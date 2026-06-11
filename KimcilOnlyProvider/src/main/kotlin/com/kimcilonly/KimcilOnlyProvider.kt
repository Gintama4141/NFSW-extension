package com.kimcilonly

import android.util.Base64
import android.util.Log
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
    private val guploadExtractor by lazy { GUploadExtractor() }

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
        Log.d("KimcilOnly", "loadLinks: data=$data")
        val allJobs = mutableListOf<kotlinx.coroutines.Deferred<Any>>()
        val playerUrls = listOf(data, "$data?player=2", "$data?player=3", "$data?player=4")

        playerUrls.forEach { playerUrl ->
            allJobs.add(async(Dispatchers.IO) {
                try {
                    Log.d("KimcilOnly", "loadLinks: fetching $playerUrl")
                    val document = app.get(playerUrl, referer = data).document

                    val iframeSrc = extractIframeUrl(document)
                    if (iframeSrc == null) {
                        Log.w("KimcilOnly", "loadLinks: no iframe found in $playerUrl")
                        return@async
                    }
                    Log.d("KimcilOnly", "loadLinks: found iframe $iframeSrc")

                    val fullUrl = fixUrl(iframeSrc)
                    Log.d("KimcilOnly", "loadLinks: routing to extractor for $fullUrl")
                    when {
                        fullUrl.contains("byse") -> {
                            byseExtractor.getUrl(fullUrl, data, subtitleCallback, callback)
                        }
                        fullUrl.contains("playmogo") -> {
                            extractDoodLike(fullUrl, data, callback)
                        }
                        fullUrl.contains("pendek.my.id") -> {
                            extractPendekMyId(fullUrl, data, callback)
                        }
                        fullUrl.contains("doods") -> {
                            extractDoodLike(fullUrl, data, callback)
                            extractVidaraLike(fullUrl, data, callback)
                        }
                        fullUrl.contains("pecah") -> {
                            extractPecahLike(fullUrl, data, subtitleCallback, callback)
                        }
                        fullUrl.contains("gupload") -> {
                            guploadExtractor.getUrl(fullUrl, data, subtitleCallback, callback)
                        }
                        else -> {
                            loadExtractor(fullUrl, data, subtitleCallback, callback)
                            extractGeneric(fullUrl, data, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("KimcilOnly", "loadLinks error: ${e.message}", e)
                }
            })
        }

        allJobs.awaitAll()
        Log.d("KimcilOnly", "loadLinks: all jobs completed")
        return@coroutineScope true
    }

    private fun extractIframeUrl(document: org.jsoup.nodes.Document): String? {
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() && it != "about:blank" }
            if (src != null) return src
            val lazySrc = iframe.attr("data-lazy-src").takeIf { it.isNotBlank() && it != "about:blank" }
            if (lazySrc != null) return lazySrc
            val dataSrc = iframe.attr("data-src").takeIf { it.isNotBlank() && it != "about:blank" }
            if (dataSrc != null) return dataSrc
        }
        document.select("meta[itemprop=embedURL]").forEach { meta ->
            val url = meta.attr("content").takeIf { it.isNotBlank() }
            if (url != null) return url
        }
        return null
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
        } catch (e: Exception) {
            Log.e("KimcilOnly", "extractVidaraLike error: ${e.message}", e)
        }
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
                "$baseUrl/ajax/get_stream_link?id=$serverId&movie=$movieId&is_init=&captcha=&ref=",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url)
            ).parsedSafe<PecahResponse>() ?: return
            val link = response.data?.link ?: return
            when {
                link.contains("byse") -> byseExtractor.getUrl(link, referer, subtitleCallback, callback)
                link.contains("doods") -> { extractDoodLike(link, referer, callback); extractVidaraLike(link, referer, callback) }
                link.contains("playmogo") -> extractDoodLike(link, referer, callback)
                link.contains("pendek.my.id") -> extractPendekMyId(link, referer, callback)
                else -> { loadExtractor(link, referer, subtitleCallback, callback); extractGeneric(link, referer, callback) }
            }
        } catch (e: Exception) {
            Log.e("KimcilOnly", "extractPecahLike error: ${e.message}", e)
        }
    }

    private suspend fun extractGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, referer = referer).document
            val html = doc.html()
            Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""").findAll(html).forEach { match ->
                val videoUrl = match.value.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(name, "Direct", videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("KimcilOnly", "extractGeneric error: ${e.message}", e)
        }
    }

    private suspend fun extractDoodLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("KimcilOnly", "extractDoodLike: fetching $url")
            val document = app.get(url, referer = referer).document
            val scripts = document.select("script")
            Log.d("KimcilOnly", "extractDoodLike: found ${scripts.size} scripts")

            for (script in scripts) {
                val scriptContent = script.html()
                val passMd5Path = findPassMd5(scriptContent)
                if (passMd5Path != null) {
                    Log.d("KimcilOnly", "extractDoodLike: pass_md5 path = $passMd5Path")
                    val baseUrl = url.substringBefore("/e/").substringBefore("/d/")
                    val passMd5Url = "$baseUrl/pass_md5/$passMd5Path"
                    Log.d("KimcilOnly", "extractDoodLike: fetching $passMd5Url")
                    val response = app.get(
                        passMd5Url,
                        headers = mapOf("Referer" to url, "X-Requested-With" to "XMLHttpRequest")
                    ).text
                    Log.d("KimcilOnly", "extractDoodLike: pass_md5 response = ${response.take(120)}")
                    if (response.isNotBlank() && response.startsWith("http")) {
                        val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                        val token = passMd5Path.substringAfterLast("/")
                        val videoUrl = "$response$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"
                        Log.d("KimcilOnly", "extractDoodLike: videoUrl = ${videoUrl.take(120)}")
                        callback.invoke(
                            newExtractorLink(name, "DoodStream", videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    } else {
                        Log.w("KimcilOnly", "extractDoodLike: pass_md5 response empty or not http")
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

            Log.d("KimcilOnly", "extractDoodLike: no pass_md5 found, trying mp4 fallback")
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
            } else {
                Log.w("KimcilOnly", "extractDoodLike: no video URL found in $url")
            }
        } catch (e: Exception) {
            Log.e("KimcilOnly", "extractDoodLike error: ${e.message}", e)
        }
    }

    private fun findPassMd5(scriptContent: String): String? {
        val patterns = listOf(
            Regex("""['"]\s*/pass_md5/([^'"]+)['"]"""),
            Regex("""/pass_md5/([a-zA-Z0-9\-]+/[a-zA-Z0-9]+)"""),
            Regex("""pass_md5['"]\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""['"](/pass_md5/[^'"]+)['"]"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(scriptContent)
            if (match != null) {
                val path = match.groupValues[1].removePrefix("/")
                Log.d("KimcilOnly", "findPassMd5: matched pattern, path = $path")
                return path
            }
        }
        return null
    }

    private suspend fun extractPendekMyId(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("KimcilOnly", "extractPendekMyId: fetching $url")
            val document = app.get(url, referer = referer).document
            val html = document.html()

            val hexPattern = Regex("""const _0x1\s*=\s*['"]([a-fA-F0-9|]+)['"]""")
            val match = hexPattern.find(html)
            if (match == null) {
                Log.w("KimcilOnly", "extractPendekMyId: no hex encoded URL found")
                return
            }

            val hexString = match.groupValues[1]
            Log.d("KimcilOnly", "extractPendekMyId: found hex string, length=${hexString.length}")

            val decodedUrl = decodeHexString(hexString)
            if (decodedUrl.isNullOrBlank()) {
                Log.w("KimcilOnly", "extractPendekMyId: failed to decode hex string")
                return
            }

            Log.d("KimcilOnly", "extractPendekMyId: decoded URL = ${decodedUrl.take(100)}")

            callback.invoke(
                newExtractorLink(name, "VidSonic", decodedUrl, ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e("KimcilOnly", "extractPendekMyId error: ${e.message}", e)
        }
    }

    private fun decodeHexString(hex: String): String? {
        return try {
            val cleanHex = hex.replace("|", "")
            val sb = StringBuilder()
            for (i in cleanHex.indices step 2) {
                val charCode = cleanHex.substring(i, i + 2).toInt(16)
                sb.append(charCode.toChar())
            }
            sb.toString().reversed()
        } catch (e: Exception) {
            Log.e("KimcilOnly", "decodeHexString error: ${e.message}", e)
            null
        }
    }
}

open class GUploadExtractor : ExtractorApi() {
    override var name = "GUpload"
    override var mainUrl = "https://gupload.xyz"
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

    private fun xorDecode(data: String, key: String): String {
        val result = StringBuilder()
        for (i in data.indices) {
            val d = data[i].code
            val k = key[i % key.length].code
            result.append((d xor k).toChar())
        }
        return result.toString()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            Log.d("GUploadExtractor", "getUrl: url=$url")
            val baseUrl = getBaseUrl(url)
            val pathParts = url.removePrefix(baseUrl).removePrefix("/").split("/")
            Log.d("GUploadExtractor", "getUrl: pathParts=$pathParts")
            if (pathParts.size < 2) {
                Log.w("GUploadExtractor", "getUrl: pathParts too short, returning")
                return
            }
            val code = pathParts.last()
            Log.d("GUploadExtractor", "getUrl: code=$code")

            val embedUrl = "$baseUrl/data/e/$code"
            Log.d("GUploadExtractor", "getUrl: fetching embed $embedUrl")
            val document = app.get(embedUrl, referer = url).document

            val fullHtml = document.html()
            Log.d("GUploadExtractor", "getUrl: page html length=${fullHtml.length}")
            Log.d("GUploadExtractor", "getUrl: html preview=${fullHtml.take(500)}")

            // Try c50b1777~ pattern (old format)
            val oldPattern = Regex("c50b1777~[A-Za-z0-9+/=]+")
            val oldMatch = oldPattern.find(fullHtml)

            // Try _dp() pattern (new format) - extracts XOR-encoded data inside _dp('...') calls
            val dpPattern = Regex("""_dp\(['"]([A-Za-z0-9+/=~]+)['"]""")
            val dpMatches = dpPattern.findAll(fullHtml).toList()

            Log.d("GUploadExtractor", "getUrl: old pattern found=${oldMatch != null}, dp patterns found=${dpMatches.size}")

            if (oldMatch != null) {
                val obfuscated = oldMatch.value.removePrefix("c50b1777~")
                val decoded = xorDecode(obfuscated, "G7#kP!2qZxV9mRwL")
                val playerUrl = String(b64UrlDecode(decoded))
                Log.d("GUploadExtractor", "getUrl: [old] playerUrl=$playerUrl")
                fetchAndExtractPlayer(playerUrl, url, baseUrl, callback)
            } else if (dpMatches.isNotEmpty()) {
                // Decode each _dp() call and try to find a valid URL
                for (dpMatch in dpMatches) {
                    val obfuscated = dpMatch.groupValues[1]
                    val decoded = xorDecode(obfuscated, "G7#kP!2qZxV9mRwL")
                    val decodedStr = String(b64UrlDecode(decoded))
                    Log.d("GUploadExtractor", "getUrl: [dp] decoded=$decodedStr")
                    if (decodedStr.startsWith("http")) {
                        Log.d("GUploadExtractor", "getUrl: [dp] found valid URL: $decodedStr")
                        fetchAndExtractPlayer(decodedStr, url, baseUrl, callback)
                        return
                    }
                }
            }

            // Fallback: try direct m3u8 patterns in the page
            val pageUrlPatterns = Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""")
            val pageMatches = pageUrlPatterns.findAll(fullHtml).toList()
            Log.d("GUploadExtractor", "getUrl: found ${pageMatches.size} direct m3u8/mp4 matches in page")

            pageMatches.forEach { m ->
                val videoUrl = m.value.replace("\\/", "/")
                if (videoUrl.contains(".m3u8")) {
                    Log.d("GUploadExtractor", "getUrl: [page] m3u8=$videoUrl")
                    M3u8Helper.generateM3u8(name, videoUrl, baseUrl).forEach(callback)
                } else {
                    Log.d("GUploadExtractor", "getUrl: [page] mp4=$videoUrl")
                    callback.invoke(newExtractorLink(name, "GUpload", videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    })
                }
            }

            if (oldMatch == null && dpMatches.isEmpty() && pageMatches.isEmpty()) {
                Log.d("GUploadExtractor", "getUrl: no patterns found, trying hls fallback")
                val thumbnailUrl = Regex("""https?://gupload\.xyz/data/e/hls/[^"'\s]*/thumb/[^"'\s]+\.jpg""").find(fullHtml)?.value
                if (thumbnailUrl != null) {
                    val hlsUrl = thumbnailUrl.replace("/thumb/", "/master.m3u8")
                    Log.d("GUploadExtractor", "getUrl: hls fallback=$hlsUrl")
                    M3u8Helper.generateM3u8(name, hlsUrl, baseUrl).forEach(callback)
                } else {
                    Log.w("GUploadExtractor", "getUrl: no video URLs found at all")
                    Log.d("GUploadExtractor", "getUrl: full html=$fullHtml")
                }
            }
        } catch (e: Exception) {
            Log.e("GUploadExtractor", "getUrl error: ${e.message}", e)
        }
    }

    private fun getBaseUrl(url: String): String =
        runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)

    private suspend fun fetchAndExtractPlayer(
        playerUrl: String,
        refererUrl: String,
        baseUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("GUploadExtractor", "fetchAndExtractPlayer: fetching $playerUrl")
        val playerDocument = app.get(playerUrl).document
        val playerHtml = playerDocument.html()
        Log.d("GUploadExtractor", "fetchAndExtractPlayer: player html length=${playerHtml.length}")

        val m3u8Pattern = Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""")
        val matches = m3u8Pattern.findAll(playerHtml).toList()
        Log.d("GUploadExtractor", "fetchAndExtractPlayer: found ${matches.size} m3u8/mp4 matches")

        for (m in matches) {
            val videoUrl = m.value.replace("\\/", "/")
            if (videoUrl.contains(".m3u8")) {
                Log.d("GUploadExtractor", "fetchAndExtractPlayer: m3u8=$videoUrl")
                M3u8Helper.generateM3u8(name, videoUrl, baseUrl).forEach(callback)
            } else {
                Log.d("GUploadExtractor", "fetchAndExtractPlayer: mp4=$videoUrl")
                callback.invoke(newExtractorLink(name, "GUpload", videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = refererUrl
                    this.quality = Qualities.Unknown.value
                })
            }
        }

        if (matches.isEmpty()) {
            Log.w("GUploadExtractor", "fetchAndExtractPlayer: no m3u8/mp4 found in player page")
            Log.d("GUploadExtractor", "fetchAndExtractPlayer: player preview=${playerHtml.take(500)}")
        }
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
