package com.kurakura21

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Kurakura21Provider : MainAPI() {

    private val mapper = jacksonObjectMapper()

    override var name = "Kurakura21"
    override var mainUrl = "https://kurakura21.net"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/genre/indo-18/" to "Indo",
        "$mainUrl/genre/korea-18/" to "Korea",
        "$mainUrl/genre/barat-18/" to "Barat",
        "$mainUrl/genre/cina-18/" to "Cina",
        "$mainUrl/genre/bioskop/" to "Film Semi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url).document

        val elements = document.select("article.item-infinite")

        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            val link = titleElement?.attr("href") ?: return@mapNotNull null
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img.wp-post-image")
            val image = img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        val elements = document.select("article.item-infinite")
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            val link = titleElement?.attr("href") ?: return@mapNotNull null
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img.wp-post-image")
            val image = img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" | KURAKURA21")?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" | ")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("meta[name=description]")?.attr("content")

        val tags = document.select(".gmr-movie-on a[rel=category-tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        android.util.Log.d("K21", "loadLinks data=$data")
        try {
            val document = app.get(data).document
            android.util.Log.d("K21", "page loaded, html length=${document.html().length}")

            val postId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id")
                ?: document.selectFirst("[data-id]")?.attr("data-id")
                ?: run {
                    val html = document.html()
                    val m = Regex("""post_id["\s:=]+["']?(\d+)""").find(html)
                    m?.groupValues?.get(1)
                }
            android.util.Log.d("K21", "postId=$postId")

            if (postId != null) {
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                for (tabNum in 1..4) {
                    try {
                        val tabName = "p$tabNum"
                        android.util.Log.d("K21", "AJAX tab=$tabName postId=$postId")
                        val response = app.post(
                            ajaxUrl,
                            requestBody = "action=muvipro_player_content&tab=$tabName&post_id=$postId"
                                .toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
                        ).text
                        android.util.Log.d("K21", "AJAX response[$tabName]=${response.take(300)}")

                        val iframeSrc = Regex("""src=["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                        android.util.Log.d("K21", "iframeSrc[$tabName]=$iframeSrc")

                        if (iframeSrc != null) {
                            val iframeUrl = fixUrl(iframeSrc)
                            android.util.Log.d("K21", "iframeUrl[$tabName]=$iframeUrl")
                            jobs.add(async(Dispatchers.IO) {
                                try {
                                    if (iframeUrl.contains("kr21.click")) {
                                        android.util.Log.d("K21", "calling extractKr21Click for $iframeUrl")
                                        extractKr21Click(iframeUrl, callback)
                                    } else {
                                        android.util.Log.d("K21", "calling loadExtractor for $iframeUrl")
                                        loadExtractor(iframeUrl, data, subtitleCallback, callback)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("K21", "extract error: ${e.message}")
                                }
                                Unit
                            })
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("K21", "AJAX error tab=$tabNum: ${e.message}")
                    }
                }

                jobs.awaitAll()
                android.util.Log.d("K21", "all jobs completed, jobs.size=${jobs.size}")
            } else {
                android.util.Log.e("K21", "postId is null!")
            }
        } catch (e: Exception) {
            android.util.Log.e("K21", "loadLinks error: ${e.message}", e)
        }

        return@coroutineScope true
    }

    private suspend fun extractKr21Click(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = url.substringAfter("/e/", "").substringBefore("/").substringBefore("?")
            if (code.isEmpty()) { android.util.Log.e("K21", "empty code from $url"); return }

            val baseUrl = runCatching {
                URI(url).let { "${it.scheme}://${it.host}" }
            }.getOrDefault(url)
            android.util.Log.d("K21", "code=$code baseUrl=$baseUrl")

            val detailsUrl = "$baseUrl/api/videos/$code/embed/details"
            val details = app.get(detailsUrl, headers = mapOf("Referer" to mainUrl)).text
            android.util.Log.d("K21", "details=$details")
            if (details.isBlank()) return

            val embedFrameUrl = Regex(""""embed_frame_url"\s*:\s*"([^"]+)"""").find(details)?.groupValues?.get(1)
            android.util.Log.d("K21", "embedFrameUrl=$embedFrameUrl")
            val embedBase = embedFrameUrl?.let {
                runCatching { URI(it).let { u -> "${u.scheme}://${u.host}" } }.getOrNull()
            } ?: baseUrl
            val embedCode = embedFrameUrl?.let {
                it.substringAfterLast("/").substringBefore("?")
            } ?: code
            android.util.Log.d("K21", "embedFrameUrl=$embedFrameUrl embedBase=$embedBase embedCode=$embedCode")

            val playbackUrl = "$embedBase/api/videos/$embedCode/embed/playback"
            android.util.Log.d("K21", "playbackUrl=$playbackUrl")

            val body = """{"fingerprint":{"token":"fp_android","viewer_id":"android_$code","device_id":"android_device","confidence":0.95}}"""

            var responseText = ""
            try {
                responseText = app.get(playbackUrl, headers = mapOf(
                    "accept" to "*/*",
                    "referer" to embedFrameUrl,
                    "x-embed-parent" to baseUrl
                )).text
                android.util.Log.d("K21", "GET playback response=${responseText.take(200)}")
            } catch (e: Exception) {
                android.util.Log.e("K21", "GET playback failed: ${e.message}")
            }

            if (responseText.isBlank() || responseText.contains("error")) {
                try {
                    responseText = app.post(
                        playbackUrl,
                        headers = mapOf(
                            "accept" to "*/*",
                            "referer" to embedFrameUrl,
                            "x-embed-parent" to baseUrl,
                            "Content-Type" to "application/json"
                        ),
                        requestBody = body.toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                    ).text
                    android.util.Log.d("K21", "POST playback response=${responseText.take(200)}")
                } catch (e: Exception) {
                    android.util.Log.e("K21", "POST playback failed: ${e.message}")
                }
            }

            if (responseText.isBlank() || responseText.contains("error")) {
                android.util.Log.e("K21", "no valid playback response"); return
            }

            android.util.Log.d("K21", "POST playback response=${responseText.take(200)}")

            val iv = Regex(""""iv"\s*:\s*"([^"]+)"""").find(responseText)?.groupValues?.get(1)
            val payload = Regex(""""payload"\s*:\s*"([^"]+)"""").find(responseText)?.groupValues?.get(1)
            val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(responseText)?.groupValues?.get(1)
            val keyPartsRaw = Regex(""""key_parts"\s*:\s*\[([^\]]+)\]""").find(responseText)?.groupValues?.get(1) ?: ""
            val keyParts = Regex(""""([^"]+)"""").findAll(keyPartsRaw).map { it.groupValues[1] }.toList()

            android.util.Log.d("K21", "iv=$iv payload=${payload?.take(20)} version=$version keyParts=${keyParts.size}")

            if (iv == null || payload == null || version == null || keyParts.isEmpty()) {
                android.util.Log.e("K21", "failed to parse playback fields"); return
            }

            val decryptedJson = decryptKr21Payload(iv, payload, version, keyParts)
            if (decryptedJson == null) { android.util.Log.e("K21", "decrypt failed"); return }
            android.util.Log.d("K21", "decrypted=${decryptedJson.take(200)}")

            val streamUrl = runCatching { mapper.readValue<Kr21DecryptedSource>(decryptedJson) }.getOrNull()?.url
                ?: runCatching { mapper.readValue<Kr21DecryptedUrl>(decryptedJson) }.getOrNull()?.sources?.firstOrNull()?.url
            if (streamUrl == null) { android.util.Log.e("K21", "streamUrl is null"); return }
            android.util.Log.d("K21", "streamUrl=$streamUrl")

            if (streamUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    "Kurakura21", streamUrl, embedBase,
                    headers = mapOf("Referer" to embedBase)
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink("Kurakura21", "Kurakura21", streamUrl, ExtractorLinkType.VIDEO) {
                        this.referer = embedBase
                    }
                )
            }
            android.util.Log.d("K21", "SUCCESS! callback called")
        } catch (e: Exception) {
            android.util.Log.e("K21", "FATAL: ${e.message}", e)
        }
    }

    private fun decryptKr21Payload(iv: String, payload: String, version: String, keyParts: List<String>): String? {
        return try {
            val verNum = version.trim().toIntOrNull()
            val idx1: Int
            val idx2: Int
            if (verNum != null && verNum >= 1 && verNum <= 20 && keyParts.size >= 30) {
                idx1 = verNum - 1
                idx2 = 30 - verNum
            } else {
                return null
            }
            val p1 = b64UrlDecode(keyParts[idx1])
            val p2 = b64UrlDecode(keyParts[idx2])
            val keyBytes = p1 + p2
            if (keyBytes.size < 32) return null
            val aesKey = keyBytes.copyOf(32)
            val ivBytes = b64UrlDecode(iv)
            val cipherBytes = b64UrlDecode(payload)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, ivBytes))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8).removePrefix("\uFEFF")
        } catch (_: Exception) { null }
    }

    private fun b64UrlDecode(s: String): ByteArray {
        return try {
            val fixed = s.replace('-', '+').replace('_', '/')
            val pad = when (fixed.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            Base64.decode(fixed + pad, Base64.DEFAULT)
        } catch (_: Exception) { ByteArray(0) }
    }
}

data class Kr21Playback(
    @JsonProperty("algorithm") val algorithm: String? = null,
    @JsonProperty("iv") val iv: String = "",
    @JsonProperty("payload") val payload: String = "",
    @JsonProperty("key_parts") val keyParts: List<String> = emptyList(),
    @JsonProperty("version") val version: String? = null
)

data class Kr21PlaybackRoot(
    @JsonProperty("playback") val playback: Kr21Playback? = null
)

data class Kr21DecryptedSource(
    @JsonProperty("url") val url: String? = null
)

data class Kr21DecryptedUrl(
    @JsonProperty("sources") val sources: List<Kr21DecryptedSource>? = null
)

data class ByseDetailsRoot(
    @JsonProperty("embed_frame_url") val embedFrameUrl: String? = null
)
