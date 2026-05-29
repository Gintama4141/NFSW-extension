package com.kimcilonly

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class KimcilOnlyProvider : MainAPI() {

    override var name = "KimcilOnly"
    override var mainUrl = "https://kimcilonlyofc.my"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category/film-semi/" to "Film Semi",
        "$mainUrl/category/live-apk/" to "Indo 18+"
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

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = image
            }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        val elements = document.select("article.item, .gmr-item-modulepost")
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a, .entry-title a")
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
        // Player URLs: ?player=2, ?player=3, ?player=4
        val playerUrls = listOf(
            data,                          // Player 1 (default)
            "$data?player=2",              // Player 2
            "$data?player=3",              // Player 3
            "$data?player=4"               // Player 4
        )

        playerUrls.map { playerUrl ->
            async(Dispatchers.IO) {
                try {
                    val document = app.get(playerUrl, referer = data).document

                    // Extract iframe src from the page
                    val iframeSrc = document.select("iframe[src]").mapNotNull {
                        it.attr("src").takeIf { src -> src.isNotBlank() }
                    }.firstOrNull()

                    if (iframeSrc != null) {
                        val fullUrl = fixUrl(iframeSrc)
                        loadExtractor(fullUrl, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        }.awaitAll()

        return@coroutineScope true
    }
}
