package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object CinemaCityScraper {
    private const val TAG = "CinemaCityScraper"
    private const val BASE_URL = "https://cinemacity.cc"
    private const val SITEMAP_URL = "$BASE_URL/news_pages.xml"
    private const val TMDB_URL = "https://api.themoviedb.org/3"
    private const val SITEMAP_CACHE_KEY = "CINEMACITY:SITEMAP"
    private const val TMDB_CACHE_KEY_PREFIX = "CINEMACITY:TMDB:"

    private val cfKiller = CloudflareKiller()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    )

    private fun tmdbHeaders() = mapOf(
        "Authorization" to "Bearer ${BuildConfig.TMDB_API}",
        "Accept" to "application/json"
    )

    suspend fun loadLinks(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        StreamITALogger.log(TAG, "Avvio CinemaCity per $imdbId S${season}E${episode}...")

        try {
            val isMovie = season == null
            val pageUrl = resolveViaSitemap(imdbId, !isMovie) ?: run {
                StreamITALogger.log(TAG, "Nessun match sitemap per $imdbId")
                return false
            }

            StreamITALogger.log(TAG, "Pagina trovata: $pageUrl")

            val pageResponse = app.get(pageUrl, headers = headers, interceptor = cfKiller)
            val html = pageResponse.text

            // FIX 1: Prova link diretti (MP4/M3U8)
            val directLinks = extractDownloadLinks(html)
            if (directLinks.isNotEmpty()) {
                StreamITALogger.log(TAG, "Trovati ${directLinks.size} link diretti")
                var selectedUrl: String? = null

                for (link in directLinks) {
                    if (link.second.contains("ita") || link.second.contains("italian") || link.second.contains("italiano")) {
                        selectedUrl = link.first
                        break
                    }
                }
                if (selectedUrl == null) {
                    for (link in directLinks) {
                        if (!link.second.contains("eng") && !link.second.contains("sub")) {
                            selectedUrl = link.first
                            break
                        }
                    }
                }
                if (selectedUrl == null) selectedUrl = directLinks.first().first

                val streamUrl = resolveUrl(pageUrl, selectedUrl)
                StreamITALogger.log(TAG, "Link diretto: $streamUrl")

                callback(
                    newExtractorLink(
                        source = "CinemaCity",
                        name = "CinemaCity",
                        url = streamUrl,
                        type = INFER_TYPE,
                    ) {
                        this.referer = BASE_URL
                        this.quality = Qualities.P1080.value
                    }
                )
                StreamITALogger.log(TAG, "CinemaCity OK (link diretto)")
                return true
            }

            // FIX 2: Prova estrazione atob()
            StreamITALogger.log(TAG, "Nessun link diretto, provo atob...")
            val atobUrl = extractStreamFromAtob(html, season, episode)
            if (atobUrl != null) {
                StreamITALogger.log(TAG, "URL da atob: $atobUrl")

                callback(
                    newExtractorLink(
                        source = "CinemaCity",
                        name = if (isMovie) "CinemaCity" else "CinemaCity S${season}E${episode}",
                        url = atobUrl,
                        type = INFER_TYPE,
                    ) {
                        this.referer = BASE_URL
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer" to BASE_URL,
                            "User-Agent" to headers["User-Agent"]!!
                        )
                    }
                )
                StreamITALogger.log(TAG, "CinemaCity OK (atob)")
                return true
            }

            StreamITALogger.log(TAG, "Nessun link trovato in pagina")
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "CinemaCity fallito: ${e.message}")
        }

        return false
    }

    // ==================== LINK DIRETTI HTML ====================

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

    // ==================== ESTRAZIONE ATOB ====================

    private fun extractStreamFromAtob(html: String, season: Int?, episode: Int?): String? {
        val atobRegex = Regex("""atob\s*\(\s*['"]([^"']{20,})['"]\s*\)""", RegexOption.IGNORE_CASE)
        for (match in atobRegex.findAll(html)) {
            try {
                val b64 = match.groupValues[1]
                val decoded = base64Decode(b64)
                if (decoded.length < 20) continue

                // Cerca file: '[JSON_ARRAY]' (stringa)
                val fileMatch = Regex("""file\s*:\s*'(\[.*?\])'""", RegexOption.DOT_MATCHES_ALL).find(decoded) ?: continue
                val jsonArray = JSONArray(fileMatch.groupValues[1])

                if (jsonArray.length() == 0) continue

                // Serie TV: folder esiste
                if (season != null && episode != null) {
                    val seasonIdx = season - 1
                    val seasonObj = jsonArray.optJSONObject(seasonIdx) ?: continue
                    val folder = seasonObj.optJSONArray("folder") ?: continue
                    val epIdx = episode - 1
                    val epObj = folder.optJSONObject(epIdx) ?: continue
                    val fileVal = epObj.optString("file").ifBlank { continue }
                    return buildDownloadUrl(fileVal) ?: fileVal
                }

                // Film
                val first = jsonArray.optJSONObject(0) ?: continue
                val fileVal = first.optString("file").ifBlank { continue }
                return buildDownloadUrl(fileVal) ?: fileVal

            } catch (_: Exception) {}
        }
        return null
    }

    // ==================== BUILD DOWNLOAD URL ====================

    private fun buildDownloadUrl(fileVal: String): String? {
        val baseEnd = fileVal.indexOf("/public_files/")
        if (baseEnd == -1) return null
        val cdnBase = fileVal.substring(0, baseEnd + "/public_files/".length)
        val rest = fileVal.substring(baseEnd + "/public_files/".length)
        val parts = rest.split(",")

        val video = parts.find { it.contains("1080p") && it.endsWith(".mp4") }
            ?: parts.find { it.endsWith(".mp4") } ?: return null
        val itaAudio = parts.find { Regex("""italian|italiano""", RegexOption.IGNORE_CASE).containsMatchIn(it) && it.endsWith(".m4a") }
            ?: return null

        return cdnBase + rest
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try {
            val baseUrl = java.net.URL(base)
            java.net.URL(baseUrl, relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    // ==================== SITEMAP MATCHING (INVARIATO) ====================

    internal suspend fun resolveViaSitemap(imdbId: String, isTvSeries: Boolean): String? {
        val cacheKey = "$TMDB_CACHE_KEY_PREFIX$imdbId"

        val cached = StreamITACache.get(cacheKey)
        val pageUrl = if (cached != null) {
            cached
        } else {
            val tmdbUrl = "$TMDB_URL/find/$imdbId?external_source=imdb_id&language=it-IT"
            val tmdbText = try {
                app.get(tmdbUrl, headers = tmdbHeaders()).text
            } catch (e: Exception) {
                StreamITALogger.log(TAG, "TMDB fallito: ${e.message}")
                return null
            }
            val tmdbJson = JSONObject(tmdbText)
            val resultsKey = if (isTvSeries) "tv_results" else "movie_results"
            val results = tmdbJson.optJSONArray(resultsKey) ?: return null
            if (results.length() == 0) return null

            val first = results.getJSONObject(0)
            val title = first.optString("title").ifBlank { first.optString("name") }.ifBlank { null } ?: return null
            val originalTitle = first.optString("original_title").ifBlank { first.optString("original_name") }
            val date = first.optString("release_date").ifBlank { first.optString("first_air_date") }
            val year = date.take(4).toIntOrNull()
            val titles = listOfNotNull(title, originalTitle).distinct()

            StreamITALogger.log(TAG, "TMDB: '$title' ($year), originale: '$originalTitle'")

            val sitemapXml = fetchSitemap()
            val kind = if (isTvSeries) "tv-series" else "movies"
            val entries = parseSitemap(sitemapXml, kind)
            val url = findBestMatch(entries, titles, year) ?: return null

            StreamITACache.put(cacheKey, url, StreamITACache.CacheProfile.CINEMACITY_SITEMAP)
            url
        }

        return pageUrl
    }

    private suspend fun fetchSitemap(): String {
        val cached = StreamITACache.get(SITEMAP_CACHE_KEY)
        if (cached != null) return cached

        val xml = app.get(SITEMAP_URL, headers = headers, interceptor = cfKiller).text
        StreamITACache.put(SITEMAP_CACHE_KEY, xml, StreamITACache.CacheProfile.CINEMACITY_SITEMAP)
        return xml
    }

    private data class SitemapEntry(
        val url: String,
        val kind: String,
        val slug: String,
        val title: String,
        val year: Int?,
    )

    private fun parseSitemap(xml: String, kind: String): List<SitemapEntry> {
        val entries = mutableListOf<SitemapEntry>()
        val regex = Regex(
            """<loc>(https://cinemacity\.cc/(movies|tv-series)/\d+-([a-z0-9-]+)\.html)</loc>""",
            RegexOption.IGNORE_CASE
        )
        for (match in regex.findAll(xml)) {
            val url = match.groupValues[1]
            val entryKind = match.groupValues[2]
            if (entryKind != kind) continue
            val slug = match.groupValues[3]
            val yearMatch = Regex("""-(\d{4})$""").find(slug)
            val extractedYear = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            val titleSlug = if (extractedYear != null) slug.dropLast(5) else slug
            val title = titleSlug.replace("-", " ")
            entries.add(SitemapEntry(url, entryKind, slug, title, extractedYear))
        }
        return entries
    }

    private fun findBestMatch(
        entries: List<SitemapEntry>,
        titles: List<String>,
        expectedYear: Int?,
    ): String? {
        var bestScore = -1.0
        var bestUrl: String? = null

        for (entry in entries) {
            for (title in titles) {
                val normalized = cleanMatchText(title)
                val entryTitle = cleanMatchText(entry.title)
                val entryTokens = entryTitle.split(" ").filter { it.length > 1 }.toSet()
                val titleTokens = normalized.split(" ").filter { it.length > 1 }.toSet()

                var score = when {
                    entryTitle == normalized -> 1.0
                    entryTitle.startsWith(normalized) || normalized.startsWith(entryTitle) -> 0.85
                    entryTitle.contains(normalized) || normalized.contains(entryTitle) -> 0.6
                    else -> {
                        val intersection = entryTokens.intersect(titleTokens)
                        if (intersection.isNotEmpty())
                            intersection.size.toDouble() / maxOf(entryTokens.size, titleTokens.size, 1) * 0.7
                        else 0.0
                    }
                }

                if (expectedYear != null && entry.year != null) {
                    score += if (entry.year == expectedYear) 0.3
                    else -0.1 * abs(entry.year - expectedYear)
                }

                if (score > bestScore) {
                    bestScore = score
                    bestUrl = entry.url
                }
            }
        }

        if (bestScore < 0.4) {
            StreamITALogger.log(TAG, "Nessun match sufficiente (best=$bestScore)")
            return null
        }
        StreamITALogger.log(TAG, "Match: $bestUrl (score=$bestScore)")
        return bestUrl
    }

    private fun cleanMatchText(value: String): String {
        return value.lowercase().trim()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
