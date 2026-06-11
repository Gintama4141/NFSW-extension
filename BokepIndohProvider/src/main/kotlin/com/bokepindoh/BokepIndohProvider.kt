package com.bokepindoh

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class BokepIndohProvider : MainAPI() {

    override var name = "BokepIndoh"
    override var mainUrl = "https://bokepindoh.party"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category/bokep-indo/" to "Bokep Indo",
        "$mainUrl/category/bokep-viral/" to "Bokep Viral",
        "$mainUrl/category/bokep-jav/" to "Bokep JAV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val elements = document.select("article.thumb-block, article.loop-video")

        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst("a")
            val title = titleElement?.attr("title") ?: element.selectFirst(".entry-header span, header.entry-header span")?.text() ?: return@mapNotNull null
            val link = titleElement?.attr("href") ?: return@mapNotNull null
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img.video-main-thumb, img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        val elements = document.select("article.thumb-block, article.loop-video")
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst("a")
            val title = titleElement?.attr("title") ?: element.selectFirst(".entry-header span, header.entry-header span")?.text() ?: return@mapNotNull null
            val link = titleElement?.attr("href") ?: return@mapNotNull null
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img.video-main-thumb, img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" | ")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".video-description p")?.text()

        val duration = document.selectFirst("meta[itemprop=duration]")?.attr("content")

        val tags = document.select(".tags-list a.label, .categories a, .video-tags a").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.duration = parseDurationISO(duration)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val document = app.get(data).document

        val iframes = mutableListOf<String>()

        document.select("iframe").forEach { iframe ->
            val lazySrc = iframe.attr("data-lazy-src").takeIf { it.isNotBlank() && it != "about:blank" }
            val src = iframe.attr("src").takeIf { it.isNotBlank() && it != "about:blank" }
            val url = lazySrc ?: src
            if (url != null) iframes.add(fixUrl(url))
        }

        if (iframes.isEmpty()) {
            document.select("meta[itemprop=embedURL]").forEach { meta ->
                val url = meta.attr("content").takeIf { it.isNotBlank() }
                if (url != null) iframes.add(fixUrl(url))
            }
        }

        Log.d("BokepIndoh", "Found ${iframes.size} iframes: $iframes")

        iframes.distinct().map { iframeSrc ->
            async(Dispatchers.IO) {
                try {
                    if (iframeSrc.contains("luluvid") || iframeSrc.contains("luluvdo") || iframeSrc.contains("lulustream") ||
                        iframeSrc.contains("playmogo") || iframeSrc.contains("pendek") || iframeSrc.contains("dood")) {
                        extractDoodLike(iframeSrc, data, callback)
                    } else {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e("BokepIndoh", "Error extracting $iframeSrc: ${e.message}")
                }
            }
        }.awaitAll()

        return@coroutineScope true
    }

    private suspend fun extractDoodLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fetchUrl = if (url.contains("/e/")) url.replace("/e/", "/d/") else url
            Log.d("BokepIndoh", "extractDoodLike: fetching $fetchUrl")
            val document = app.get(fetchUrl, referer = referer).document
            val scripts = document.select("script")
            Log.d("BokepIndoh", "extractDoodLike: found ${scripts.size} scripts")
            val pageHtml = document.html()

            Log.d("BokepIndoh", "extractDoodLike: trying packer decoder")
            val decodedScript = decodePackerScript(pageHtml)
            if (decodedScript != null) {
                Log.d("BokepIndoh", "extractDoodLike: packer decoded, length=${decodedScript.length}")
                val m3u8InDecoded = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(decodedScript)
                if (m3u8InDecoded != null) {
                    val videoUrl = m3u8InDecoded.value.replace("\\/", "/")
                    Log.d("BokepIndoh", "extractDoodLike: [packer] m3u8 = ${videoUrl.take(150)}")
                    M3u8Helper.generateM3u8(name, videoUrl, fetchUrl, headers = mapOf("Referer" to fetchUrl)).forEach(callback)
                    return
                }
                val mp4InDecoded = Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").find(decodedScript)
                if (mp4InDecoded != null) {
                    val videoUrl = mp4InDecoded.value.replace("\\/", "/")
                    Log.d("BokepIndoh", "extractDoodLike: [packer] mp4 = ${videoUrl.take(150)}")
                    callback.invoke(
                        newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                            this.referer = fetchUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            }

            for (script in scripts) {
                val scriptContent = script.html()
                val passMd5Path = findPassMd5(scriptContent)
                if (passMd5Path != null) {
                    Log.d("BokepIndoh", "extractDoodLike: pass_md5 path = $passMd5Path")
                    val baseUrl = fetchUrl.substringBefore("/e/").substringBefore("/d/")
                    val passMd5Url = "$baseUrl/pass_md5/$passMd5Path"
                    Log.d("BokepIndoh", "extractDoodLike: fetching $passMd5Url")
                    val response = app.get(
                        passMd5Url,
                        headers = mapOf("Referer" to fetchUrl, "X-Requested-With" to "XMLHttpRequest")
                    ).text
                    Log.d("BokepIndoh", "extractDoodLike: pass_md5 response = ${response.take(120)}")
                    if (response.isNotBlank() && response.startsWith("http")) {
                        val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                        val token = passMd5Path.substringAfterLast("/")
                        val videoUrl = "$response$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"
                        Log.d("BokepIndoh", "extractDoodLike: videoUrl = ${videoUrl.take(120)}")
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

            Log.d("BokepIndoh", "extractDoodLike: no pass_md5 found, trying m3u8/mp4 fallback")

            val m3u8Match = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(pageHtml)
            if (m3u8Match != null) {
                val videoUrl = m3u8Match.groupValues[1].replace("\\/", "/")
                Log.d("BokepIndoh", "extractDoodLike: m3u8 fallback = ${videoUrl.take(120)}")
                M3u8Helper.generateM3u8(name, videoUrl, fetchUrl, headers = mapOf("Referer" to fetchUrl)).forEach(callback)
                return
            }

            val mp4Match = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(pageHtml)
            if (mp4Match != null) {
                val videoUrl = mp4Match.groupValues[1].replace("\\/", "/")
                Log.d("BokepIndoh", "extractDoodLike: mp4 fallback = ${videoUrl.take(120)}")
                callback.invoke(
                    newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = fetchUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            Log.d("BokepIndoh", "extractDoodLike: no direct m3u8/mp4, trying hash-based HLS construction")
            val hashMatch = Regex(""""hash"\s*:\s*"([a-f0-9]{32,})"""").find(pageHtml)
            val fileId = fetchUrl.substringAfterLast("/d/").substringAfterLast("/e/").substringBefore("?")
            if (hashMatch != null && fileId.isNotEmpty()) {
                val hash = hashMatch.groupValues[1]
                Log.d("BokepIndoh", "extractDoodLike: hash=$hash, fileId=$fileId")
                val dirMatch = Regex("""/hls2/(\d{2})/(\d+)/""").find(decodedScript ?: pageHtml)
                val dir1 = dirMatch?.groupValues?.get(1) ?: "03"
                val dir2 = dirMatch?.groupValues?.get(2) ?: "00000"
                val videoHash = "${fileId}_h"
                val cdnDomains = listOf("hw6ugf3856NN.tnmr.org", "hw.jmnl.xyz", "hw.cdnst1.xyz")
                for (cdn in cdnDomains) {
                    val hlsUrl = "https://$cdn/hls2/$dir1/$dir2/$videoHash/master.m3u8?e=28800&f=$fileId&i=0.3&sp=0"
                    Log.d("BokepIndoh", "extractDoodLike: trying HLS $hlsUrl")
                    try {
                        M3u8Helper.generateM3u8(name, hlsUrl, fetchUrl, headers = mapOf("Referer" to fetchUrl)).forEach(callback)
                        return
                    } catch (_: Exception) {}
                }
            }

            Log.w("BokepIndoh", "extractDoodLike: no video URL found in $fetchUrl")
        } catch (e: Exception) {
            Log.e("BokepIndoh", "extractDoodLike error: ${e.message}", e)
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
            Log.d("BokepIndoh", "decodePackerScript: base=$a, count=$c, words=${k.size}")

            var result = p
            for (i in (c - 1) downTo 0) {
                if (i < k.size && k[i].isNotEmpty()) {
                    val index = i.toString(a)
                    result = result.replace(Regex("\\b${Regex.escape(index)}\\b"), k[i])
                }
            }
            Log.d("BokepIndoh", "decodePackerScript: decoded length=${result.length}")
            return result
        } catch (e: Exception) {
            Log.e("BokepIndoh", "decodePackerScript error: ${e.message}", e)
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
                Log.d("BokepIndoh", "findPassMd5: matched pattern, path = $path")
                return path
            }
        }
        return null
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
