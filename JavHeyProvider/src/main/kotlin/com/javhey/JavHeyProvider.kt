package com.javhey

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ── Shared Helpers ──────────────────────────────────────────────

private fun b64UrlDecode(s: String): ByteArray {
    val fixed = s.replace('-', '+').replace('_', '/')
    val pad = when (fixed.length % 4) {
        2 -> "=="
        3 -> "="
        else -> ""
    }
    return runCatching { Base64.decode(fixed + pad, Base64.DEFAULT) }.getOrElse { ByteArray(0) }
}

private fun getBaseUrl(url: String): String =
    runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)

// ── Main Provider ───────────────────────────────────────────────

class JavHeyProvider : MainAPI() {
    override var mainUrl = "https://javhey.com"
    override var name = "JavHey"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    private val byseExtractor by lazy { ByseSXLocal() }
    private val guploadExtractor by lazy { GUploadExtractor() }

    override val mainPage = mainPageOf(
        "$mainUrl/videos/paling-baru/page=" to "Latest Update",
        "$mainUrl/category/2/censored/page=" to "Category Censored",
        "$mainUrl/category/31/decensored/page=" to "Category Uncensored",
        "$mainUrl/videos/paling-dilihat/page=" to "Most Viewed",
        "$mainUrl/videos/top-rating/page=" to "Top Rating"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.removeSuffix("/page=")
        val url = if (page > 1) "$base/page=$page" else base
        val document = app.get(url, headers = headers).document

        val home = document.select("div.article_standard_view > article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchHeaders = headers + mapOf("Referer" to "$mainUrl/")
        val document = app.get("$mainUrl/search?s=$query", headers = searchHeaders).document
        return document.select("div.article_standard_view > article.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("div.item_content > h3 > a") ?: return null
        val title = titleElement.text().removePrefix("JAV Subtitle Indonesia - ").trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = selectFirst("div.item_header > a > img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("article.post header.post_header h1")?.text()
            ?.removePrefix("JAV Subtitle Indonesia - ")?.trim() ?: "Unknown Title"

        val poster = document.selectFirst("div.product div.images img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("p.video-description")?.text()
            ?.removePrefix("Description: ")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val metaDiv = document.select("div.product_meta")
        val tags = metaDiv.select("span:contains(Category) a, span:contains(Tag) a").map { it.text() }
        val actorList = metaDiv.select("span:contains(Actor) a").map { ActorData(Actor(it.text())) }

        val yearStr = metaDiv.select("span:contains(Release Day)").text()
        val yearInt = Regex("""\d{4}""").find(yearStr)?.value?.toIntOrNull()

        val recommended = document.select("div.article_standard_view > article.item").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actorList
            this.year = yearInt
            this.recommendations = recommended
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val document = app.get(data, headers = headers).document

        val rawLinks = document.select("[id=links]").mapNotNull {
            it.attr("value").takeIf { v -> v.isNotBlank() }
        }.flatMap { encodedValue ->
            runCatching {
                String(Base64.decode(encodedValue, Base64.DEFAULT))
                    .split(",,,")
                    .map { it.trim() }
                    .filter { it.startsWith("http") }
            }.getOrDefault(emptyList())
        }.toSet()

        rawLinks.map { url ->
            async(Dispatchers.IO) {
                runCatching {
                    when {
                        url.contains("streamwish.to") || url.contains("minochinos.com") || url.contains("terbit2.com") || url.contains("swdyu.com") || url.contains("hgplaycdn.com") -> {
                            val fixed = url
                                .replace("minochinos.com", "streamwish.to")
                                .replace("terbit2.com", "streamwish.to")
                                .replace("swdyu.com", "streamwish.to")
                                .replace("hgplaycdn.com", "streamwish.to")
                            loadExtractor(fixed, data, subtitleCallback, callback)
                        }
                        url.contains("byse") -> byseExtractor.getUrl(url, data, subtitleCallback, callback)
                        url.contains("gupload") -> guploadExtractor.getUrl(url, data, subtitleCallback, callback)
                        else -> loadExtractor(url, data, subtitleCallback, callback)
                    }
                }
            }
        }.awaitAll()

        true
    }
}

// ── Byse Extractor ──────────────────────────────────────────────

open class ByseSXLocal : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun getCodeFromUrl(url: String): String =
        runCatching { URI(url).path?.trimEnd('/')?.substringAfterLast('/') }.getOrNull() ?: ""

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = getCodeFromUrl(url)
        if (code.isEmpty()) return
        val refererUrl = getBaseUrl(url)

        val detailsUrl = "$refererUrl/api/videos/$code/embed/details"
        val details = tryParseJson<DetailsRoot>(app.get(detailsUrl).text) ?: return

        val embedFrameUrl = details.embedFrameUrl
        val embedCode = getCodeFromUrl(embedFrameUrl)
        val embedBase = getBaseUrl(embedFrameUrl)

        val playbackUrl = "$embedBase/api/videos/$embedCode/embed/playback"
        val playbackHeaders = mapOf(
            "accept" to "*/*",
            "referer" to embedFrameUrl,
            "x-embed-parent" to (referer ?: mainUrl)
        )

        val playback = tryParseJson<PlaybackRoot>(app.get(playbackUrl, headers = playbackHeaders).text)
            ?.playback ?: return

        runCatching {
            val keyParts = playback.keyParts
            if (keyParts.size < 2) return@runCatching
            val keyBytes = b64UrlDecode(keyParts[0]) + b64UrlDecode(keyParts[1])
            val ivBytes = b64UrlDecode(playback.iv)
            val cipherBytes = b64UrlDecode(playback.payload)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))

            val jsonStr = String(cipher.doFinal(cipherBytes), Charsets.UTF_8).removePrefix("\uFEFF")

            tryParseJson<PlaybackDecrypt>(jsonStr)?.sources?.firstOrNull()?.url?.let { streamUrl ->
                M3u8Helper.generateM3u8(name, streamUrl, refererUrl, headers = mapOf("Referer" to refererUrl))
                    .forEach(callback)
            }
        }
    }
}

// ── GUpload Extractor ───────────────────────────────────────────

open class GUploadExtractor : ExtractorApi() {
    override var name = "GUpload"
    override var mainUrl = "https://gupload.xyz"
    override val requiresReferer = true

    private fun xorDecode(data: String, key: String): String {
        val result = StringBuilder(data.length)
        for (i in data.indices) {
            result.append((data[i].code xor key[i % key.length].code).toChar())
        }
        return result.toString()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val baseUrl = getBaseUrl(url)
            val pathParts = url.removePrefix(baseUrl).removePrefix("/").split("/")
            if (pathParts.size < 2) return@runCatching
            val code = pathParts.last()

            val document = app.get("$baseUrl/data/e/$code", referer = url).document
            val fullHtml = document.html()

            val oldPattern = Regex("c50b1777~[A-Za-z0-9+/=]+")
            val oldMatch = oldPattern.find(fullHtml)

            val dpPattern = Regex("""_dp\(['"]([A-Za-z0-9+/=~]+)['"]""")
            val dpMatches = dpPattern.findAll(fullHtml).toList()

            when {
                oldMatch != null -> {
                    val obfuscated = oldMatch.value.removePrefix("c50b1777~")
                    val decoded = xorDecode(obfuscated, "G7#kP!2qZxV9mRwL")
                    fetchAndExtractPlayer(String(b64UrlDecode(decoded)), url, baseUrl, callback)
                }
                dpMatches.isNotEmpty() -> {
                    for (dpMatch in dpMatches) {
                        val decodedStr = String(b64UrlDecode(xorDecode(dpMatch.groupValues[1], "G7#kP!2qZxV9mRwL")))
                        if (decodedStr.startsWith("http")) {
                            fetchAndExtractPlayer(decodedStr, url, baseUrl, callback)
                            return@runCatching
                        }
                    }
                }
            }

            val pageUrlPattern = Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""")
            val pageMatches = pageUrlPattern.findAll(fullHtml).toList()

            for (m in pageMatches) {
                val videoUrl = m.value.replace("\\/", "/")
                if (videoUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, videoUrl, baseUrl).forEach(callback)
                } else {
                    callback(newExtractorLink(name, "GUpload", videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    })
                }
            }

            if (oldMatch == null && dpMatches.isEmpty() && pageMatches.isEmpty()) {
                val thumbMatch = Regex("""https?://gupload\.xyz/data/e/hls/[^"'\s]*/thumb/[^"'\s]+\.jpg""").find(fullHtml)
                if (thumbMatch != null) {
                    M3u8Helper.generateM3u8(name, thumbMatch.value.replace("/thumb/", "/master.m3u8"), baseUrl)
                        .forEach(callback)
                }
            }
        }
    }

    private suspend fun fetchAndExtractPlayer(
        playerUrl: String,
        refererUrl: String,
        baseUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val playerHtml = app.get(playerUrl).document.html()
        val pattern = Regex("""https?://[^"'\s]+\.(m3u8|mp4)[^"'\s]*""")

        for (m in pattern.findAll(playerHtml)) {
            val videoUrl = m.value.replace("\\/", "/")
            if (videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, videoUrl, baseUrl).forEach(callback)
            } else {
                callback(newExtractorLink(name, "GUpload", videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = refererUrl
                    this.quality = Qualities.Unknown.value
                })
            }
        }
    }
}

// ── DTOs ────────────────────────────────────────────────────────

data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String)
data class PlaybackRoot(@JsonProperty("playback") val playback: Playback)
data class Playback(@JsonProperty("iv") val iv: String, @JsonProperty("payload") val payload: String, @JsonProperty("key_parts") val keyParts: List<String>)
data class PlaybackDecrypt(@JsonProperty("sources") val sources: List<PlaybackDecryptSource>)
data class PlaybackDecryptSource(@JsonProperty("url") val url: String)
