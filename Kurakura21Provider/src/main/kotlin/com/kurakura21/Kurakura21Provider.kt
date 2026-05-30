package com.kurakura21

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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
        val document = app.get(data).document

        val postId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id") ?: run {
            document.selectFirst("[data-id]")?.attr("data-id")
        }

        if (postId != null) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            for (tabNum in 1..4) {
                try {
                    val tabName = "p$tabNum"
                    val response = app.post(
                        ajaxUrl,
                        requestBody = "action=muvipro_player_content&tab=$tabName&post_id=$postId"
                            .toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
                    ).document

                    val iframeSrc = response.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                    if (iframeSrc != null) {
                        val iframeUrl = fixUrl(iframeSrc)
                        jobs.add(async(Dispatchers.IO) {
                            try {
                                when {
                                    iframeUrl.contains("kr21.click") -> {
                                        extractKr21Click(iframeUrl, data, callback)
                                    }
                                    iframeUrl.contains("turtle4up") -> {
                                        extractTurtleUp(iframeUrl, data, callback)
                                    }
                                    else -> {
                                        loadExtractor(iframeUrl, data, subtitleCallback, callback)
                                    }
                                }
                            } catch (_: Exception) {}
                        })
                    }
                } catch (_: Exception) {}
            }

            jobs.awaitAll()
        }

        return@coroutineScope true
    }

    private suspend fun extractKr21Click(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = URI(url).path?.trimEnd('/')?.substringAfterLast('/') ?: return
            if (code.isEmpty()) return

            val baseUrl = runCatching {
                URI(url).let { "${it.scheme}://${it.host}" }
            }.getOrDefault(url)

            val playbackUrl = "$baseUrl/api/videos/$code/embed/playback"

            val fakeFingerprint = """{"fingerprint":{"token":"fp_android","viewer_id":"android_${code}","device_id":"android_device","confidence":0.95}}"""

            val response = app.post(
                playbackUrl,
                headers = mapOf(
                    "Referer" to "$baseUrl/e/$code/",
                    "x-embed-parent" to referer,
                    "Content-Type" to "application/json"
                ),
                requestBody = fakeFingerprint.toRequestBody("application/json".toMediaTypeOrNull())
            ).parsedSafe<Kr21PlaybackRoot>() ?: return

            val pb = response.playback ?: return
            val decryptedJson = decryptKr21Payload(pb) ?: return
            val sourceUrl = tryParseJson<Kr21DecryptedSource>(decryptedJson)?.url ?: return

            M3u8Helper.generateM3u8(
                "Kurakura21",
                sourceUrl,
                baseUrl,
                headers = mapOf("Referer" to baseUrl)
            ).forEach(callback)
        } catch (_: Exception) {}
    }

    private fun decryptKr21Payload(pb: Kr21Playback): String? {
        return try {
            val keyParts = pb.keyParts
            val version = pb.version?.trim() ?: ""
            val versionNum = version.toIntOrNull()

            val selectedParts = if (versionNum != null && versionNum in 1..20 && keyParts.size >= 30) {
                val idx1 = versionNum - 1
                val idx2 = 30 - versionNum
                listOf(keyParts[idx1], keyParts[idx2])
            } else {
                keyParts.take(2)
            }

            val keyBytes = selectedParts
                .filter { it.isNotBlank() }
                .map { b64UrlDecode(it) }
                .reduce { a, b -> a + b }

            if (keyBytes.size < 32) return null

            val aesKey = keyBytes.copyOf(32)

            val ivBytes = b64UrlDecode(pb.iv)
            val cipherBytes = b64UrlDecode(pb.payload)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, ivBytes))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
                .removePrefix("\uFEFF")
        } catch (_: Exception) { null }
    }

    private suspend fun extractTurtleUp(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val hash = url.substringAfter("#").takeIf { it.isNotBlank() } ?: return
            val baseUrl = "https://turtle4up.top"

            val playbackResponse = app.get(
                "$baseUrl/api/v1/video?id=$hash",
                headers = mapOf("Referer" to url, "Accept" to "application/json")
            ).text

            if (playbackResponse.isBlank()) return

            val responseObj = tryParseJson<TurtleUpResponse>(playbackResponse)
            val payload = responseObj?.payload ?: playbackResponse
            val iv = responseObj?.iv ?: ""
            val keyParts = responseObj?.keyParts ?: emptyList()

            if (payload.isNotBlank() && iv.isNotBlank() && keyParts.isNotEmpty()) {
                val keyBytes = keyParts
                    .filter { it.isNotBlank() }
                    .map { b64UrlDecode(it) }
                    .reduce { a, b -> a + b }

                val aesKey = if (keyBytes.size >= 32) keyBytes.copyOf(32) else return
                val ivBytes = b64UrlDecode(iv)
                val cipherBytes = b64UrlDecode(payload)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, ivBytes))
                val jsonStr = String(cipher.doFinal(cipherBytes), Charsets.UTF_8).removePrefix("\uFEFF")

                val sourceUrl = tryParseJson<Kr21DecryptedSource>(jsonStr)?.url
                    ?: tryParseJson<TurtleUpResponse>(jsonStr)?.url
                    ?: Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""").find(jsonStr)?.value
                    ?: return

                M3u8Helper.generateM3u8(
                    "Kurakura21",
                    sourceUrl,
                    baseUrl,
                    headers = mapOf("Referer" to baseUrl)
                ).forEach(callback)
                return
            }

            val videoUrl = Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""").find(playbackResponse)?.value
            if (videoUrl != null) {
                callback.invoke(
                    newExtractorLink("Kurakura21", "TurtleUp", videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = baseUrl
                    }
                )
            }
        } catch (_: Exception) {}
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

data class TurtleUpResponse(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("payload") val payload: String? = null,
    @JsonProperty("iv") val iv: String? = null,
    @JsonProperty("key_parts") val keyParts: List<String>? = null
)
