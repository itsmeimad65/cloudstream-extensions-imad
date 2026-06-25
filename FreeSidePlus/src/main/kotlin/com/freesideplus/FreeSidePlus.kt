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

    private val cache = mutableMapOf<String, Pair<Long, Any>>()
    private val LIST_CACHE_TTL = 5 * 60 * 1000L
    private val POST_CACHE_TTL = 10 * 60 * 1000L

    @Suppress("UNCHECKED_CAST")
    private suspend fun fetchPosts(url: String, ttl: Long = LIST_CACHE_TTL): List<WpPost> {
        val now = System.currentTimeMillis()
        cache[url]?.let { (expiry, data) ->
            if (now < expiry) return data as List<WpPost>
        }
        val json = app.get(url).text
        val posts = try { mapper.readValue(json, object : TypeReference<List<WpPost>>() {}) } catch (_: Exception) { emptyList<WpPost>() }
        cache[url] = now + ttl to posts
        return posts
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = coroutineScope {
        val homeLists = mutableListOf<HomePageList>()

        val latestDeferred = async {
            fetchPosts("$apiBase/posts?per_page=30&_embed&orderby=date&order=desc")
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
            async { name to fetchPosts("$apiBase/posts?categories=$id&per_page=15&_embed&orderby=date&order=desc") }
        }

        val latestPosts = latestDeferred.await()
        if (latestPosts.isNotEmpty()) {
            val items = latestPosts.map { async { it.toSearchResponse() } }.awaitAll().filterNotNull()
            homeLists.add(HomePageList("Latest", items, isHorizontalImages = true))
        }

        for (d in categoryDeferreds) {
            val (name, posts) = d.await()
            if (posts.isNotEmpty()) {
                val items = posts.map { async { it.toSearchResponse() } }.awaitAll().filterNotNull()
                homeLists.add(HomePageList(name, items, isHorizontalImages = true))
            }
        }

        newHomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        fetchPosts("$apiBase/posts?search=$encodedQuery&_embed&per_page=50&orderby=relevance").map { async { it.toSearchResponse() } }.awaitAll().filterNotNull()
    }

    private suspend fun WpPost.toSearchResponse(): SearchResponse? {
        val title = Jsoup.parse(title?.rendered?.trim() ?: "").text().takeIf { it.isNotBlank() } ?: return null
        var posterUrl = _embedded?.featuredMedia?.firstOrNull()?.sourceUrl
        if (posterUrl == null && featuredMedia != 0L) {
            posterUrl = try {
                val doc = app.get(link).document
                doc.selectFirst("meta[property=og:image]")?.attr("content")
            } catch (_: Exception) { null }
        }
        return newMovieSearchResponse(title, link, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = try {
            URL(url).path.removeSuffix("/").substringAfterLast("/")
        } catch (_: Exception) {
            url.removePrefix("$mainUrl/").removeSuffix("/").substringBefore("/")
        }
        val posts = fetchPosts("$apiBase/posts?slug=$slug&_embed", POST_CACHE_TTL)
        val post = posts.firstOrNull() ?: throw Exception("Post not found")

        val title = Jsoup.parse(post.title?.rendered?.trim() ?: "").text().takeIf { it.isNotBlank() } ?: "Unknown"
        val excerptHtml = post.excerpt?.rendered ?: ""
        val plot = Jsoup.parse(excerptHtml).text().trim().takeIf { it.isNotBlank() }
        var posterUrl = post._embedded?.featuredMedia?.firstOrNull()?.sourceUrl
        if (posterUrl == null && post.featuredMedia != 0L) {
            posterUrl = try {
                val doc = app.get(post.link).document
                doc.selectFirst("meta[property=og:image]")?.attr("content")
            } catch (_: Exception) { null }
        }
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
