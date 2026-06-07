package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.CinemaCityScraper
import it.dogior.hadEnough.StreamITALogger
import org.json.JSONArray
import org.json.JSONObject

class CinemaCityExtractor : ExtractorApi() {
    override val name = "CinemaCity"
    override val mainUrl = "https://cinemacity.cc"
    override val requiresReferer = true

    private val cfKiller = CloudflareKiller()

    companion object {
        private const val TAG = "CinemaCityExtractor"
        private const val WORKER_URL = "https://broad-mouse-85c7.appbeta870.workers.dev"
        private val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        )
    }

    private suspend fun fetchViaWorker(url: String): String? {
        val path = try {
            val u = java.net.URL(url)
            u.path + if (u.query != null) "?${u.query}" else ""
        } catch (_: Exception) { url }
        val workerUrl = "${WORKER_URL}${path}"
        StreamITALogger.log(TAG, "fetchViaWorker → $workerUrl (da url=$url)")
        return try {
            val start = System.currentTimeMillis()
            val text = app.get(workerUrl, headers = headers).text
            val elapsed = System.currentTimeMillis() - start
            StreamITALogger.log(TAG, "fetchViaWorker OK (${text.length} bytes, ${elapsed}ms) per $workerUrl")
            if (text.length < 10) {
                StreamITALogger.log(TAG, "fetchViaWorker: risposta troppo corta (${text.length} bytes)")
                null
            } else text
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "fetchViaWorker fallito per $workerUrl: ${e.message}")
            null
        }
    }

    private fun isBlockedResponse(text: String): Boolean {
        return text.length < 500 ||
            text.contains("Just a moment", ignoreCase = true) ||
            (text.contains("admin", ignoreCase = true) && text.contains("Unlimited"))
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val imdbId = url
            val season = referer?.substringAfter("season=")?.substringBefore("&")?.toIntOrNull()
            val episode = referer?.substringAfter("episode=")?.substringBefore("&")?.toIntOrNull()
            val isTvSeries = season != null

            StreamITALogger.log(TAG, "Cerco CinemaCity per IMDb: $imdbId, S${season}E${episode}")

            val pageUrl = CinemaCityScraper.resolveViaSitemap(imdbId, isTvSeries) ?: run {
                StreamITALogger.log(TAG, "Nessun match sitemap per $imdbId")
                return
            }

            StreamITALogger.log(TAG, "Pagina trovata: $pageUrl")

            // Worker primario per pagina contenuto
            var html = fetchViaWorker(pageUrl)
            if (html != null && !isBlockedResponse(html)) {
                StreamITALogger.log(TAG, "Pagina via worker")
            } else {
                StreamITALogger.log(TAG, "Worker bloccato, fallback CFK...")
                html = app.get(pageUrl, headers = headers, interceptor = cfKiller).text
                if (isBlockedResponse(html)) {
                    StreamITALogger.log(TAG, "Anche CFK bloccato")
                    return
                }
            }

            // Link diretti
            val directLinks = extractDownloadLinks(html)
            if (directLinks.isNotEmpty()) {
                StreamITALogger.log(TAG, "Trovati ${directLinks.size} link diretti")
                var selectedUrl: String? = null
                for (link in directLinks) {
                    if (link.second.contains("ita") || link.second.contains("italian") || link.second.contains("italiano")) {
                        selectedUrl = link.first; break
                    }
                }
                if (selectedUrl == null) {
                    for (link in directLinks) {
                        if (!link.second.contains("eng") && !link.second.contains("sub")) {
                            selectedUrl = link.first; break
                        }
                    }
                }
                if (selectedUrl == null) selectedUrl = directLinks.first().first

                val streamUrl = resolveUrl(pageUrl, selectedUrl)
                StreamITALogger.log(TAG, "Link diretto: $streamUrl")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "CinemaCity",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer" to pageUrl,
                            "Origin" to mainUrl
                        )
                    }
                )
                return
            }

            // atob
            StreamITALogger.log(TAG, "Nessun link diretto, provo atob...")
            val atobUrl = extractStreamFromAtob(html, season, episode)
            if (atobUrl != null) {
                StreamITALogger.log(TAG, "URL da atob: $atobUrl")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = if (isTvSeries) "CinemaCity S${season}E${episode}" else "CinemaCity",
                        url = atobUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer" to pageUrl,
                            "Origin" to mainUrl
                        )
                    }
                )
                return
            }

            StreamITALogger.log(TAG, "Nessun link trovato")
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "Errore CinemaCity: ${e.message}")
        }
    }

    private fun extractDownloadLinks(html: String): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>()
        val regex = Regex(
            """<a\s[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        for (match in regex.findAll(html)) {
            val href = match.groupValues[1].trim()
            val text = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            if (!Regex("""\.(mp4|m3u8|mkv|avi|mov|webm)([?#].*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(href)) continue
            if (href.length < 10) continue
            links.add(href to text.lowercase())
        }
        return links
    }

    private fun extractStreamFromAtob(html: String, season: Int?, episode: Int?): String? {
        val atobRegex = Regex("""atob\s*\(\s*['"]([^"']{20,})['"]\s*\)""", RegexOption.IGNORE_CASE)
        for (match in atobRegex.findAll(html)) {
            try {
                val b64 = match.groupValues[1]
                val decoded = base64Decode(b64)
                if (decoded.length < 20) continue

                val fileMatch = Regex("""file\s*:\s*'(\[.*?\])'""", RegexOption.DOT_MATCHES_ALL).find(decoded) ?: continue
                val jsonArray = JSONArray(fileMatch.groupValues[1])
                if (jsonArray.length() == 0) continue

                if (season != null && episode != null) {
                    val seasonIdx = season - 1
                    val seasonObj = jsonArray.optJSONObject(seasonIdx) ?: continue
                    val folder = seasonObj.optJSONArray("folder") ?: continue
                    val epIdx = episode - 1
                    val epObj = folder.optJSONObject(epIdx) ?: continue
                    val fileVal = epObj.optString("file").ifBlank { continue }
                    return buildDownloadUrl(fileVal) ?: fileVal
                }

                val first = jsonArray.optJSONObject(0) ?: continue
                val fileVal = first.optString("file").ifBlank { continue }
                return buildDownloadUrl(fileVal) ?: fileVal

            } catch (_: Exception) {}
        }
        return null
    }

    private fun buildDownloadUrl(fileVal: String): String? {
        val baseEnd = fileVal.indexOf("/public_files/")
        if (baseEnd == -1) {
            StreamITALogger.log(TAG, "buildDownloadUrl: /public_files/ non trovato in $fileVal")
            return null
        }
        val cdnBase = fileVal.substring(0, baseEnd + "/public_files/".length)
        val rest = fileVal.substring(baseEnd + "/public_files/".length)
        val parts = rest.split(",")

        val video = parts.find { it.contains("1080p") && it.endsWith(".mp4") }
            ?: parts.find { it.endsWith(".mp4") }
        if (video == null) {
            StreamITALogger.log(TAG, "buildDownloadUrl: nessun video mp4 trovato in parts=$parts")
            return null
        }
        StreamITALogger.log(TAG, "buildDownloadUrl: video=$video")

        val itaAudio = parts.find { Regex("""italian|italiano""", RegexOption.IGNORE_CASE).containsMatchIn(it) && it.endsWith(".m4a") }
        if (itaAudio != null) {
            StreamITALogger.log(TAG, "buildDownloadUrl: traccia italiana=$itaAudio")
        } else {
            StreamITALogger.log(TAG, "buildDownloadUrl: nessuna traccia italiana, procedo comunque")
        }

        val hasM3u8 = parts.any { it.contains(".m3u8") }
        val suffix = if (hasM3u8) "" else ".urlset/master.m3u8"
        val result = cdnBase + rest + suffix
        StreamITALogger.log(TAG, "buildDownloadUrl: risultato=$result")
        return result
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try {
            val baseUrl = java.net.URL(base)
            java.net.URL(baseUrl, relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    private fun extractQuality(url: String): Int {
        return when {
            url.contains("2160p") -> Qualities.P2160.value
            url.contains("1440p") -> Qualities.P1440.value
            url.contains("1080p") -> Qualities.P1080.value
            url.contains("720p") -> Qualities.P720.value
            url.contains("480p") -> Qualities.P480.value
            url.contains("360p") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
