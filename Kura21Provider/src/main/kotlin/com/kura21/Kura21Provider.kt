package com.kura21

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Kura21Provider : MainAPI() {

    override var name = "Kura21"
    override var mainUrl = "https://kurakura21.net"
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        Log.d("Kura21", "getMainPage: fetching $url")
        val document = app.get(url).document

        val elements = document.select("article.item-infinite")

        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a")
            val title = titleElement?.text() ?: element.selectFirst(".entry-title")?.text() ?: return@mapNotNull null
            val link = element.selectFirst(".gmr-watch-movie a")?.attr("href")
                ?: titleElement?.attr("href")
                ?: return@mapNotNull null
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst(".content-thumbnail img")
            val image = img?.attr("src")?.takeIf { it.isNotBlank() }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
        Log.d("Kura21", "getMainPage: found ${home.size} items")
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        Log.d("Kura21", "search: fetching $searchUrl")
        val document = app.get(searchUrl).document

        val elements = document.select("article.item-infinite")
        Log.d("Kura21", "search: found ${elements.size} items")

        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = element.selectFirst(".gmr-watch-movie a")?.attr("href")
                ?: titleElement?.attr("href")
                ?: return@mapNotNull null
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst(".content-thumbnail img")
            val image = img?.attr("src")?.takeIf { it.isNotBlank() }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Kura21", "load: fetching $url")
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" | ")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".entry-content p")?.text()

        val tags = document.select(".tags-list a.label, .categories a, .video-tags a").map { it.text().trim() }

        val postId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id")

        return newMovieLoadResponse(title, url, TvType.NSFW, "${url}#p2") {
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
        Log.d("Kura21", "loadLinks: fetching $data")
        val (pageUrl, tab) = if (data.contains("#")) {
            val parts = data.split("#", limit = 2)
            parts[0] to parts[1]
        } else {
            data to "p2"
        }
        Log.d("Kura21", "loadLinks: pageUrl=$pageUrl, tab=$tab")
        val document = app.get(pageUrl).document

        val postId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id")
        Log.d("Kura21", "loadLinks: postId=$postId")

        if (postId.isNullOrBlank()) {
            Log.w("Kura21", "loadLinks: no postId found")
            return@coroutineScope true
        }

        // Try AJAX fetch for player content
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        Log.d("Kura21", "loadLinks: posting to $ajaxUrl")

        try {
            val response = app.post(
                ajaxUrl,
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to tab,
                    "post_id" to postId
                ),
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            Log.d("Kura21", "loadLinks: AJAX response length=${response.length}")
            Log.d("Kura21", "loadLinks: AJAX response preview=${response.take(500)}")

            // Parse iframe from AJAX response (case-insensitive)
            val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(response)
            val iframeSrc = iframeMatch?.groupValues?.get(1)
            Log.d("Kura21", "loadLinks: iframeSrc=$iframeSrc")

            if (iframeSrc != null) {
                val fixedIframe = fixUrl(iframeSrc)
                Log.d("Kura21", "loadLinks: routing iframe: $fixedIframe")
                routeAndExtract(fixedIframe, data, callback)
                return@coroutineScope true
            }

            // Try to find direct HLS URL in response
            val hlsMatch = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(response)
            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.value.replace("\\/", "/")
                Log.d("Kura21", "loadLinks: found HLS in response: $hlsUrl")
                M3u8Helper.generateM3u8(name, hlsUrl, data).forEach(callback)
                return@coroutineScope true
            }

        } catch (e: Exception) {
            Log.e("Kura21", "loadLinks: AJAX error: ${e.message}", e)
        }

        // Fallback: try to find any iframe in page
        val iframes = mutableListOf<String>()
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() && it != "about:blank" }
            if (src != null) iframes.add(fixUrl(src))
        }

        if (iframes.isEmpty()) {
            document.select("meta[itemprop=embedURL]").forEach { meta ->
                val content = meta.attr("content").takeIf { it.isNotBlank() }
                if (content != null) iframes.add(fixUrl(content))
            }
        }

        Log.d("Kura21", "loadLinks: found ${iframes.size} iframes: $iframes")

        iframes.distinct().map { iframeSrc ->
            async(Dispatchers.IO) {
                routeAndExtract(iframeSrc, data, callback)
            }
        }.awaitAll()

        return@coroutineScope true
    }

    private suspend fun routeAndExtract(iframeSrc: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            Log.d("Kura21", "routeAndExtract: routing $iframeSrc")
            when {
                iframeSrc.contains("playmogo") || iframeSrc.contains("pendek") || iframeSrc.contains("dood") ||
                iframeSrc.contains("luluvdo") || iframeSrc.contains("lulustream") || iframeSrc.contains("luluvid") -> {
                    extractDoodLike(iframeSrc, referer, callback)
                }
                iframeSrc.contains("terbit2") -> {
                    extractTerbit2(iframeSrc, referer, callback)
                }
                else -> {
                    loadExtractor(iframeSrc, referer, {}, callback)
                    extractGeneric(iframeSrc, referer, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("Kura21", "routeAndExtract error: ${e.message}", e)
        }
    }

    private suspend fun extractGeneric(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(url, referer = referer).document
            val html = doc.html()
            Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""").findAll(html).forEach { match ->
                val videoUrl = match.value.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(name, "Generic", videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractDoodLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fetchUrl = if (url.contains("/e/")) url.replace("/e/", "/d/") else url
            Log.d("Kura21", "extractDoodLike: fetching $fetchUrl")
            val document = app.get(fetchUrl, referer = referer).document
            val scripts = document.select("script")
            Log.d("Kura21", "extractDoodLike: found ${scripts.size} scripts")
            val pageHtml = document.html()

            // Try Dean Edwards packer decoder
            Log.d("Kura21", "extractDoodLike: trying packer decoder")
            val decodedScript = decodePackerScript(pageHtml)
            if (decodedScript != null) {
                Log.d("Kura21", "extractDoodLike: packer decoded, length=${decodedScript.length}")
                val m3u8InDecoded = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(decodedScript)
                if (m3u8InDecoded != null) {
                    val videoUrl = m3u8InDecoded.value.replace("\\/", "/")
                    Log.d("Kura21", "extractDoodLike: [packer] m3u8 = ${videoUrl.take(150)}")
                    M3u8Helper.generateM3u8(name, videoUrl, fetchUrl, headers = mapOf("Referer" to fetchUrl)).forEach(callback)
                    return
                }
            }

            // Try pass_md5 extraction
            for (script in scripts) {
                val scriptContent = script.html()
                val passMd5Path = findPassMd5(scriptContent)
                if (passMd5Path != null) {
                    Log.d("Kura21", "extractDoodLike: pass_md5 path = $passMd5Path")
                    val baseUrl = fetchUrl.substringBefore("/e/").substringBefore("/d/")
                    val passMd5Url = "$baseUrl/pass_md5/$passMd5Path"
                    Log.d("Kura21", "extractDoodLike: fetching $passMd5Url")
                    val response = app.get(
                        passMd5Url,
                        headers = mapOf("Referer" to fetchUrl, "X-Requested-With" to "XMLHttpRequest")
                    ).text
                    Log.d("Kura21", "extractDoodLike: pass_md5 response = ${response.take(120)}")
                    if (response.isNotBlank() && response.startsWith("http")) {
                        val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                        val token = passMd5Path.substringAfterLast("/")
                        val videoUrl = "$response$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"
                        Log.d("Kura21", "extractDoodLike: videoUrl = ${videoUrl.take(120)}")
                        callback.invoke(
                            newExtractorLink(name, "DoodStream", videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = fetchUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                }
            }

            // Try direct m3u8/mp4 in page HTML
            val m3u8Match = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(pageHtml)
            if (m3u8Match != null) {
                val videoUrl = m3u8Match.groupValues[1].replace("\\/", "/")
                Log.d("Kura21", "extractDoodLike: m3u8 fallback = ${videoUrl.take(120)}")
                M3u8Helper.generateM3u8(name, videoUrl, fetchUrl, headers = mapOf("Referer" to fetchUrl)).forEach(callback)
                return
            }

            // Try hash-based HLS construction
            Log.d("Kura21", "extractDoodLike: trying hash-based HLS construction")
            val hashMatch = Regex(""""hash"\s*:\s*"([a-f0-9]{32,})"""").find(pageHtml)
            val fileId = fetchUrl.substringAfterLast("/e/").substringAfterLast("/d/").substringBefore("?")
            if (hashMatch != null && fileId.isNotEmpty()) {
                val hash = hashMatch.groupValues[1]
                Log.d("Kura21", "extractDoodLike: hash=$hash, fileId=$fileId")
                val dirMatch = Regex("""/hls2/(\d{2})/(\d+)/""").find(decodedScript ?: pageHtml)
                val dir1 = dirMatch?.groupValues?.get(1) ?: "01"
                val dir2 = dirMatch?.groupValues?.get(2) ?: "00000"
                val videoHash = "${fileId}_x"
                val cdnDomains = listOf(
                    "edge2-waw-sprintcdn.r66nv9ed.com",
                    "hw6ugf3856NN.tnmr.org",
                    "hw.jmnl.xyz",
                    "hw.cdnst1.xyz"
                )
                for (cdn in cdnDomains) {
                    val hlsUrl = "https://$cdn/hls2/$dir1/$dir2/$videoHash/master.m3u8?e=28800&f=$fileId&sp=5500&p=0"
                    Log.d("Kura21", "extractDoodLike: trying HLS $hlsUrl")
                    try {
                        M3u8Helper.generateM3u8(name, hlsUrl, fetchUrl, headers = mapOf("Referer" to fetchUrl)).forEach(callback)
                        return
                    } catch (_: Exception) {}
                }
            }

            Log.w("Kura21", "extractDoodLike: no video URL found in $fetchUrl")
        } catch (e: Exception) {
            Log.e("Kura21", "extractDoodLike error: ${e.message}", e)
        }
    }

    private suspend fun extractTerbit2(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Kura21", "extractTerbit2: fetching $url")
            val document = app.get(url, referer = referer).document
            val pageHtml = document.html()

            // Decode packed script (Dean Edwards packer)
            val decodedScript = decodePackerScript(pageHtml)
            val contentToSearch = decodedScript ?: pageHtml

            Log.d("Kura21", "extractTerbit2: searching for video URLs in ${contentToSearch.length} chars")

            // Find all m3u8 URLs
            val m3u8Patterns = listOf(
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
                Regex("""["']([^"']*\.m3u8[^"']*)["']""")
            )

            for (pattern in m3u8Patterns) {
                pattern.findAll(contentToSearch).forEach { match ->
                    var videoUrl = match.groupValues.getOrNull(1) ?: match.value
                    videoUrl = videoUrl.replace("\\/", "/").trim()
                    if (videoUrl.startsWith("http") && !videoUrl.contains("eval(")) {
                        Log.d("Kura21", "extractTerbit2: found m3u8 = ${videoUrl.take(150)}")
                        M3u8Helper.generateM3u8(name, videoUrl, url, headers = mapOf("Referer" to url)).forEach(callback)
                    }
                }
            }

            // Find MP4 URLs
            val mp4Pattern = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
            mp4Pattern.findAll(contentToSearch).forEach { match ->
                var videoUrl = match.groupValues[1].replace("\\/", "/").trim()
                if (videoUrl.startsWith("http")) {
                    Log.d("Kura21", "extractTerbit2: found mp4 = ${videoUrl.take(150)}")
                    callback.invoke(
                        newExtractorLink(name, "Terbit2", videoUrl, ExtractorLinkType.VIDEO) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }

            // If still nothing, try generic extraction on the iframe page
            if (decodedScript == null) {
                Log.d("Kura21", "extractTerbit2: trying generic extraction on iframe page")
                extractGeneric(url, referer, callback)
            }

        } catch (e: Exception) {
            Log.e("Kura21", "extractTerbit2 error: ${e.message}", e)
        }
    }

    private fun decodePackerScript(html: String): String? {
        try {
            val packerRegex = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{[^}]*\}\('(.+?)',(\d+),(\d+),'([^']*?)'\.split\('\|'\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = packerRegex.find(html) ?: return null
            val p = match.groupValues[1]
            val a = match.groupValues[2].toIntOrNull() ?: return null
            val c = match.groupValues[3].toIntOrNull() ?: return null
            val k = match.groupValues[4].split('|')
            Log.d("Kura21", "decodePackerScript: base=$a, count=$c, words=${k.size}")

            var result = p
            for (i in (c - 1) downTo 0) {
                if (i < k.size && k[i].isNotEmpty()) {
                    val index = i.toString(a)
                    result = result.replace(Regex("\\b${Regex.escape(index)}\\b"), k[i])
                }
            }
            Log.d("Kura21", "decodePackerScript: decoded length=${result.length}")
            return result
        } catch (e: Exception) {
            Log.e("Kura21", "decodePackerScript error: ${e.message}", e)
            return null
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
                Log.d("Kura21", "findPassMd5: matched pattern, path = $path")
                return path
            }
        }
        return null
    }
}
