package com.avtub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AvtubProvider : MainAPI() {

    override var name = "Avtub"
    override var mainUrl = "https://avtub.wiki"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category/bokep-indo/" to "Indo 18+",
        "$mainUrl/category/jav-sub-indo/" to "JAV Sub Indo",
        "$mainUrl/category/jav-uncensored/" to "JAV Uncensored"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val elements = document.select("a[href*=\"/video/\"]")

        val home = elements.mapNotNull { element ->
            val href = element.attr("href")
            if (!href.contains("/video/")) return@mapNotNull null

            val title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
            val link = fixUrl(href)

            val img = element.selectFirst("img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = image
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/search/?keyword=$query"
        val document = app.get(searchUrl).document

        val elements = document.select("a[href*=\"/video/\"]")
        return elements.mapNotNull { element ->
            val href = element.attr("href")
            if (!href.contains("/video/")) return@mapNotNull null

            val title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
            val link = fixUrl(href)

            val img = element.selectFirst("img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = image
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" - AVTub")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("meta[name=description]")?.attr("content")

        val tags = document.select("a[href*=\"/category/\"]").mapNotNull {
            it.text().takeIf { text -> text.isNotBlank() }
        }.distinct()

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
