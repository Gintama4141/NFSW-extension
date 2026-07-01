package com.bokep31

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Bokep31Provider : MainAPI() {

    override var name = "Bokep31"
    override var mainUrl = "https://bokep31.store"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category/bokep-indo/" to "Bokep Indo",
        "$mainUrl/category/javindo/" to "JAV",
        "$mainUrl/category/asian/" to "Asian"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val items = app.get(url).document.select(ITEM_SELECTOR).mapNotNull { it.parseSearchItem() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get("$mainUrl/?s=$query").document
            .select(ITEM_SELECTOR)
            .mapNotNull { it.parseSearchItem() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" | ")?.trim()
            ?: return null

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            plot = document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst(".video-description p")?.text()
            tags = document.select(".tags-list a.label, .categories a, .video-tags a").map { it.text().trim() }
            duration = document.selectFirst("meta[itemprop=duration]")?.attr("content")?.parseDurationISO()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val document = app.get(data).document

        val iframes = extractIframes(document).ifEmpty {
            document.select("meta[itemprop=embedURL]").mapNotNull { meta ->
                meta.attr("content").takeIf { it.isNotBlank() }
            }
        }.map { fixUrl(it) }

        Log.d(TAG, "Found ${iframes.size} iframes: $iframes")

        iframes.distinct().map { src ->
            async(Dispatchers.IO) {
                try {
                    if (src.isDoodLike()) extractDoodLike(src, data, callback)
                    else loadExtractor(src, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting $src: ${e.message}")
                }
            }
        }.awaitAll()

        true
    }

    // ---- Shared parsing ----

    private fun org.jsoup.nodes.Element.parseSearchItem(): SearchResponse? {
        val titleEl = selectFirst("a") ?: return null
        val title = titleEl.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst(".entry-header span, header.entry-header span")?.text()
            ?: return null
        val link = titleEl.attr("href").takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
            posterUrl = extractPoster()
        }
    }

    private fun org.jsoup.nodes.Element.extractPoster(): String? {
        attr("data-main-thumb").takeIf { it.isNotBlank() }?.let { return it }
        return selectFirst("img.video-main-thumb, img")?.let { img ->
            img.attr("data-lazy-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
    }

    // ---- Doodstream-like extractor ----

    private suspend fun extractDoodLike(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val embedUrl = url
            val directUrl = url.replace("/e/", "/d/")
            Log.d(TAG, "extractDoodLike: embed=$embedUrl direct=$directUrl")

            // Strategy 1: packer decode from /d/ page (LuluVid/JWPlayer)
            val directDoc = app.get(directUrl, referer = referer).document
            val directHtml = directDoc.html()
            val decoded = directHtml.decodePacker()
            if (decoded != null && emitVideoUrl(decoded, directUrl, "Packer", callback)) return

            // Strategy 2: pass_md5 from /e/ page (DoodStream/PlayMogo)
            val embedDoc = app.get(embedUrl, referer = referer).document
            if (tryPassMd5(embedDoc, embedUrl, callback)) return

            // Strategy 3: direct URL in /d/ HTML
            if (emitVideoUrl(directHtml, directUrl, "Direct", callback)) return

            // Strategy 4: hash HLS — skip for DoodStream (serves MP4, not HLS)
            if (!url.containsDoodStream()) tryHashHls(decoded, directHtml, directUrl, callback)
        } catch (e: Exception) {
            Log.e(TAG, "extractDoodLike: ${e.message}", e)
        }
    }

    private suspend fun tryPassMd5(doc: org.jsoup.nodes.Document, embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        for (script in doc.select("script")) {
            val path = script.html().findPassMd5() ?: continue
            Log.d(TAG, "tryPassMd5: path = $path")
            val baseUrl = embedUrl.substringBefore("/e/")
            val response = app.get(
                "$baseUrl/pass_md5/$path",
                headers = mapOf(
                    "Referer" to embedUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
            Log.d(TAG, "tryPassMd5: response = ${response.take(120)}")
            if (response == "RELOAD") {
                Log.w(TAG, "tryPassMd5: server requested RELOAD (Turnstile), falling back")
                return false
            }
            if (response.isNotBlank() && response.startsWith("http")) {
                val token = path.substringAfterLast("/")
                val suffix = (1..10).map { ALPHANUM.random() }.joinToString("")
                val videoUrl = "$response$suffix?token=$token&expiry=${System.currentTimeMillis()}"
                callback(newExtractorLink(name, "DoodStream", videoUrl, ExtractorLinkType.VIDEO) {
                    referer = embedUrl
                    quality = Qualities.Unknown.value
                })
                return true
            }
        }
        return false
    }

    private suspend fun tryHashHls(decoded: String?, html: String, fetchUrl: String, callback: (ExtractorLink) -> Unit) {
        if (fetchUrl.containsDoodStream()) return
        val hashMatch = Regex(""""hash"\s*:\s*"([a-f0-9]{32,})"""").find(html)
        val fileId = fetchUrl.substringAfterLast("/d/").substringBefore("?")
        if (hashMatch == null || fileId.isEmpty()) {
            Log.w(TAG, "tryHashHls: no hash or fileId found")
            return
        }
        val dirMatch = Regex("""/hls2/(\d{2})/(\d+)/""").find(decoded ?: html)
        val dir1 = dirMatch?.groupValues?.getOrNull(1) ?: "03"
        val dir2 = dirMatch?.groupValues?.getOrNull(2) ?: "00000"
        val videoHash = "${fileId}_h"

        for (cdn in CDN_DOMAINS) {
            val hlsUrl = "https://$cdn/hls2/$dir1/$dir2/$videoHash/master.m3u8?e=28800&f=$fileId&i=0.3&sp=0"
            Log.d(TAG, "tryHashHls: trying $hlsUrl")
            try {
                M3u8Helper.generateM3u8(name, hlsUrl, fetchUrl, headers = mapOf("Referer" to fetchUrl)).forEach(callback)
                return
            } catch (_: Exception) {}
        }
    }

    private suspend fun emitVideoUrl(source: String, referer: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(source)?.let { match ->
            val url = match.value.replace("\\/", "/")
            Log.d(TAG, "emitVideoUrl [$label] m3u8 = ${url.take(150)}")
            M3u8Helper.generateM3u8(name, url, referer, headers = mapOf("Referer" to referer)).forEach(callback)
            return true
        }
        Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").find(source)?.let { match ->
            val url = match.value.replace("\\/", "/")
            Log.d(TAG, "emitVideoUrl [$label] mp4 = ${url.take(150)}")
            callback(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = referer
                quality = Qualities.Unknown.value
            })
            return true
        }
        return false
    }

    // ---- Helpers ----

    private fun extractIframes(document: org.jsoup.nodes.Document): List<String> {
        return document.select("iframe").mapNotNull { iframe ->
            iframe.attr("data-lazy-src").takeIf { it.isNotBlank() && it != "about:blank" }
                ?: iframe.attr("src").takeIf { it.isNotBlank() && it != "about:blank" }
        }
    }

    private fun String.decodePacker(): String? {
        try {
            val match = PACKER_REGEX.find(this) ?: return null
            val p = match.groupValues[1]
            val a = match.groupValues[2].toIntOrNull() ?: return null
            val c = match.groupValues[3].toIntOrNull() ?: return null
            val k = match.groupValues[4].split('|')
            Log.d(TAG, "decodePacker: base=$a, count=$c, words=${k.size}")

            var result = p
            for (i in (c - 1) downTo 0) {
                if (i < k.size && k[i].isNotEmpty()) {
                    result = result.replace(Regex("\\b${Regex.escape(i.toString(a))}\\b"), k[i])
                }
            }
            Log.d(TAG, "decodePacker: decoded length=${result.length}")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "decodePacker error: ${e.message}", e)
            return null
        }
    }

    private fun String.findPassMd5(): String? {
        for (pattern in PASS_MD5_PATTERNS) {
            pattern.find(this)?.let { match ->
                val path = match.groupValues[1].removePrefix("/")
                Log.d(TAG, "findPassMd5: matched, path = $path")
                return path
            }
        }
        return null
    }

    private fun String.parseDurationISO(): Int? {
        if (isBlank()) return null
        val match = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(this) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        val seconds = match.groupValues[3].toIntOrNull() ?: 0
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun String.isDoodLike(): Boolean = contains("playmogo") || contains("pendek") || contains("dood") ||
        contains("luluvid") || contains("luluvdo") || contains("lulustream")

    private fun String.containsDoodStream(): Boolean = contains("playmogo") || contains("dood")

    companion object {
        private const val TAG = "Bokep31"
        private const val ITEM_SELECTOR = "article.thumb-block, article.loop-video"
        private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
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
        private val CDN_DOMAINS = listOf(
            "iihbzqjhkqull.tnmr.org",
            "DrMtUew6NHFm.tnmr.org",
            "hw6ugf3856NN.tnmr.org",
            "hw.jmnl.xyz",
            "hw.cdnst1.xyz"
        )
    }
}
