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
    ): HomePageResponse {
        val homeLists = mutableListOf<HomePageList>()

        val latestJson = app.get("$apiBase/posts?per_page=30&_embed&orderby=date&order=desc").text
        val latestPosts = try { mapper.readValue(latestJson, object : TypeReference<List<WpPost>>() {}) } catch (_: Exception) { emptyList() }
        if (latestPosts.isNotEmpty()) {
            homeLists.add(HomePageList("Latest", latestPosts.mapNotNull { it.toSearchResponse() }))
        }

        val categoriesJson = app.get("$apiBase/categories?exclude=1&per_page=20&orderby=count&order=desc").text
        val categories = try { mapper.readValue(categoriesJson, object : TypeReference<List<WpCategory>>() {}) } catch (_: Exception) { emptyList() }

        categories.filter { it.count > 0 }.forEach { cat ->
            val postsJson = app.get("$apiBase/posts?categories=${cat.id}&per_page=15&_embed&orderby=date&order=desc").text
            val posts = try { mapper.readValue(postsJson, object : TypeReference<List<WpPost>>() {}) } catch (_: Exception) { emptyList() }
            if (posts.isNotEmpty()) {
                homeLists.add(HomePageList(cat.name, posts.mapNotNull { it.toSearchResponse() }))
            }
        }

        return newHomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val postsJson = app.get("$apiBase/posts?search=$encodedQuery&_embed&per_page=50&orderby=relevance").text
        val posts = try { mapper.readValue(postsJson, object : TypeReference<List<WpPost>>() {}) } catch (_: Exception) { emptyList() }
        return posts.mapNotNull { it.toSearchResponse() }
    }

    private fun WpPost.toSearchResponse(): SearchResponse? {
        val title = title?.rendered?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val posterUrl = _embedded?.featuredMedia?.firstOrNull()?.sourceUrl
        return newMovieSearchResponse(title, link, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterVertical = false
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = try {
            URL(url).path.removeSuffix("/").substringAfterLast("/")
        } catch (_: Exception) {
            url.removePrefix("$mainUrl/").removeSuffix("/").substringBefore("/")
        }
        val postsJson = app.get("$apiBase/posts?slug=$slug&_embed").text
        val posts = try { mapper.readValue(postsJson, object : TypeReference<List<WpPost>>() {}) } catch (_: Exception) { emptyList() }
        val post = posts.firstOrNull() ?: throw Exception("Post not found")

        val title = post.title?.rendered?.trim() ?: "Unknown"
        val excerptHtml = post.excerpt?.rendered ?: ""
        val plot = Jsoup.parse(excerptHtml).text().trim().takeIf { it.isNotBlank() }
        val posterUrl = post._embedded?.featuredMedia?.firstOrNull()?.sourceUrl
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
