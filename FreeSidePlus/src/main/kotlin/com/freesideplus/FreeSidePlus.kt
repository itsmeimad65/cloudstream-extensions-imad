package com.freesideplus

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URL

class FreeSidePlus : MainAPI() {
    override var mainUrl = "https://www.free-sideplus.com"
    override var name = "FreeSidePlus"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiBase = "$mainUrl/wp-json/wp/v2"

    companion object {
        private val postCache = mutableMapOf<String, Pair<Long, List<WpPost>>>()
        private val posterCache = mutableMapOf<String, String>() // post id -> poster URL
        private val srCache = mutableMapOf<String, Pair<Long, SearchResponse?>>() // post link -> search response
        private const val LIST_TTL = 5 * 60 * 1000L
        private const val POST_TTL = 10 * 60 * 1000L
        private const val SR_TTL = 10 * 60 * 1000L
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpCategory(
        val id: Long,
        val name: String,
        val slug: String,
        val count: Int
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpPost(
        val id: Long,
        val slug: String,
        val link: String,
        val title: Rendered? = null,
        val excerpt: Rendered? = null,
        val content: Rendered? = null,
        val date: String? = null,
        @JsonProperty("featured_media") val featuredMedia: Long = 0,
        val categories: List<Long>? = null,
        val tags: List<Long>? = null,
        val _embedded: Embedded? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Rendered(val rendered: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Embedded(
        @JsonProperty("wp:featuredmedia") val featuredMedia: List<FeaturedMedia>? = null,
        @JsonProperty("wp:term") val terms: List<List<Term>>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FeaturedMedia(
        @JsonProperty("source_url") val sourceUrl: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Term(
        val name: String? = null,
        val slug: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpMedia(
        val id: Long,
        @JsonProperty("source_url") val sourceUrl: String? = null
    )

    private suspend fun getCachedPosts(url: String, ttl: Long = LIST_TTL): List<WpPost> {
        val now = System.currentTimeMillis()
        synchronized(postCache) {
            postCache[url]?.let { (expiry, posts) ->
                if (now < expiry) return posts
            }
        }
        val json = app.get(url).text
        val posts = try { mapper.readValue(json, object : TypeReference<List<WpPost>>() {}) } catch (_: Exception) { emptyList<WpPost>() }
        synchronized(postCache) { postCache[url] = now + ttl to posts }
        return posts
    }

    private suspend fun getCachedMedia(mediaIds: Set<Long>): Map<Long, String> {
        if (mediaIds.isEmpty()) return emptyMap()
        val ids = mediaIds.filterNot { posterCache.containsKey(it.toString()) }
        if (ids.isNotEmpty()) {
            try {
                val json = app.get("$apiBase/media?include=${ids.joinToString(",")}&per_page=100&_fields=id,source_url").text
                val mediaList = try { mapper.readValue(json, object : TypeReference<List<WpMedia>>() {}) } catch (_: Exception) { emptyList<WpMedia>() }
                mediaList.forEach { if (it.sourceUrl != null) posterCache[it.id.toString()] = it.sourceUrl }
            } catch (_: Exception) { }
        }
        return mediaIds.mapNotNull { id ->
            posterCache[id.toString()]?.let { id to it }
        }.toMap()
    }

    private suspend fun getPosterUrl(post: WpPost): String? {
        if (post.featuredMedia == 0L) return null
        val fromEmbed = post._embedded?.featuredMedia?.firstOrNull()?.sourceUrl
        if (fromEmbed != null) return fromEmbed
        val cached = posterCache[post.featuredMedia.toString()]
        if (cached != null) return cached
        val fromOg = try {
            val doc = app.get(post.link).document
            doc.selectFirst("meta[property=og:image]")?.attr("content")
        } catch (_: Exception) { null }
        if (fromOg != null) posterCache[post.featuredMedia.toString()] = fromOg
        return fromOg
    }

    private suspend fun WpPost.toSearchResponseFromPost(): SearchResponse? {
        val linkKey = link
        synchronized(srCache) {
            srCache[linkKey]?.let { (expiry, sr) ->
                if (System.currentTimeMillis() < expiry) return sr
            }
        }
        val title = Jsoup.parse(title?.rendered?.trim() ?: "").text().takeIf { it.isNotBlank() } ?: return null
        val posterUrl = getPosterUrl(this)
        val sr = newMovieSearchResponse(title, link, TvType.Movie) { this.posterUrl = posterUrl }
        synchronized(srCache) { srCache[linkKey] = System.currentTimeMillis() + SR_TTL to sr }
        return sr
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = coroutineScope {
        val homeLists = mutableListOf<HomePageList>()

        val latestDeferred = async {
            getCachedPosts("$apiBase/posts?per_page=30&orderby=date&order=desc")
        }

        val categoryOrder = listOf(
            41L to "Sidecast",
            43L to "Side+ Saturdays",
            42L to "BTS",
            44L to "Inside",
            53L to "Inside Out",
            32L to "1v100",
            1L to "Uncategorized",
        )

        val categoryDeferreds = categoryOrder.map { (id, name) ->
            async { name to getCachedPosts("$apiBase/posts?categories=$id&per_page=15&orderby=date&order=desc") }
        }

        val latestPosts = latestDeferred.await()
        val allResults = categoryDeferreds.map { it.await() }

        val allMediaIds = (latestPosts + allResults.flatMap { it.second })
            .mapNotNull { it.featuredMedia.takeIf { id -> id != 0L } }.toSet()

        getCachedMedia(allMediaIds)

        if (latestPosts.isNotEmpty()) {
            val items = latestPosts.map { async { it.toSearchResponseFromPost() } }.awaitAll().filterNotNull()
            homeLists.add(HomePageList("Latest", items, isHorizontalImages = true))
        }

        for ((name, posts) in allResults) {
            if (posts.isNotEmpty()) {
                val items = posts.map { async { it.toSearchResponseFromPost() } }.awaitAll().filterNotNull()
                homeLists.add(HomePageList(name, items, isHorizontalImages = true))
            }
        }

        newHomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val posts = getCachedPosts("$apiBase/posts?search=$encodedQuery&_embed&per_page=50&orderby=relevance")
        val mediaIds = posts.mapNotNull { it.featuredMedia.takeIf { id -> id != 0L } }.toSet()
        getCachedMedia(mediaIds)
        posts.map { async { it.toSearchResponseFromPost() } }.awaitAll().filterNotNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = try {
            URL(url).path.removeSuffix("/").substringAfterLast("/")
        } catch (_: Exception) {
            url.removePrefix("$mainUrl/").removeSuffix("/").substringBefore("/")
        }
        val posts = getCachedPosts("$apiBase/posts?slug=$slug&_embed", POST_TTL)
        val post = posts.firstOrNull() ?: throw Exception("Post not found")

        val title = Jsoup.parse(post.title?.rendered?.trim() ?: "").text().takeIf { it.isNotBlank() } ?: "Unknown"
        val excerptHtml = post.excerpt?.rendered ?: ""
        val plot = Jsoup.parse(excerptHtml).text().trim().takeIf { it.isNotBlank() }
        val posterUrl = getPosterUrl(post)
        val tags = post._embedded?.terms?.flatten()?.mapNotNull { it.name }?.takeIf { it.isNotEmpty() }
        val year = post.date?.substringBefore("-")?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val tabs = doc.select("div.justabutton-tab[data-payload]")

        for (tab in tabs) {
            val payload = tab.attr("data-payload")
            if (payload.isBlank()) continue
            val decoded = try {
                String(java.util.Base64.getDecoder().decode(payload))
            } catch (_: Exception) {
                continue
            }
            if (decoded.startsWith("http")) {
                loadExtractor(decoded, subtitleCallback, callback)
            }
        }
        return true
    }

}
