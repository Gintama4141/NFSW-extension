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
        val document = app.get(data).document

        val rawServerUrls = document.select(".muvipro-player-tabs a, ul.muvipro-player-tabs li a")
            .mapNotNull { it.attr("href").takeIf { href -> href.isNotBlank() }?.let { u -> fixUrl(u) } }
            .distinct()
            .toMutableList()

        if (rawServerUrls.isEmpty()) {
            rawServerUrls.add(data)
        }

        val sortedUrls = rawServerUrls.sortedBy { if (it == data) 0 else 1 }

        sortedUrls.map { serverUrl ->
            async(Dispatchers.IO) {
                try {
                    val serverDoc = if (serverUrl == data) document else app.get(serverUrl, referer = data).document

                    val iframes = serverDoc.select("iframe[src], .gmr-embed-responsive iframe").mapNotNull {
                        it.attr("src").takeIf { src -> src.isNotBlank() }?.let { src -> fixUrl(src) }
                    }

                    iframes.forEach { iframeSrc ->
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        }.awaitAll()

        return@coroutineScope true
    }
}
