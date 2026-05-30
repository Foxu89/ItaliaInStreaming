package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.StreamITACache.Companion.CacheProfile
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.math.abs

object CinemaCityScraper {
    private const val TAG = "CinemaCityScraper"
    private const val BASE_URL = "https://cinemacity.cc"
    private const val SITEMAP_URL = "$BASE_URL/news_pages.xml"
    private const val TMDB_URL = "https://api.themoviedb.org/3"
    private const val SITEMAP_CACHE_KEY = "CINEMACITY:SITEMAP"
    private const val TMDB_CACHE_KEY_PREFIX = "CINEMACITY:TMDB:"

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

            val pageResponse = app.get(pageUrl, headers = headers)
            val doc = Jsoup.parse(pageResponse.text)

            val playerScript = doc.select("script:containsData(atob)").getOrNull(1)?.data()
            if (playerScript == null) {
                StreamITALogger.log(TAG, "PlayerJS non trovato")
                return false
            }

            val decoded = base64Decode(
                playerScript.substringAfter("atob(\"").substringBefore("\")")
            )
            val playerJson = JSONObject(
                decoded.substringAfter("new Playerjs(").substringBeforeLast(");")
            )

            val rawFile = playerJson.opt("file") ?: return false
            val fileArray: JSONArray = when (rawFile) {
                is JSONArray -> rawFile
                is String -> if (rawFile.startsWith("[")) JSONArray(rawFile)
                else JSONArray().put(JSONObject().put("file", rawFile))
                else -> return false
            }

            return if (season != null && episode != null) {
                extractSeriesLinks(fileArray, season, episode, pageUrl, callback)
            } else {
                extractMovieLink(fileArray, callback)
            }
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "CinemaCity fallito: ${e.message}")
        }

        return false
    }

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

            StreamITACache.put(cacheKey, url, CacheProfile.CINEMACITY_SITEMAP)
            url
        }

        return pageUrl
    }

    private suspend fun fetchSitemap(): String {
        val cached = StreamITACache.get(SITEMAP_CACHE_KEY)
        if (cached != null) return cached

        val xml = app.get(SITEMAP_URL, headers = headers).text
        StreamITACache.put(SITEMAP_CACHE_KEY, xml, CacheProfile.CINEMACITY_SITEMAP)
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
            val yearMatch = Regex("-(\d{4})$").find(slug)
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

    private fun extractMovieLink(
        fileArray: JSONArray,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val movieFile = fileArray.optJSONObject(0)?.optString("file")
        if (movieFile.isNullOrBlank()) return false

        callback(
            newExtractorLink(
                source = "CinemaCity",
                name = "CinemaCity",
                url = movieFile,
                type = INFER_TYPE,
            ) {
                this.referer = BASE_URL
                this.quality = Qualities.P1080.value
            }
        )
        StreamITALogger.log(TAG, "CinemaCity OK (film)")
        return true
    }

    private suspend fun extractSeriesLinks(
        fileArray: JSONArray,
        targetSeason: Int,
        targetEpisode: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val seasonRegex = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val episodeRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)

        for (i in 0 until fileArray.length()) {
            val seasonJson = fileArray.optJSONObject(i) ?: continue
            val seasonNumber = seasonRegex.find(seasonJson.optString("title"))
                ?.groupValues?.get(1)?.toIntOrNull() ?: continue

            if (seasonNumber != targetSeason) continue

            val episodes = seasonJson.optJSONArray("folder") ?: continue
            for (j in 0 until episodes.length()) {
                val epJson = episodes.optJSONObject(j) ?: continue
                val episodeNumber = episodeRegex.find(epJson.optString("title"))
                    ?.groupValues?.get(1)?.toIntOrNull() ?: continue

                if (episodeNumber != targetEpisode) continue

                val file = epJson.optString("file")
                if (file.isNotBlank()) {
                    callback(
                        newExtractorLink(
                            source = "CinemaCity",
                            name = "CinemaCity S${targetSeason}E${targetEpisode}",
                            url = file,
                            type = INFER_TYPE,
                        ) {
                            this.referer = referer
                            this.quality = Qualities.P1080.value
                        }
                    )
                    StreamITALogger.log(TAG, "CinemaCity OK: S${targetSeason}E${targetEpisode}")
                    return true
                }
            }
        }

        StreamITALogger.log(TAG, "Episodio S${targetSeason}E${targetEpisode} non trovato")
        return false
    }
}
