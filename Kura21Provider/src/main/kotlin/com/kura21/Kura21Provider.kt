package com.kura21

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.CancellationException
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

class Kura21Provider : MainAPI() {

    override var name = "Kura21"
    override var mainUrl = "https://kurakura21.com"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/genre/indo-18/" to "Indo 18++",
        "$mainUrl/genre/korea-18/" to "Korea 18++",
        "$mainUrl/genre/cina-18/" to "Cina 18++",
        "$mainUrl/country/philippines/" to "Pinoy",
        "$mainUrl/genre/barat-18/" to "Barat 18++"
    )

    companion object {
        private const val TAG = "Kura21"
        private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val PACKER_REGEX = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{[^}]*\}\('(.+?)',(\d+),(\d+),'([^']*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val PASS_MD5_PATTERNS = listOf(
            Regex("""['"]\s*/pass_md5/([^'"]+)['"]"""),
            Regex("""/pass_md5/([a-zA-Z0-9\-]+/[a-zA-Z0-9]+)"""),
            Regex("""pass_md5['"]\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""['"](/pass_md5/[^'"]+)['"]"""),
        )
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val HLS_URL_REGEX = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
        private val MP4_URL_REGEX = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
        private val HASH_REGEX = Regex(""""hash"\s*:\s*"([a-f0-9]{32,})"""")
        private val VIDEO_URL_REGEX = Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""")
        private val CDN_DOMAINS = listOf(
            "edge2-waw-sprintcdn.r66nv9ed.com",
            "hw6ugf3856NN.tnmr.org",
            "hw.jmnl.xyz",
            "hw.cdnst1.xyz"
        )
        private val DOOD_LIKE_DOMAINS = listOf(
            "playmogo", "pendek", "dood", "luluvdo", "lulustream", "luluvid",
            "kr21.click", "kurakura21.space", "kr21.click", "turtle4up.top"
        )
        private val BYSE_DOMAINS = listOf("kr21.click", "byse")
        private val TURTLE4UP_DOMAINS = listOf("turtle4up.top")
        private val EM_TURBOVID_DOMAINS = listOf("emturbovid.com")

        private fun <T> Result<T>.ignoreCancellation(): Result<T> =
            onFailure { if (it is CancellationException) throw it }

        private fun getBaseUrl(url: String): String =
            runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)

        private fun getCodeFromUrl(url: String): String =
            runCatching { URI(url).path?.substringAfter("/e/")?.substringBefore("/")?.trimEnd('/') }.getOrNull() ?: ""
    }

    // --- Extractor instances ---
    private val byseExtractor by lazy { ByseSXLocal() }
    private val turtle4upExtractor by lazy { Turtle4upExtractor() }
    private val emturbovidExtractor by lazy { EmturbovidExtractor() }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        return runCatching {
            val items = parseSearchResults(app.get(url).document)
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }.ignoreCancellation().getOrNull()
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return runCatching {
            parseSearchResults(app.get("$mainUrl/?s=$query").document)
        }.ignoreCancellation().getOrNull()
    }

    override suspend fun load(url: String): LoadResponse? = runCatching {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" | ")?.trim()
            ?: return@runCatching null

        newMovieLoadResponse(title, url, TvType.NSFW, "$url#p2") {
            posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            plot = document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst(".entry-content p")?.text()
            tags = document.select(".tags-list a.label, .categories a, .video-tags a").map { it.text().trim() }
        }
    }.ignoreCancellation().getOrNull()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val (pageUrl, tab) = parsePageTab(data)

        runCatching {
            val document = app.get(pageUrl).document
            val postId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id")

            if (postId != null && fetchAjaxContent(postId, tab, pageUrl, callback)) return@runCatching

            extractIframes(document).distinct().map { src ->
                async(Dispatchers.IO) {
                    runCatching {
                        routeAndExtract(src, pageUrl, subtitleCallback, callback)
                    }.ignoreCancellation().onFailure { e ->
                        Log.w(TAG, "Extract error: ${e.message}")
                    }
                }
            }.awaitAll()
        }.ignoreCancellation().onFailure { e -> Log.w(TAG, "loadLinks: ${e.message}") }

        return@coroutineScope true
    }

    // ==================== Parsing ====================

    private fun parseSearchResults(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("article.item-infinite").mapNotNull { element ->
            val title = element.selectFirst("h2.entry-title a")?.text()
                ?: element.selectFirst(".entry-title")?.text()
                ?: return@mapNotNull null

            val link = element.selectFirst(".gmr-watch-movie a")?.attr("href")
                ?: element.selectFirst("h2.entry-title a")?.attr("href")
                ?: return@mapNotNull null

            val image = element.selectFirst(".content-thumbnail img")?.attr("src")
                ?.takeIf { it.isNotBlank() }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    // ==================== Helpers ====================

    private fun parsePageTab(data: String): Pair<String, String> {
        return if (data.contains("#")) {
            val parts = data.split("#", limit = 2)
            parts[0] to parts[1]
        } else {
            data to "p2"
        }
    }

    private fun extractIframes(doc: org.jsoup.nodes.Document): List<String> {
        val iframes = doc.select("iframe").mapNotNull { iframe ->
            iframe.attr("data-lazy-src").takeIf { it.isNotBlank() && it != "about:blank" }
                ?: iframe.attr("src").takeIf { it.isNotBlank() && it != "about:blank" }
        }
        if (iframes.isNotEmpty()) return iframes.map { fixUrl(it) }

        return doc.select("meta[itemprop=embedURL]").mapNotNull { meta ->
            meta.attr("content").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }
    }

    private fun randomString(length: Int): String =
        (1..length).map { ALPHANUMERIC.random() }.joinToString("")

    // ==================== AJAX ====================

    private suspend fun fetchAjaxContent(
        postId: String,
        tab: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val response = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "muvipro_player_content", "tab" to tab, "post_id" to postId),
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text

        val iframeSrc = IFRAME_SRC_REGEX.find(response)?.groupValues?.get(1)
        if (iframeSrc != null) {
            routeAndExtract(fixUrl(iframeSrc), referer, {}, callback)
            return@runCatching true
        }

        val hlsMatch = HLS_URL_REGEX.find(response)
        if (hlsMatch != null) {
            M3u8Helper.generateM3u8(name, hlsMatch.value.replace("\\/", "/"), referer).forEach(callback)
            return@runCatching true
        }

        false
    }.ignoreCancellation().getOrDefault(false)

    // ==================== Routing ====================

    private suspend fun routeAndExtract(
        src: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            BYSE_DOMAINS.any { src.contains(it) } ->
                byseExtractor.getUrl(src, referer, subtitleCallback, callback)
            TURTLE4UP_DOMAINS.any { src.contains(it) } ->
                turtle4upExtractor.getUrl(src, referer, subtitleCallback, callback)
            EM_TURBOVID_DOMAINS.any { src.contains(it) } ->
                emturbovidExtractor.getUrl(src, referer, subtitleCallback, callback)
            DOOD_LIKE_DOMAINS.any { src.contains(it) } ->
                extractDoodLike(src, referer, callback)
            src.contains("terbit2") ->
                extractTerbit2(src, referer, callback)
            else -> {
                loadExtractor(src, referer, subtitleCallback, callback)
                extractGeneric(src, referer, callback)
            }
        }
    }

    // ==================== DoodStream ====================

    private suspend fun extractDoodLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fetchUrl = url.replace("/e/", "/d/")

        runCatching {
            val document = app.get(fetchUrl, referer = referer).document
            val pageHtml = document.html()

            if (tryPackerDecode(pageHtml, fetchUrl, callback)) return@runCatching
            if (tryPassMd5FromDoc(document, fetchUrl, callback)) return@runCatching
            if (tryDirectVideoUrl(pageHtml, fetchUrl, callback)) return@runCatching
            tryHashHls(pageHtml, fetchUrl, callback)
        }.ignoreCancellation().onFailure { e -> Log.w(TAG, "extractDoodLike: ${e.message}") }
    }

    private suspend fun tryPackerDecode(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val decoded = decodePackerScript(html) ?: return false
        val m3u8Match = HLS_URL_REGEX.find(decoded) ?: return false
        val videoUrl = m3u8Match.value.replace("\\/", "/")
        M3u8Helper.generateM3u8(name, videoUrl, referer, headers = mapOf("Referer" to referer)).forEach(callback)
        return true
    }

    private suspend fun tryPassMd5FromDoc(
        doc: org.jsoup.nodes.Document,
        fetchUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (script in doc.select("script")) {
            val path = findPassMd5(script.html()) ?: continue
            if (fetchPassMd5(path, fetchUrl, callback)) return true
        }
        return false
    }

    private suspend fun fetchPassMd5(path: String, fetchUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val baseUrl = fetchUrl.substringBefore("/e/").substringBefore("/d/")
        return runCatching {
            val response = app.get(
                "$baseUrl/pass_md5/$path",
                headers = mapOf("Referer" to fetchUrl, "X-Requested-With" to "XMLHttpRequest")
            ).text

            if (response.isNotBlank() && response.startsWith("http")) {
                val token = path.substringAfterLast("/")
                val videoUrl = "$response${randomString(10)}?token=$token&expiry=${System.currentTimeMillis()}"
                callback(newExtractorLink(name, "DoodStream", videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = fetchUrl
                    this.quality = Qualities.Unknown.value
                })
                true
            } else false
        }.ignoreCancellation().getOrDefault(false)
    }

    private suspend fun tryDirectVideoUrl(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val m3u8Match = HLS_URL_REGEX.find(html) ?: return false
        val videoUrl = m3u8Match.value.replace("\\/", "/")
        M3u8Helper.generateM3u8(name, videoUrl, referer, headers = mapOf("Referer" to referer)).forEach(callback)
        return true
    }

    private suspend fun tryHashHls(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val decoded = decodePackerScript(html)
        val target = decoded ?: html
        val hashMatch = HASH_REGEX.find(target) ?: return false
        val fileId = referer.substringAfterLast("/e/").substringAfterLast("/d/").substringBefore("?")

        if (fileId.isEmpty()) return false

        val dirMatch = Regex("""/hls2/(\d{2})/(\d+)/""").find(target)
        val dir1 = dirMatch?.groupValues?.get(1) ?: "01"
        val dir2 = dirMatch?.groupValues?.get(2) ?: "00000"
        val videoHash = "${fileId}_x"

        for (cdn in CDN_DOMAINS) {
            val hlsUrl = "https://$cdn/hls2/$dir1/$dir2/$videoHash/master.m3u8?e=28800&f=$fileId&sp=5500&p=0"
            val result = runCatching {
                M3u8Helper.generateM3u8(name, hlsUrl, referer, headers = mapOf("Referer" to referer))
                    .forEach(callback)
            }.ignoreCancellation()
            if (result.isSuccess) return true
        }
        return false
    }

    // ==================== Terbit2 ====================

    private suspend fun extractTerbit2(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val html = app.get(url, referer = referer).text
            val content = decodePackerScript(html) ?: html

            HLS_URL_REGEX.findAll(content).forEach { match ->
                val videoUrl = match.value.replace("\\/", "/")
                M3u8Helper.generateM3u8(name, videoUrl, url, headers = mapOf("Referer" to url)).forEach(callback)
            }

            MP4_URL_REGEX.findAll(content).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                callback(newExtractorLink(name, "Terbit2", videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                })
            }
        }.ignoreCancellation().onFailure { e -> Log.w(TAG, "extractTerbit2: ${e.message}") }
    }

    // ==================== Generic ====================

    private suspend fun extractGeneric(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val html = app.get(url, referer = referer).text
            VIDEO_URL_REGEX.findAll(html).forEach { match ->
                val videoUrl = match.value.replace("\\/", "/")
                callback(newExtractorLink(name, "Generic", videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                })
            }
        }
    }

    // ==================== Packer Decoder ====================

    private fun decodePackerScript(html: String): String? = runCatching {
        val match = PACKER_REGEX.find(html) ?: return@runCatching null
        val p = match.groupValues[1]
        val a = match.groupValues[2].toIntOrNull() ?: return@runCatching null
        val c = match.groupValues[3].toIntOrNull() ?: return@runCatching null
        val k = match.groupValues[4].split('|')

        var result = p
        for (i in (c - 1) downTo 0) {
            if (i < k.size && k[i].isNotEmpty()) {
                result = result.replace(Regex("\\b${Regex.escape(i.toString(a))}\\b"), k[i])
            }
        }
        result
    }.ignoreCancellation().onFailure { e -> Log.w(TAG, "decodePacker: ${e.message}") }.getOrNull()

    private fun findPassMd5(content: String): String? {
        for (pattern in PASS_MD5_PATTERNS) {
            val match = pattern.find(content)
            if (match != null) return match.groupValues[1].removePrefix("/")
        }
        return null
    }

    // ==================== ByseSXLocal Extractor ====================

    private inner class ByseSXLocal : ExtractorApi() {
        override var name = "Byse"
        override var mainUrl = "https://byse.sx"
        override val requiresReferer = true

        private fun b64UrlDecode(s: String): ByteArray = runCatching {
            val fixed = s.replace('-', '+').replace('_', '/')
            val pad = when (fixed.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            Base64.decode(fixed + pad, Base64.DEFAULT)
        }.getOrElse { ByteArray(0) }

        private fun getBaseUrl(url: String): String =
            runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)

        private fun getCodeFromUrl(url: String): String =
            runCatching { URI(url).path?.substringAfter("/e/")?.substringBefore("/")?.trimEnd('/') }.getOrNull() ?: ""

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            runCatching {
                val refererUrl = getBaseUrl(url)
                val code = getCodeFromUrl(url)
                if (code.isEmpty()) return@runCatching

                // Step 1: Get embed details
                val detailsUrl = "$refererUrl/api/videos/$code/embed/details"
                val details = tryParseJson<DetailsRoot>(app.get(detailsUrl).text) ?: return@runCatching

                val embedFrameUrl = details.embedFrameUrl
                val embedBase = getBaseUrl(embedFrameUrl)
                val embedCode = runCatching { URI(embedFrameUrl).path?.substringAfter("/e/")?.substringBefore("/")?.trimEnd('/') }.getOrNull() ?: ""

                // Step 2: Get playback data with proper headers
                val playbackUrl = "$embedBase/api/videos/$embedCode/embed/playback"
                val playbackHeaders = mapOf(
                    "accept" to "*/*",
                    "referer" to embedFrameUrl,
                    "x-embed-parent" to (referer ?: mainUrl)
                )

                // Try GET first
                var playback = tryParseJson<PlaybackRoot>(app.get(playbackUrl, headers = playbackHeaders).text)?.playback
                if (playback == null) {
                    // Try POST with empty JSON body
                    playback = tryParseJson<PlaybackRoot>(
                        app.post(
                            playbackUrl,
                            headers = playbackHeaders + mapOf("Content-Type" to "application/json"),
                            requestBody = "{}".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                        ).text
                    )?.playback
                }

                playback?.let { p ->
                    val keyBytes = b64UrlDecode(p.keyParts[0]) + b64UrlDecode(p.keyParts[1])
                    val ivBytes = b64UrlDecode(p.iv)
                    val cipherBytes = b64UrlDecode(p.payload)

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
                    val jsonStr = String(cipher.doFinal(cipherBytes), Charsets.UTF_8).removePrefix("\uFEFF")

                    tryParseJson<PlaybackDecrypt>(jsonStr)?.sources?.firstOrNull()?.url?.let { streamUrl ->
                        M3u8Helper.generateM3u8(name, streamUrl, refererUrl, headers = mapOf("Referer" to refererUrl))
                            .forEach(callback)
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "ByseSXLocal getUrl error: ${e.message}", e)
            }
        }
    }

    // ==================== Turtle4up Extractor ====================

    private inner class Turtle4upExtractor : ExtractorApi() {
        override var name = "Turtle4up"
        override var mainUrl = "https://turtle4up.top"
        override val requiresReferer = true

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            // URL format: https://turtle4up.top/#{hash}
            val hash = runCatching { URI(url).fragment }.getOrNull() ?: url.substringAfterLast("#")
            if (hash.isEmpty()) return

            runCatching {
                val baseUrl = getBaseUrl(url)

                // Try video info endpoint
                val videoInfo = tryParseJson<VideoInfo>(app.get("$baseUrl/api/v1/video?id=$hash",
                    headers = mapOf("Referer" to url)
                ).text)

                if (videoInfo != null && videoInfo.url.isNotEmpty()) {
                    callback(newExtractorLink(name, "Turtle4up", videoInfo.url, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    })
                    return@runCatching
                }

                // Try info endpoint
                val info = tryParseJson<InfoResponse>(app.get("$baseUrl/api/v1/info?id=$hash",
                    headers = mapOf("Referer" to url)
                ).text)

                if (info != null && info.src.isNotEmpty()) {
                    callback(newExtractorLink(name, "Turtle4up", info.src, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    })
                    return@runCatching
                }

                // Try player endpoint (requires valid token)
                // Token might be derived from hash or obtained from page
                val player = tryParseJson<PlayerResponse>(app.get("$baseUrl/api/v1/player?t=$hash",
                    headers = mapOf("Referer" to url, "Origin" to baseUrl)
                ).text)

                if (player != null && player.url.isNotEmpty()) {
                    callback(newExtractorLink(name, "Turtle4up", player.url, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    })
                    return@runCatching
                }

                // Fallback: try download endpoint
                val download = tryParseJson<DownloadResponse>(app.get("$baseUrl/api/v1/download?id=$hash",
                    headers = mapOf("Referer" to url)
                ).text)

                if (download != null && download.url.isNotEmpty()) {
                    callback(newExtractorLink(name, "Turtle4up", download.url, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    })
                }
            }.onFailure { e ->
                Log.e(TAG, "Turtle4upExtractor error: ${e.message}", e)
            }
        }

        private fun getBaseUrl(url: String): String =
            runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)
    }

    // ==================== Emturbovid Extractor ====================

    private inner class EmturbovidExtractor : ExtractorApi() {
        override var name = "Emturbovid"
        override var mainUrl = "https://emturbovid.com"
        override val requiresReferer = true

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            // URL format: https://emturbovid.com/t/{id}
            runCatching {
                val baseUrl = getBaseUrl(url)
                val id = runCatching { URI(url).path?.substringAfter("/t/") }.getOrNull() ?: url.substringAfterLast("/")

                // Try to get video page and extract m3u8/mp4
                val html = app.get(url, referer = referer).text

                // Look for m3u8 or mp4 in page
                HLS_URL_REGEX.findAll(html).forEach { match ->
                    val videoUrl = match.value.replace("\\/", "/")
                    M3u8Helper.generateM3u8(name, videoUrl, url, headers = mapOf("Referer" to url)).forEach(callback)
                }

                MP4_URL_REGEX.findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    callback(newExtractorLink(name, "Emturbovid", videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    })
                }

                // Try to find iframe src
                val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                iframeMatch?.groupValues?.get(1)?.let { iframeSrc ->
                    val fixed = fixUrl(iframeSrc)
                    loadExtractor(fixed, url, {}, callback)
                    extractGeneric(fixed, url, callback)
                }
            }.onFailure { e ->
                Log.e(TAG, "EmturbovidExtractor error: ${e.message}", e)
            }
        }

        private fun getBaseUrl(url: String): String =
            runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)
    }

    // ==================== DTOs ====================

    data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String)
    data class PlaybackRoot(@JsonProperty("playback") val playback: Playback?)
    data class Playback(
        @JsonProperty("iv") val iv: String,
        @JsonProperty("payload") val payload: String,
        @JsonProperty("key_parts") val keyParts: List<String>
    )
    data class PlaybackDecrypt(@JsonProperty("sources") val sources: List<PlaybackDecryptSource>)
    data class PlaybackDecryptSource(@JsonProperty("url") val url: String)

    data class VideoInfo(@JsonProperty("url") val url: String)
    data class InfoResponse(@JsonProperty("src") val src: String)
    data class PlayerResponse(@JsonProperty("url") val url: String)
    data class DownloadResponse(@JsonProperty("url") val url: String)
}