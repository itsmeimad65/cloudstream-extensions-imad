package com.freesideplus

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class FreeSidePlusExtractor : ExtractorApi() {
    override val name = "FreeSidePlus"
    override val mainUrl = "https://www.free-sideplus.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("Referer" to (referer ?: "https://www.free-sideplus.com/"))
        val resp = app.get(url, headers = headers)
        val iframeSrc = resp.document.selectFirst("iframe")?.attr("src") ?: return

        when {
            iframeSrc.contains("link.free-sideplus.com") -> {
                handleLinkServer(iframeSrc, url, subtitleCallback, callback)
            }
            iframeSrc.contains("cdn.free-sideplus.com") -> {
                handleCdnServer(iframeSrc, url, subtitleCallback, callback)
            }
            else -> {
                loadExtractor(iframeSrc, subtitleCallback, callback)
            }
        }
    }

    private suspend fun handleLinkServer(
        proxyUrl: String,
        parentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resp = app.get(proxyUrl, headers = mapOf("Referer" to parentUrl))
        val voeSrc = resp.document.selectFirst("iframe")?.attr("src") ?: return
        loadExtractor(voeSrc, subtitleCallback, callback)
    }

    private suspend fun handleCdnServer(
        cdnPage: String,
        parentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resp = app.get(cdnPage, headers = mapOf("Referer" to parentUrl))
        val html = resp.text

        val token = extractToken(html) ?: return
        val streamUrl = "https://cdn.free-sideplus.com/stream.php?v=$token"

        val streamResp = try {
            app.get(streamUrl, headers = mapOf("Referer" to cdnPage))
        } catch (_: Exception) {
            return
        }

        val videoUrl = streamResp.url
        if (videoUrl.isNotBlank() && videoUrl != streamUrl) {
            callback.invoke(
                newExtractorLink(
                    source = "FreeSidePlus CDN",
                    name = "FreeSidePlus",
                    url = videoUrl
                ) {
                    this.referer = cdnPage
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            val body = streamResp.text
            if (body.isNotBlank() && body.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "FreeSidePlus CDN",
                        name = "FreeSidePlus",
                        url = body.trim()
                    ) {
                        this.referer = cdnPage
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }

    private fun extractToken(html: String): String? {
        val tokenRegex = Regex("""token:\s*"([^"]+)"""")
        val match = tokenRegex.find(html)
        return match?.groupValues?.get(1)
    }
}
