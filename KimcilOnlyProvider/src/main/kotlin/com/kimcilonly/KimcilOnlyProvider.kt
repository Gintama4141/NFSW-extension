package com.kimcilonly

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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
        "$mainUrl/category/live-apk/" to "Viral",
        "$mainUrl/category/film-semi/" to "Film Semi"
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
            newMovieSearchResponse(title, link, TvType.NSFW) { this.posterUrl = image }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item, .gmr-item-modulepost").mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a, .entry-title a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            val img = element.selectFirst("img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            image = image?.replace(Regex("-\\d+x\\d+\\."), ".")
            newMovieSearchResponse(title, link, TvType.NSFW) { this.posterUrl = image }
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
        val playerUrls = listOf(data, "$data?player=2", "$data?player=3", "$data?player=4")

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
                        fullUrl.contains("doods") -> {
                            extractDoodLike(fullUrl, data, callback)
                            extractVidaraLike(fullUrl, data, callback)
                        }
                        fullUrl.contains("pecah") -> {
                            extractPecahLike(fullUrl, data, subtitleCallback, callback)
                        }
                        else -> {
                            loadExtractor(fullUrl, data, subtitleCallback, callback)
                            extractGeneric(fullUrl, data, callback)
                        }
                    }
                } catch (_: Exception) {}
            })
        }

        allJobs.awaitAll()
        return@coroutineScope true
    }

    private suspend fun extractVidaraLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val baseUrl = url.substringBefore("/e/")
            val filecode = url.substringAfterLast("/e/").substringBefore("?")
            if (filecode.isEmpty()) return
            val response = app.post(
                "$baseUrl/api/stream",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url
                ),
                requestBody = """{"filecode":"$filecode","device":"android"}""".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            ).parsedSafe<VidaraStreamResponse>() ?: return
            val streamUrl = response.streaming_url ?: return
            M3u8Helper.generateM3u8(name, streamUrl, baseUrl, headers = mapOf("Referer" to baseUrl)).forEach(callback)
        } catch (_: Exception) {}
    }

    private suspend fun extractPecahLike(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val baseUrl = url.substringBefore("/embed")
            val doc = app.get(url, referer = referer).document
            val movieId = doc.selectFirst("#embed-player")?.attr("data-movie-id") ?: return
            val serverId = doc.selectFirst("a.server")?.attr("data-id") ?: return
            val response = app.get(
                "$baseUrl/ajax/get_stream_link",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url),
                data = mapOf("id" to serverId, "movie" to movieId, "is_init" to "", "captcha" to "", "ref" to "")
            ).parsedSafe<PecahResponse>() ?: return
            val link = response.data?.link ?: return
            when {
                link.contains("byse") -> byseExtractor.getUrl(link, referer, subtitleCallback, callback)
                link.contains("doods") -> { extractDoodLike(link, referer, callback); extractVidaraLike(link, referer, callback) }
                link.contains("playmogo") || link.contains("pendek") -> extractDoodLike(link, referer, callback)
                else -> { loadExtractor(link, referer, subtitleCallback, callback); extractGeneric(link, referer, callback) }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractDoodLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url, referer = referer).document
            val scripts = document.select("script")

            for (script in scripts) {
                val scriptContent = script.html()
                val passMd5Match = Regex("""['"]\s*/pass_md5/([^'"]+)['"]""").find(scriptContent)
                if (passMd5Match != null) {
                    val passMd5Path = passMd5Match.groupValues[1]
                    val baseUrl = url.substringBefore("/e/")
                    val passMd5Url = "$baseUrl/pass_md5/$passMd5Path"
                    val response = app.get(
                        passMd5Url,
                        headers = mapOf("Referer" to url, "X-Requested-With" to "XMLHttpRequest")
                    ).text
                    if (response.isNotBlank() && response.startsWith("http")) {
                        val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                        val tokenMatch = Regex("""token=([^&'"]+)""").find(scriptContent)
                        val token = tokenMatch?.groupValues?.get(1) ?: ""
                        val videoUrl = "$response$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"
                        callback.invoke(
                            newExtractorLink(name, "DoodStream", videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                }

                val doodMatch = Regex("""['"]\s*/dood\?op=watch[^'"]*hash=([^&'"]+)[^'"]*token=([^&'"]+)['"]""").find(scriptContent)
                if (doodMatch != null) {
                    val hash = doodMatch.groupValues[1]
                    val token = doodMatch.groupValues[2]
                    val baseUrl = url.substringBefore("/e/")
                    val watchUrl = "$baseUrl/dood?op=watch&hash=$hash&token=$token&embed=true"
                    val response = app.get(
                        watchUrl,
                        headers = mapOf("Referer" to url, "X-Requested-With" to "XMLHttpRequest")
                    ).text
                    if (response.isNotBlank() && response.startsWith("http")) {
                        val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                        val videoUrl = "$response$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"
                        callback.invoke(
                            newExtractorLink(name, "DoodStream", videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                }
            }

            val pageHtml = document.html()
            val mp4Match = Regex(""""(https?://[^"]+\.mp4[^"]*)"""").find(pageHtml)
            if (mp4Match != null) {
                val videoUrl = mp4Match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
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
        } catch (_: Exception) { ByteArray(0) }
    }

    private fun getBaseUrl(url: String): String =
        runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)

    private fun getCodeFromUrl(url: String): String =
        runCatching { URI(url).path?.trimEnd('/')?.substringAfterLast('/') }.getOrNull() ?: ""

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
        var playback = app.get(playbackUrl, headers = playbackHeaders).parsedSafe<PlaybackRoot>()?.playback
        if (playback == null) {
            playback = try {
                app.post(
                    playbackUrl,
                    headers = playbackHeaders + mapOf("Content-Type" to "application/json"),
                    requestBody = "{}".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                ).parsedSafe<PlaybackRoot>()?.playback
            } catch (_: Exception) { null }
        }
        if (playback == null) return
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
data class VidaraStreamResponse(val streaming_url: String? = null)
data class PecahResponse(val success: Boolean, val data: PecahData?)
data class PecahData(val link: String?)
