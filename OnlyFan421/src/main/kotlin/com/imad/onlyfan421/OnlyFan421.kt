package com.imad.onlyfan421

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URL

class OnlyFan421 : MainAPI() {
    override var mainUrl = "https://rentry.org/OnlyFan421"
    override var name = "OnlyFan421"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        private val categories = listOf(
            "Latest OnlyFans Leaks" to "https://rentry.org/OnlyFan421",
            "Studio Releases" to "https://rentry.co/Allsex24"
        )
        private val knownProviders = listOf(
            "luluvdo" to "LuluVdo",
            "vidara" to "Vidara",
            "playmogo" to "DoodStream",
            "streamtape" to "StreamTape",
            "dood" to "DoodStream",
            "sbchill" to "SbChill",
        )
    }

    private fun parseItems(doc: Document): List<SearchResponse> {
        return doc.select("table.ntable td:has(a.external[href])").mapNotNull { cell ->
            val link = cell.selectFirst("a.external") ?: return@mapNotNull null
            val href = link.attr("abs:href")
            if (href.contains("clenchinfer.com")) return@mapNotNull null
            val title = link.ownText().trim()
            if (title.isEmpty()) return@mapNotNull null
            val img = link.selectFirst("img") ?: return@mapNotNull null
            val poster = img.attr("abs:src").takeIf { it.isNotBlank() } ?: ""
            val encoded = "$href|||$title|||$poster"
            newMovieSearchResponse(title, encoded, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeLists = categories.map { (name, url) ->
            val doc = app.get(url).document
            HomePageList(name, parseItems(doc), isHorizontalImages = true)
        }
        return newHomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|||")
        val realUrl = parts[0]
        val title = parts.getOrElse(1) { "OnlyFans Leak" }
        val poster = parts.getOrElse(2) { "" }.ifBlank { null }
        val tags = try {
            val host = URL(realUrl).host.removePrefix("www.")
            val known = knownProviders.firstOrNull { (pattern, _) -> host.contains(pattern) }?.second
            if (known != null) listOf(known)
            else listOf(host.substringBefore(".").split("-", "_").joinToString(" ") {
                it.replaceFirstChar { c -> c.uppercase() }
            })
        } catch (_: Exception) {
            emptyList()
        }
        return newMovieLoadResponse(title, url, TvType.NSFW, realUrl) {
            this.posterUrl = poster
            this.tags = tags.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (loadExtractor(data, mainUrl, subtitleCallback, callback)) return true
        return try {
            val doc = app.get(data).document
            doc.select("iframe[src]").any {
                loadExtractor(it.attr("abs:src"), subtitleCallback, callback)
            }
        } catch (_: Exception) { false }
    }
}
