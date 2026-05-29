package com.bokepindo13

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class BokepIndo13Provider : MainAPI() {

    override var name = "BokepIndo13"
    override var mainUrl = "https://bokepindo13.center"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category/bokep-indo/" to "Bokep Indo"
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

        // Try to extract video from meta tag first (direct video URL)
        val embedUrl = document.selectFirst("meta[itemprop=embedURL]")?.attr("content")
        if (embedUrl != null && embedUrl.isNotBlank()) {
            async(Dispatchers.IO) {
                try {
                    // Try to extract video from bebasbokep.online
                    if (embedUrl.contains("bebasbokep")) {
                        extractBebasBokep(embedUrl, data, callback)
                    } else {
                        loadExtractor(fixUrl(embedUrl), data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }.awaitAll()
        }

        // Also try iframes
        val iframes = document.select("iframe[src], .responsive-player iframe, .video-player iframe").mapNotNull {
            it.attr("src").takeIf { src -> src.isNotBlank() }?.let { src -> fixUrl(src) }
        }.distinct()

        iframes.map { iframeSrc ->
            async(Dispatchers.IO) {
                try {
                    if (iframeSrc.contains("bebasbokep")) {
                        extractBebasBokep(iframeSrc, data, callback)
                    } else {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        }.awaitAll()

        return@coroutineScope true
    }

    private suspend fun extractBebasBokep(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url, referer = referer).document

            // Try to find video source in page
            val videoSrc = document.select("video source, video").mapNotNull {
                it.attr("src").takeIf { s -> s.isNotBlank() }
            }.firstOrNull()

            if (videoSrc != null) {
                val videoUrl = fixUrl(videoSrc)
                if (videoUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        videoUrl,
                        url,
                        headers = mapOf("Referer" to url)
                    ).forEach(callback)
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                return
            }

            // Try to find video URL in JavaScript
            val pageHtml = document.html()
            val m3u8Match = Regex(""""(https?://[^"]+\.m3u8[^"]*)"""").find(pageHtml)
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    url,
                    headers = mapOf("Referer" to url)
                ).forEach(callback)
                return
            }

            val mp4Match = Regex(""""(https?://[^"]+\.mp4[^"]*)"""").find(pageHtml)
            if (mp4Match != null) {
                val mp4Url = mp4Match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Try to find packed/obfuscated JavaScript
            val packedMatch = Regex("""eval\(function\(p,a,c,k,e,d\).+?</script>""", RegexOption.DOT_MATCHES_ALL).find(pageHtml)
            if (packedMatch != null) {
                val unpacked = getAndUnpack(packedMatch.value)
                val unpackedM3u8 = Regex(""""(https?://[^"]+\.m3u8[^"]*)"""").find(unpacked)
                if (unpackedM3u8 != null) {
                    val m3u8Url = unpackedM3u8.groupValues[1].replace("\\/", "/")
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        url,
                        headers = mapOf("Referer" to url)
                    ).forEach(callback)
                }
            }
        } catch (_: Exception) {}
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
