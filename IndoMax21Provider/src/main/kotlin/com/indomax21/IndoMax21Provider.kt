package com.indomax21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class IndoMax21Provider : MainAPI() {

    override var name = "IndoMax21"
    override var mainUrl = "https://homecookingrocks.com"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category/hentai/" to "Hentai",
        "$mainUrl/category/asia-m/" to "Asia",
        "$mainUrl/category/vivamax/" to "VivaMax",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/kelas-bintang/" to "Porn Star",
        "$mainUrl/category/semi-barat/" to "Western",
        "$mainUrl/category/bokep-indo/" to "Indonesia",
        "$mainUrl/category/bokep-vietnam/" to "Vietnam"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val items = app.get(url).document.select(ITEM_SELECTOR).mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get("$mainUrl/?s=$query").document
            .select(ITEM_SELECTOR)
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.select(".entry-content p").joinToString("\n") { it.text() }.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun:) a")?.text()?.toIntOrNull()
        val ratingStr = document.selectFirst(".gmr-meta-rating span[itemprop=ratingValue]")?.text()
        val tags = document.select(".gmr-moviedata:contains(Genre:) a").map { it.text() }

        val episodes = document.select(EPISODE_SELECTOR).mapNotNull { it.toEpisode() }
        val isSeries = episodes.isNotEmpty() || url.contains("/hentai/")

        return if (isSeries) {
            val finalEpisodes = episodes.ifEmpty {
                listOf(newEpisode(data = url) { name = "Episode 1"; episode = 1 })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(ratingStr)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(ratingStr)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val document = app.get(data).document

        val serverUrls = document.select(SERVER_TAB_SELECTOR)
            .mapNotNull { it.attr("href").takeIf(String::isNotBlank)?.let(::fixUrl) }
            .distinct()
            .ifEmpty { listOf(data) }
            .sortedBy { if (it == data) 0 else 1 }

        serverUrls.map { serverUrl ->
            async(Dispatchers.IO) {
                val serverDoc = if (serverUrl == data) document
                else app.get(serverUrl, referer = data).document

                serverDoc.select("iframe").mapNotNull { iframe ->
                    iframe.attr("src").takeIf(String::isNotBlank)?.let(::fixUrl)
                }.forEach { iframeSrc ->
                    extractIframe(iframeSrc, data, serverUrl, subtitleCallback, callback)
                }
            }
        }.awaitAll()

        true
    }

    private suspend fun extractIframe(
        iframeSrc: String,
        referer: String,
        serverUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            when {
                iframeSrc.contains("pyrox", ignoreCase = true) || iframeSrc.contains("embedpyrox", ignoreCase = true) ->
                    extractPyrox(iframeSrc, referer, callback)
                iframeSrc.contains("4meplayer", ignoreCase = true) ->
                    extract4MePlayer(iframeSrc, callback)
                iframeSrc.contains("imaxstreams", ignoreCase = true) ->
                    extractImaxStreams(iframeSrc, serverUrl, callback)
                else ->
                    loadExtractor(iframeSrc, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractPyrox(iframeSrc: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val iframeId = iframeSrc.split("/").last().substringBefore("?")
            val host = URI(iframeSrc).host
            val response = app.post(
                url = "https://$host/player/index.php?data=$iframeId&do=getVideo",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to iframeSrc),
                data = mapOf("hash" to iframeId, "r" to referer)
            ).text

            PYROX_URL_REGEX.findAll(response).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true) || videoUrl.contains(".txt")
                callback(
                    newExtractorLink(name, "Pyrox", videoUrl,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.referer = iframeSrc; this.quality = Qualities.Unknown.value }
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun extract4MePlayer(iframeSrc: String, callback: (ExtractorLink) -> Unit) {
        try {
            val videoId = iframeSrc.substringAfterLast("#")
            if (videoId.isEmpty() || videoId == iframeSrc) return
            val host = URI(iframeSrc).host

            for (apiPath in listOf("api/v1/video", "api/v1/info")) {
                try {
                    val hexResponse = app.get("https://$host/$apiPath?id=$videoId", referer = iframeSrc).text.trim()
                    if (hexResponse.isEmpty() || !hexResponse.all { it.isDigit() || (it in 'a'..'f') || (it in 'A'..'F') }) continue

                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(FOURME_KEY, "AES"), IvParameterSpec(FOURME_IV))
                    val decoded = hexResponse.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val decrypted = cipher.doFinal(decoded).toString(Charsets.UTF_8)

                    M3U8_IN_QUOTES.find(decrypted)?.groupValues?.get(1)?.let { match ->
                        val m3u8Url = match.replace("\\/", "/").let {
                            if (it.startsWith("/")) "https://$host$it" else it
                        }
                        callback(
                            newExtractorLink(name, "4MePlayer", m3u8Url, ExtractorLinkType.M3U8) {
                                this.referer = iframeSrc; this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractImaxStreams(iframeSrc: String, serverUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(iframeSrc, referer = serverUrl).text
            val unpacked = getAndUnpack(html)
            val text = unpacked ?: html

            M3U8_IN_QUOTES.find(text)?.let { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                callback(
                    newExtractorLink(name, "ImaxStreams", videoUrl, ExtractorLinkType.M3U8) {
                        this.referer = iframeSrc; this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // ---- Extensions ----

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val titleEl = selectFirst(".entry-title a, h2 a") ?: return null
        val title = titleEl.text()
        val link = titleEl.attr("href")
        if (title.isBlank() || link.isBlank()) return null

        val isSeries = selectFirst(".gmr-numbeps") != null || link.contains("/hentai/")
        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        val poster = selectFirst("img")?.extractPoster()

        return if (type == TvType.TvSeries)
            newTvSeriesSearchResponse(title, link, type) { this.posterUrl = poster }
        else
            newMovieSearchResponse(title, link, type) { this.posterUrl = poster }
    }

    private fun org.jsoup.nodes.Element.extractPoster(): String? {
        return attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.replace(SCALED_IMG_REGEX, ".")
    }

    private fun org.jsoup.nodes.Element.toEpisode(): Episode? {
        val epsUrl = attr("href").takeIf(String::isNotBlank) ?: return null
        val epsName = text().trim()
        return newEpisode(data = epsUrl) {
            name = epsName
            episode = EPISODE_NUM_REGEX.find(epsName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("\\d+").find(epsName)?.value?.toIntOrNull()
            season = SEASON_NUM_REGEX.find(epsName)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    companion object {
        private const val ITEM_SELECTOR = "#gmr-main-load article, .gmr-item-modulepost"
        private const val EPISODE_SELECTOR = ".gmr-listseries a, .gmr-eps-list a, .button-seasons a, ul.gmr-episodes li a"
        private const val SERVER_TAB_SELECTOR = ".muvipro-player-tabs a"
        private val FOURME_KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        private val FOURME_IV = ByteArray(16) { i -> if (i < 9) i.toByte() else 32.toByte() }
        private val PYROX_URL_REGEX = Regex("""(https:\\?\/\\?\/[^"]+\.(?:m3u8|mp4|txt)[^"]*)""")
        private val M3U8_IN_QUOTES = Regex(""""([^"]+\.m3u8[^"]*)"""")
        private val EPISODE_NUM_REGEX = Regex("(?:episode|eps|ep)\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val SEASON_NUM_REGEX = Regex("s(\\d+)", RegexOption.IGNORE_CASE)
        private val SCALED_IMG_REGEX = Regex("-\\d+x\\d+\\.")
    }
}
