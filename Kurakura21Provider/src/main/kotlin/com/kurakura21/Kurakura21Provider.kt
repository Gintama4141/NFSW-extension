package com.kurakura21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Kurakura21Provider : MainAPI() {

    override var name = "Kurakura21"
    override var mainUrl = "https://kurakura21.net"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/genre/bioskop/" to "Bioskop",
        "$mainUrl/genre/indo-18/" to "Indo 18+",
        "$mainUrl/genre/korea-18/" to "Korea 18+",
        "$mainUrl/genre/cina-18/" to "Cina 18+",
        "$mainUrl/genre/jav-sub-indo/" to "JAV",
        "$mainUrl/genre/barat-18/" to "Barat 18+"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val elements = document.select("article.item, #gmr-main-load article, .gmr-item-modulepost")

        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a, .entry-title a, h3 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")

            val img = element.selectFirst("img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            image = image?.replace(Regex("-\\d+x\\d+\\."), ".")

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = image
            }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        val elements = document.select("article.item, #gmr-main-load article, .gmr-item-modulepost")
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a, .entry-title a, h3 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")

            val img = element.selectFirst("img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            image = image?.replace(Regex("-\\d+x\\d+\\."), ".")

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.select(".entry-content p").joinToString("\n") { it.text() }.trim()
        val tags = document.select(".gmr-moviedata:contains(Genre:) a, .tags a").map { it.text() }

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
        val document = app.get(data).document

        // Get all iframes from the page
        val iframes = document.select("iframe[src], .gmr-embed-responsive iframe, .responsive-player iframe").mapNotNull {
            it.attr("src").takeIf { src -> src.isNotBlank() }?.let { src -> fixUrl(src) }
        }.distinct()

        iframes.map { iframeSrc ->
            async(Dispatchers.IO) {
                try {
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }.awaitAll()

        // Also get player tabs URLs (hash-based like #p2, #p3)
        val playerTabs = document.select("ul.muvipro-player-tabs li a")
            .mapNotNull { it.attr("href").takeIf { h -> h.isNotBlank() } }
            .filter { !it.contains("javascript:") }

        // For hash-based tabs, we need to fetch the main page with the hash
        // Since hash fragments aren't sent to server, we need to look for content loaded via JS
        // Try to get iframes from tab content divs
        val tabContents = document.select(".gmr-pagi-player, .tab-content")
        tabContents.forEach { tabContent ->
            val tabIframes = tabContent.select("iframe[src]").mapNotNull {
                it.attr("src").takeIf { s -> s.isNotBlank() }?.let { s -> fixUrl(s) }
            }
            tabIframes.forEach { iframeSrc ->
                async(Dispatchers.IO) {
                    try {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }
        }

        return@coroutineScope true
    }
}
