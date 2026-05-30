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
        "$mainUrl/genre/indo-18/" to "Indo",
        "$mainUrl/genre/korea-18/" to "Korea",
        "$mainUrl/genre/barat-18/" to "Barat",
        "$mainUrl/genre/cina-18/" to "Cina",
        "$mainUrl/genre/bioskop/" to "Film Semi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url).document

        val elements = document.select("article.item-infinite")

        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            val link = titleElement?.attr("href") ?: return@mapNotNull null
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img.wp-post-image")
            val image = img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        val elements = document.select("article.item-infinite")
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2.entry-title a")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            val link = titleElement?.attr("href") ?: return@mapNotNull null
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img.wp-post-image")
            val image = img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            newMovieSearchResponse(title, fixUrl(link), TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" | KURAKURA21")?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" | ")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("meta[name=description]")?.attr("content")

        val tags = document.select(".gmr-movie-on a[rel=category-tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }

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

        val postId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id") ?: run {
            document.selectFirst("[data-id]")?.attr("data-id")
        }

        if (postId != null) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

            for (tabNum in 1..4) {
                try {
                    val tabName = "p$tabNum"
                    val response = app.post(
                        ajaxUrl,
                        requestBody = "action=muvipro_player_content&tab=$tabName&post_id=$postId"
                            .toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
                    ).document

                    val iframeSrc = response.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                    if (iframeSrc != null) {
                        val iframeUrl = fixUrl(iframeSrc)
                        async(Dispatchers.IO) {
                            try {
                                loadExtractor(iframeUrl, data, subtitleCallback, callback)
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return@coroutineScope true
    }
}
