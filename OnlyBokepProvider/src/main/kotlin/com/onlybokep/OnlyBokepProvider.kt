package com.onlybokep

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class OnlyBokepProvider : MainAPI() {

    override var name = "OnlyBokep"
    override var mainUrl = "https://onlybokep.com"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category/indonesia/" to "Bokep Indo",
        "$mainUrl/category/bokep-jilbab/" to "Bokep Jilbab"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val elements = document.select(".thumb-block")

        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-header .title, .entry-header a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img.lazy, img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() }
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

        val elements = document.select(".thumb-block")
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-header .title, .entry-header a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img.lazy, img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title, h1.entry-title, .video-infos h1")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" - OnlyBokep")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".video-description p")?.text()

        val tags = document.select(".video-tags a, .tag-item a").map { it.text() }.distinct()

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

        val iframes = document.select("iframe[src]").mapNotNull {
            it.attr("src").takeIf { src -> src.isNotBlank() }
        }

        iframes.map { iframeSrc ->
            async(Dispatchers.IO) {
                try {
                    loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }.awaitAll()

        return@coroutineScope true
    }
}
