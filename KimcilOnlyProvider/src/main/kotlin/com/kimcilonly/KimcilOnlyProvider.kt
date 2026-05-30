package com.kimcilonly

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KimcilOnlyProvider : MainAPI() {

    override var name = "KimcilOnly"
    override var mainUrl = "https://kimcilonlyofc.my"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val byseExtractor by lazy { ByseSXLocal() }

    override val mainPage = mainPageOf(
        "$mainUrl/category/film-semi/" to "Film Semi",
        "$mainUrl/category/live-apk/" to "Indo 18+"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val elements = document.select("article.item, .gmr-item-modulepost")

        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a, .entry-title a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")

            val img = element.selectFirst("img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            image = image?.replace(Regex("-\\d+x\\d+\\."), ".")

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = image
            }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        val elements = document.select("article.item, .gmr-item-modulepost")
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a, .entry-title a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")

            val img = element.selectFirst("img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            image = image?.replace(Regex("-\\d+x\\d+\\."), ".")

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.select(".entry-content p").joinToString("\n") { it.text() }.trim()
        val tags = document.select(".gmr-moviedata:contains(Genre:) a").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val allJobs = mutableListOf<kotlinx.coroutines.Deferred<Any>>()

        val playerUrls = listOf(
            data,
            "$data?player=2",
            "$data?player=3",
            "$data?player=4"
        )

        playerUrls.forEach { playerUrl ->
            allJobs.add(async(Dispatchers.IO) {
                try {
                    val document = app.get(playerUrl, referer = data).document

                    val iframeSrc = document.select("iframe[src]").mapNotNull {
                        it.attr("src").takeIf { src -> src.isNotBlank() }
                    }.firstOrNull() ?: return@async

                    val fullUrl = fixUrl(iframeSrc)

                    when {
                        fullUrl.contains("byse") -> {
                            byseExtractor.getUrl(fullUrl, data, subtitleCallback, callback)
                        }
                        fullUrl.contains("playmogo") || fullUrl.contains("pendek") -> {
                            extractDoodLike(fullUrl, data, callback)
                        }
                        else -> {
                            loadExtractor(fullUrl, data, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            })
        }

        allJobs.awaitAll()
        return@coroutineScope true
    }

    private suspend fun extractDoodLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url, referer = referer).document

            // Find pass_md5 endpoint in scripts
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptContent = script.html()

                // Pattern: $.get('/pass_md5/{hash}/{token}', function(data)
                val passMd5Match = Regex("""['"]\s*/pass_md5/([^'"]+)['"]""").find(scriptContent)
                if (passMd5Match != null) {
                    val passMd5Path = passMd5Match.groupValues[1]
                    val passMd5Url = "$url/pass_md5/$passMd5Path"

                    val response = app.get(
                        passMd5Url,
                        headers = mapOf(
                            "Referer" to url,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text

                    if (response.isNotBlank() && response.startsWith("http")) {
                        val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                        val tokenMatch = Regex("""token=([^&'"]+)""").find(scriptContent)
                        val token = tokenMatch?.groupValues?.get(1) ?: ""
                        val videoUrl = "$response$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "DoodStream",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                }

                // Alternative pattern: look for /dood?op=watch
                val doodMatch = Regex("""['"]\s*/dood\?op=watch[^'"]*hash=([^&'"]+)[^'"]*token=([^&'"]+)['"]""").find(scriptContent)
                if (doodMatch != null) {
                    val hash = doodMatch.groupValues[1]
                    val token = doodMatch.groupValues[2]
                    val watchUrl = "$url/dood?op=watch&hash=$hash&token=$token&embed=true"

                    val response = app.get(
                        watchUrl,
                        headers = mapOf(
                            "Referer" to url,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text

                    if (response.isNotBlank() && response.startsWith("http")) {
                        val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                        val videoUrl = "$response$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "DoodStream",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                }
            }

            // Fallback: try to find video URL directly in page
            val pageHtml = document.html()
            val mp4Match = Regex(""""(https?://[^"]+\.mp4[^"]*)"""").find(pageHtml)
            if (mp4Match != null) {
                val videoUrl = mp4Match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private fun parseDurationISO(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        val match = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(duration) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        val seconds = match.groupValues[3].toIntOrNull() ?: 0
        return hours * 3600 + minutes * 60 + seconds
    }
}

open class ByseSXLocal : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64UrlDecode(s: String): ByteArray {
        return try {
            val fixed = s.replace('-', '+').replace('_', '/')
            val pad = when (fixed.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            Base64.decode(fixed + pad, Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)
    }

    private fun getCodeFromUrl(url: String): String {
        return runCatching { URI(url).path?.trimEnd('/')?.substringAfterLast('/') }.getOrNull() ?: ""
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val refererUrl = getBaseUrl(url)
        val code = getCodeFromUrl(url)
        if (code.isEmpty()) return

        val detailsUrl = "$refererUrl/api/videos/$code/embed/details"
        val details = app.get(detailsUrl).parsedSafe<DetailsRoot>() ?: return

        val embedFrameUrl = details.embedFrameUrl
        val embedBase = getBaseUrl(embedFrameUrl)
        val embedCode = getCodeFromUrl(embedFrameUrl)

        val playbackUrl = "$embedBase/api/videos/$embedCode/embed/playback"
        val playbackHeaders = mapOf(
            "accept" to "*/*",
            "referer" to embedFrameUrl,
            "x-embed-parent" to (referer ?: mainUrl)
        )

        val playback = app.get(playbackUrl, headers = playbackHeaders).parsedSafe<PlaybackRoot>()?.playback ?: return

        try {
            val keyBytes = b64UrlDecode(playback.keyParts[0]) + b64UrlDecode(playback.keyParts[1])
            val ivBytes = b64UrlDecode(playback.iv)
            val cipherBytes = b64UrlDecode(playback.payload)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))

            val jsonStr = String(cipher.doFinal(cipherBytes), Charsets.UTF_8).removePrefix("\uFEFF")

            tryParseJson<PlaybackDecrypt>(jsonStr)?.sources?.firstOrNull()?.url?.let { streamUrl ->
                M3u8Helper.generateM3u8(name, streamUrl, refererUrl, headers = mapOf("Referer" to refererUrl)).forEach(callback)
            }
        } catch (_: Exception) {}
    }
}

data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String)
data class PlaybackRoot(@JsonProperty("playback") val playback: Playback)
data class Playback(@JsonProperty("iv") val iv: String, @JsonProperty("payload") val payload: String, @JsonProperty("key_parts") val keyParts: List<String>)
data class PlaybackDecrypt(@JsonProperty("sources") val sources: List<PlaybackDecryptSource>)
data class PlaybackDecryptSource(@JsonProperty("url") val url: String)
