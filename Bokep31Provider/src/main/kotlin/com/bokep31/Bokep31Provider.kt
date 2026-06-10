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

        // Extract from data-lazy-src (WordPress Rocket) or src
        document.select("iframe").forEach { iframe ->
            val lazySrc = iframe.attr("data-lazy-src").takeIf { it.isNotBlank() && it != "about:blank" }
            val src = iframe.attr("src").takeIf { it.isNotBlank() && it != "about:blank" }
            val url = lazySrc ?: src
            if (url != null) iframes.add(fixUrl(url))
        }

        // Fallback: meta[itemprop=embedURL]
        if (iframes.isEmpty()) {
            document.select("meta[itemprop=embedURL]").forEach { meta ->
                val url = meta.attr("content").takeIf { it.isNotBlank() }
                if (url != null) iframes.add(fixUrl(url))
            }
        }

        iframes.distinct().map { iframeSrc ->
            async(Dispatchers.IO) {
                try {
                    if (iframeSrc.contains("playmogo") || iframeSrc.contains("pendek")) {
                        extractDoodLike(iframeSrc, data, callback)
                    } else {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e("Bokep31", "Error extracting $iframeSrc: ${e.message}")
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
            }

            // Fallback: try to find direct mp4 in page
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
        } catch (e: Exception) {
            Log.e("Bokep31", "Error in extractDoodLike: ${e.message}")
        }
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
