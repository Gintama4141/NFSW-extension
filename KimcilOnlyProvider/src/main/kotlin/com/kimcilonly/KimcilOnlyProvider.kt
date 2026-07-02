package com.kimcilonly

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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

class KimcilOnlyProvider : MainAPI() {

    override var name = "KimcilOnly"
    override var mainUrl = "https://tv.kimcilonly.de"
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
        val items = app.get(url).document.select(ITEM_SELECTOR).mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get("$mainUrl/?s=$query").document
            .select(ITEM_SELECTOR)
            .mapNotNull { it.toSearchResponse() }
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val titleElement = selectFirst("h2.entry-title a, .entry-title a") ?: return null
        val title = titleElement.text()
        val link = titleElement.attr("href")
        val img = selectFirst("img")
        var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        image = image?.replace(IMAGE_SIZE_REGEX, ".")
        return newMovieSearchResponse(title, link, TvType.NSFW) { this.posterUrl = image }
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
        val playerUrls = listOf(data, "$data?player=2", "$data?player=3", "$data?player=4")

        playerUrls.map { playerUrl ->
            async(Dispatchers.IO) {
                runCatching {
                    val document = app.get(playerUrl, referer = data).document
                    val iframeSrc = extractIframeUrl(document)

                    if (iframeSrc == null) return@async

                    val fullUrl = fixUrl(iframeSrc)
                    when {
                        fullUrl.contains("byse") -> {
                            byseExtractor.getUrl(fullUrl, data, subtitleCallback, callback)
                        }
                        fullUrl.contains("luluvid") || fullUrl.contains("luluvdo") || fullUrl.contains("lulustream") ||
                        fullUrl.contains("playmogo") || fullUrl.contains("doods") || fullUrl.contains("firestream") -> {
                            extractDoodLike(fullUrl, data, callback)
                            extractVidaraLike(fullUrl, data, callback)
                        }
                        fullUrl.contains("pendek.my.id") -> {
                            extractPendekMyId(fullUrl, data, callback)
                        }
                        fullUrl.contains("pecah") -> {
                            extractPecahLike(fullUrl, data, subtitleCallback, callback)
                        }
                        fullUrl.contains("gupload") -> {
                            guploadExtractor.getUrl(fullUrl, data, subtitleCallback, callback)
                        }
                        fullUrl.contains("streamcash") -> {
                            extractStreamCash(fullUrl, data, callback)
                        }
                        else -> {
                            loadExtractor(fullUrl, data, subtitleCallback, callback)
                            extractGeneric(fullUrl, data, callback)
                        }
                    }
                }.onFailure { e ->
                    Log.e(TAG, "loadLinks error for $playerUrl: ${e.message}", e)
                }
            }
        }.awaitAll()

        true
    }

    private fun extractIframeUrl(document: org.jsoup.nodes.Document): String? {
        for (iframe in document.select("iframe")) {
            for (attr in IFRAME_ATTRIBUTES) {
                iframe.attr(attr).takeIf { it.isNotBlank() && it != "about:blank" }?.let { return it }
            }
        }
        return document.select("meta[itemprop=embedURL]").firstOrNull()
            ?.attr("content")?.takeIf { it.isNotBlank() }
    }

    private suspend fun extractVidaraLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) = runCatching {
        val baseUrl = url.substringBefore("/e/")
        val filecode = url.substringAfterLast("/e/").substringBefore("?")
        if (filecode.isEmpty()) return@runCatching

        val json = app.post(
            "$baseUrl/api/stream",
            headers = mapOf(
                "Content-Type" to "application/json",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url
            ),
            requestBody = """{"filecode":"$filecode","device":"android"}"""
                .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        ).text
        val response = tryParseJson<VidaraStreamResponse>(json) ?: return@runCatching
        val streamUrl = response.streaming_url ?: return@runCatching
        M3u8Helper.generateM3u8(name, streamUrl, baseUrl, headers = mapOf("Referer" to baseUrl))
            .forEach(callback)
    }.onFailure { e ->
        Log.e(TAG, "extractVidaraLike error: ${e.message}", e)
    }

    private suspend fun extractPecahLike(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = runCatching {
        val baseUrl = url.substringBefore("/embed")
        val doc = app.get(url, referer = referer).document
        val movieId = doc.selectFirst("#embed-player")?.attr("data-movie-id") ?: return@runCatching
        val serverId = doc.selectFirst("a.server")?.attr("data-id") ?: return@runCatching

        val json = app.get(
            "$baseUrl/ajax/get_stream_link?id=$serverId&movie=$movieId&is_init=&captcha=&ref=",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url)
        ).text
        val response = tryParseJson<PecahResponse>(json) ?: return@runCatching
        val link = response.data?.link ?: return@runCatching

        when {
            link.contains("byse") -> byseExtractor.getUrl(link, referer, subtitleCallback, callback)
            link.contains("luluvid") || link.contains("luluvdo") || link.contains("lulustream") ||
            link.contains("doods") || link.contains("playmogo") || link.contains("firestream") -> {
                extractDoodLike(link, referer, callback)
                extractVidaraLike(link, referer, callback)
            }
            link.contains("pendek.my.id") -> extractPendekMyId(link, referer, callback)
            link.contains("streamcash") -> extractStreamCash(link, referer, callback)
            else -> {
                loadExtractor(link, referer, subtitleCallback, callback)
                extractGeneric(link, referer, callback)
            }
        }
    }.onFailure { e ->
        Log.e(TAG, "extractPecahLike error: ${e.message}", e)
    }

    private suspend fun extractGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) = runCatching {
        val html = app.get(url, referer = referer).document.html()
        M3U8_MP4_REGEX.findAll(html).forEach { match ->
            val videoUrl = match.value.replace("\\/", "/")
            callback.invoke(
                newExtractorLink(name, "Direct", videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }.onFailure { e ->
        Log.e(TAG, "extractGeneric error: ${e.message}", e)
    }

    private suspend fun extractStreamCash(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) = runCatching {
        val code = url.substringAfter("/embed/").substringBefore("?")
        if (code.isEmpty()) return@runCatching
        val m3u8Url = "https://cdn.streamcash.to/videos/$code/index.m3u8"
        M3u8Helper.generateM3u8(
            "StreamCash", m3u8Url, "https://streamcash.to/",
            headers = mapOf("Referer" to "https://streamcash.to/")
        ).forEach(callback)
    }.onFailure { e ->
        Log.e(TAG, "extractStreamCash error: ${e.message}", e)
    }

    private suspend fun extractDoodLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) = runCatching {
        val embedUrl = url
        val directUrl = url.replace("/e/", "/d/")

        // Strategy 1: packer decode from /d/ page (LuluVid/JWPlayer)
        val directDoc = app.get(directUrl, referer = referer).document
        val directHtml = directDoc.html()
        val decoded = directHtml.decodePacker()
        if (decoded != null && emitVideoUrl(decoded, directUrl, "Packer", callback)) return@runCatching

        // Strategy 2: pass_md5 from /e/ page (DoodStream/PlayMogo)
        val embedDoc = app.get(embedUrl, referer = referer).document
        if (tryPassMd5(embedDoc, embedUrl, callback)) return@runCatching

        // Strategy 3: dood?op=watch from /e/ scripts
        for (script in embedDoc.select("script")) {
            val doodMatch = DOOD_WATCH_REGEX.find(script.html())
            if (doodMatch != null) {
                val hash = doodMatch.groupValues[1]
                val token = doodMatch.groupValues[2]
                val baseUrl = embedUrl.substringBefore("/e/")
                val response = app.get(
                    "$baseUrl/dood?op=watch&hash=$hash&token=$token&embed=true",
                    headers = mapOf("Referer" to embedUrl, "X-Requested-With" to "XMLHttpRequest")
                ).text
                if (response.isNotBlank() && response.startsWith("http")) {
                    val videoUrl = "$response${randomSuffix()}?token=$token&expiry=${System.currentTimeMillis()}"
                    callback.invoke(
                        newExtractorLink(name, "DoodStream", videoUrl, ExtractorLinkType.VIDEO) {
                            this.referer = embedUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return@runCatching
                }
            }
        }

        // Strategy 4: direct URL in /d/ HTML
        if (emitVideoUrl(directHtml, directUrl, "Direct", callback)) return@runCatching

        // Strategy 5: direct MP4 regex in /e/ HTML
        val mp4Match = DOOD_MP4_REGEX.find(embedDoc.html())
        if (mp4Match != null) {
            val videoUrl = mp4Match.groupValues[1].replace("\\/", "/")
            callback.invoke(
                newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return@runCatching
        }

        // Strategy 6: hash HLS fallback — skip for pure DoodStream (serves MP4, not HLS)
        if (!url.containsDoodStream()) tryHashHls(decoded, directHtml, directUrl, callback)
    }.onFailure { e ->
        Log.e(TAG, "extractDoodLike error: ${e.message}", e)
    }

    private suspend fun tryPassMd5(doc: org.jsoup.nodes.Document, embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        for (script in doc.select("script")) {
            val path = script.html().findPassMd5() ?: continue
            val baseUrl = embedUrl.substringBefore("/e/")
            val response = app.get(
                "$baseUrl/pass_md5/$path",
                headers = mapOf("Referer" to embedUrl, "X-Requested-With" to "XMLHttpRequest")
            ).text
            if (response == "RELOAD") return false
            if (response.isNotBlank() && response.startsWith("http")) {
                val token = path.substringAfterLast("/")
                val videoUrl = "$response${randomSuffix()}?token=$token&expiry=${System.currentTimeMillis()}"
                callback.invoke(
                    newExtractorLink(name, "DoodStream", videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        }
        return false
    }

    private suspend fun tryHashHls(decoded: String?, html: String, fetchUrl: String, callback: (ExtractorLink) -> Unit) {
        val hashMatch = Regex(""""hash"\s*:\s*"([a-f0-9]{32,})"""").find(html)
        val fileId = fetchUrl.substringAfterLast("/d/").substringBefore("?")
        if (hashMatch == null || fileId.isEmpty()) return
        val dirMatch = Regex("""/hls2/(\d{2})/(\d+)/""").find(decoded ?: html)
        val dir1 = dirMatch?.groupValues?.getOrNull(1) ?: "03"
        val dir2 = dirMatch?.groupValues?.getOrNull(2) ?: "00000"
        val videoHash = "${fileId}_h"
        for (cdn in CDN_DOMAINS) {
            val hlsUrl = "https://$cdn/hls2/$dir1/$dir2/$videoHash/master.m3u8?e=28800&f=$fileId&i=0.3&sp=0"
            runCatching {
                M3u8Helper.generateM3u8(name, hlsUrl, fetchUrl, headers = mapOf("Referer" to fetchUrl)).forEach(callback)
            }.onSuccess { return }
        }
    }

    private suspend fun emitVideoUrl(source: String, referer: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(source)?.let { match ->
            val url = match.value.replace("\\/", "/")
            M3u8Helper.generateM3u8(name, url, referer, headers = mapOf("Referer" to referer)).forEach(callback)
            return true
        }
        Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").find(source)?.let { match ->
            val url = match.value.replace("\\/", "/")
            callback.invoke(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            })
            return true
        }
        return false
    }

    private fun String.decodePacker(): String? = runCatching {
        val match = PACKER_REGEX.find(this) ?: return@runCatching null
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
    }.getOrNull()

    private fun String.findPassMd5(): String? {
        for (pattern in PASS_MD5_PATTERNS) {
            pattern.find(this)?.let { match ->
                return match.groupValues[1].removePrefix("/")
            }
        }
        return null
    }

    private fun String.containsDoodStream(): Boolean =
        contains("playmogo") || contains("dood")

    private suspend fun extractPendekMyId(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) = runCatching {
        val html = app.get(url, referer = referer).document.html()
        val hexMatch = HEX_PATTERN.find(html) ?: return@runCatching
        val decodedUrl = decodeHexString(hexMatch.groupValues[1]) ?: return@runCatching

        callback.invoke(
            newExtractorLink(name, "VidSonic", decodedUrl, ExtractorLinkType.VIDEO) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }.onFailure { e ->
        Log.e(TAG, "extractPendekMyId error: ${e.message}", e)
    }

    private fun decodeHexString(hex: String): String? = runCatching {
        hex.replace("|", "")
            .chunked(2)
            .map { it.toInt(16).toChar() }
            .joinToString("")
            .reversed()
    }.getOrNull()

    companion object {
        private const val TAG = "KimcilOnly"
        private const val ITEM_SELECTOR = "article.item, .gmr-item-modulepost"
        private val IMAGE_SIZE_REGEX = Regex("-\\d+x\\d+\\.")
        private val M3U8_MP4_REGEX = Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""")
        private val DOOD_MP4_REGEX = Regex(""""(https?://[^"]+\.mp4[^"]*)"""")
        private val DOOD_WATCH_REGEX = Regex("""['"]\s*/dood\?op=watch[^'"]*hash=([^&'"]+)[^'"]*token=([^&'"]+)['"]""")
        private val HEX_PATTERN = Regex("""const _0x1\s*=\s*['"]([a-fA-F0-9|]+)['"]""")
        private val PASS_MD5_PATTERNS = listOf(
            Regex("""['"]\s*/pass_md5/([^'"]+)['"]"""),
            Regex("""/pass_md5/([a-zA-Z0-9\-]+/[a-zA-Z0-9]+)"""),
            Regex("""pass_md5['"]\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""['"](/pass_md5/[^'"]+)['"]"""),
        )
        private val PACKER_REGEX = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{[^}]*\}\('(.+?)',(\d+),(\d+),'([^']*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val CDN_DOMAINS = listOf(
            "iihbzqjhkqull.tnmr.org",
            "DrMtUew6NHFm.tnmr.org",
            "hw6ugf3856NN.tnmr.org",
            "hw.jmnl.xyz",
            "hw.cdnst1.xyz"
        )
        private val IFRAME_ATTRIBUTES = listOf("src", "data-lazy-src", "data-src")
        private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        private fun randomSuffix(): String = (1..10).map { ALPHANUM.random() }.joinToString("")
    }
}

open class GUploadExtractor : ExtractorApi() {
    override var name = "GUpload"
    override var mainUrl = "https://gupload.xyz"
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

    private fun xorDecode(data: String, key: String): String {
        val result = StringBuilder(data.length)
        for (i in data.indices) {
            result.append((data[i].code xor key[i % key.length].code).toChar())
        }
        return result.toString()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val baseUrl = getBaseUrl(url)
            val pathParts = url.removePrefix(baseUrl).removePrefix("/").split("/")
            if (pathParts.size < 2) return@runCatching
            val code = pathParts.last()

            val document = app.get("$baseUrl/data/e/$code", referer = url).document
            val fullHtml = document.html()

            val oldPattern = Regex("c50b1777~[A-Za-z0-9+/=]+")
            val oldMatch = oldPattern.find(fullHtml)

            val dpPattern = Regex("""_dp\(['"]([A-Za-z0-9+/=~]+)['"]""")
            val dpMatches = dpPattern.findAll(fullHtml).toList()

            if (oldMatch != null) {
                val obfuscated = oldMatch.value.removePrefix("c50b1777~")
                val decoded = xorDecode(obfuscated, "G7#kP!2qZxV9mRwL")
                val playerUrl = String(b64UrlDecode(decoded))
                fetchAndExtractPlayer(playerUrl, url, baseUrl, callback)
            } else if (dpMatches.isNotEmpty()) {
                for (dpMatch in dpMatches) {
                    val decodedStr = String(b64UrlDecode(xorDecode(dpMatch.groupValues[1], "G7#kP!2qZxV9mRwL")))
                    if (decodedStr.startsWith("http")) {
                        fetchAndExtractPlayer(decodedStr, url, baseUrl, callback)
                        return@runCatching
                    }
                }
            }

            val pageMatches = M3U8_MP4_REGEX.findAll(fullHtml).toList()
            for (m in pageMatches) {
                val videoUrl = m.value.replace("\\/", "/")
                if (videoUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, videoUrl, baseUrl).forEach(callback)
                } else {
                    callback.invoke(newExtractorLink(name, "GUpload", videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    })
                }
            }

            if (oldMatch == null && dpMatches.isEmpty() && pageMatches.isEmpty()) {
                val thumbnailUrl = THUMB_REGEX.find(fullHtml)?.value
                if (thumbnailUrl != null) {
                    val hlsUrl = thumbnailUrl.replace("/thumb/", "/master.m3u8")
                    M3u8Helper.generateM3u8(name, hlsUrl, baseUrl).forEach(callback)
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "getUrl error: ${e.message}", e)
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
        val playerHtml = app.get(playerUrl).document.html()
        for (m in M3U8_MP4_REGEX.findAll(playerHtml)) {
            val videoUrl = m.value.replace("\\/", "/")
            if (videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, videoUrl, baseUrl).forEach(callback)
            } else {
                callback.invoke(newExtractorLink(name, "GUpload", videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = refererUrl
                    this.quality = Qualities.Unknown.value
                })
            }
        }
    }

    companion object {
        private const val TAG = "GUploadExtractor"
        private val M3U8_MP4_REGEX = Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""")
        private val THUMB_REGEX = Regex("""https?://gupload\.xyz/data/e/hls/[^"'\s]*/thumb/[^"'\s]+\.jpg""")
    }
}

open class ByseSXLocal : ExtractorApi() {
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

            val detailsUrl = "$refererUrl/api/videos/$code/embed/details"
            val details = tryParseJson<DetailsRoot>(app.get(detailsUrl).text) ?: return@runCatching

            val embedFrameUrl = details.embedFrameUrl
            val embedBase = getBaseUrl(embedFrameUrl)
            val embedCode = getCodeFromUrl(embedFrameUrl)

            val playbackUrl = "$embedBase/api/videos/$embedCode/embed/playback"
            val playbackHeaders = mapOf(
                "accept" to "*/*",
                "referer" to embedFrameUrl,
                "x-embed-parent" to (referer ?: mainUrl)
            )

            var playback = tryParseJson<PlaybackRoot>(app.get(playbackUrl, headers = playbackHeaders).text)?.playback
            if (playback == null) {
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
            Log.e(TAG, "getUrl error: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "ByseSXLocal"
    }
}

// ── DTOs ────────────────────────────────────────────────────────

data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String)
data class PlaybackRoot(@JsonProperty("playback") val playback: Playback)
data class Playback(
    @JsonProperty("iv") val iv: String,
    @JsonProperty("payload") val payload: String,
    @JsonProperty("key_parts") val keyParts: List<String>
)
data class PlaybackDecrypt(@JsonProperty("sources") val sources: List<PlaybackDecryptSource>)
data class PlaybackDecryptSource(@JsonProperty("url") val url: String)
data class VidaraStreamResponse(val streaming_url: String? = null)
data class PecahResponse(val success: Boolean, val data: PecahData?)
data class PecahData(val link: String?)
