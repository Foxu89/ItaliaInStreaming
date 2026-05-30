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
import kotlin.math.max

object CinemaCityScraper {
    private const val TAG = "CinemaCityScraper"
    private const val BASE_URL = "https://cinemacity.cc"
    private const val SITEMAP_URL = "$BASE_URL/news_pages.xml"
    private const val TMDB_URL = "https://api.themoviedb.org/3"
    private const val WORKER_BASE = "https://cm.leanhuo61206.workers.dev"
    private const val SITEMAP_CACHE_MS = 60 * 60 * 1000L
    private const val TMDB_CACHE_KEY_PREFIX = "CINEMACITY:TMDB:"

    private val cfKiller = CloudflareKiller()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    )

    private fun tmdbHeaders() = mapOf(
        "Authorization" to "Bearer ${BuildConfig.TMDB_API}",
        "Accept" to "application/json"
    )

    private data class SitemapCache(
        val entries: List<SitemapEntry>,
        val expiresAt: Long,
    )

    private data class SitemapEntry(
        val url: String,
        val kind: String,
        val slug: String,
        val title: String,
        val normalizedTitle: String,
        val compactTitle: String,
        val tokens: Set<String>,
        val year: Int?,
    )

    private var sitemapCache: SitemapCache? = null

    private suspend fun fetchViaWorker(url: String): String? {
        val path = try {
            val u = java.net.URL(url)
            u.path + if (u.query != null) "?${u.query}" else ""
        } catch (_: Exception) { url }
        val workerUrl = "${WORKER_BASE}${path}"
        StreamITALogger.log(TAG, "Worker → $workerUrl")
        return try {
            val text = app.get(workerUrl, headers = headers).text
            if (text.length < 10) null else text
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "Worker fallito: ${e.message}")
            null
        }
    }

    private fun isBlockedResponse(text: String): Boolean {
        return text.length < 500 ||
            text.contains("Just a moment", ignoreCase = true) ||
            (text.contains("admin", ignoreCase = true) && text.contains("Unlimited"))
    }

    private fun decodeHtmlEntities(value: String): String {
        var result = value
        result = result.replace(Regex("&#(\\d+);")) {
            it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value
        }
        result = result.replace(Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE)) {
            it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.value
        }
        result = result.replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("&(?:ndash|mdash);"), "-")
            .replace(Regex("[\u2013\u2014]"), "-")
        return result
    }

    private fun normalizeTitle(value: String): String {
        return decodeHtmlEntities(value)
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKD) }
            .replace(Regex("[\u0300-\u036f]"), "")
            .lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun compactTitle(value: String): String {
        return normalizeTitle(value).replace("\\s+".toRegex(), "")
    }

    private fun getSignificantTokens(value: String): Set<String> {
        val stopwords = setOf(
            "the", "a", "an", "of", "and", "in", "on", "to", "for", "at", "by", "is", "it",
            "il", "lo", "la", "gli", "le", "un", "uno", "una",
            "di", "da", "del", "della", "dei", "e", "o", "con", "per", "su", "tra", "fra"
        )
        return normalizeTitle(value)
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in stopwords }
            .toSet()
    }

    private fun scoreSitemapEntry(entry: SitemapEntry, expectedTitles: List<String>, expectedYear: Int?): Int {
        var bestScore = 0
        for (title in expectedTitles) {
            val normalized = normalizeTitle(title)
            val compact = compactTitle(title)
            if (normalized.isBlank() || compact.isBlank()) continue

            val score = when {
                entry.normalizedTitle == normalized || entry.compactTitle == compact -> 1000
                entry.normalizedTitle.startsWith(normalized) || normalized.startsWith(entry.normalizedTitle) -> 500
                entry.compactTitle.contains(compact) || compact.contains(entry.compactTitle) -> 420
                else -> {
                    val expectedTokens = getSignificantTokens(title)
                    if (expectedTokens.isNotEmpty() && entry.tokens.isNotEmpty()) {
                        val hits = expectedTokens.count { it in entry.tokens }
                        val coverage = hits.toDouble() / expectedTokens.size
                        val extraTokens = max(0, entry.tokens.size - expectedTokens.size)
                        val lengthDiff = abs(entry.tokens.size - expectedTokens.size)
                        (coverage * 300 - extraTokens * 20 - lengthDiff * 2).toInt()
                    } else 0
                }
            }

            val yearBonus = if (expectedYear != null && entry.year != null) {
                if (entry.year == expectedYear) 50 else -abs(entry.year - expectedYear) * 3
            } else 0

            bestScore = max(bestScore, score + yearBonus)
        }
        return bestScore
    }

    private fun extractImdbIdFromHtml(html: String): String? {
        return Regex("""\b(tt\d{5,})\b""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.lowercase()
    }

    private fun parseSitemapEntries(xml: String): List<SitemapEntry> {
        val entries = mutableListOf<SitemapEntry>()
        val regex = Regex(
            """<loc>(https://cinemacity\.cc/(movies|tv-series)/\d+-([a-z0-9-]+)\.html)</loc>""",
            RegexOption.IGNORE_CASE
        )
        for (match in regex.findAll(xml)) {
            val url = match.groupValues[1]
            val kind = match.groupValues[2]
            val slug = match.groupValues[3]
            val yearMatch = Regex("""-(\d{4})$""").find(slug)
            val extractedYear = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            val titleSlug = if (extractedYear != null) slug.dropLast(5) else slug
            val title = titleSlug.replace("-", " ")
            entries.add(
                SitemapEntry(
                    url = url,
                    kind = kind,
                    slug = slug,
                    title = title,
                    normalizedTitle = normalizeTitle(title),
                    compactTitle = compactTitle(title),
                    tokens = getSignificantTokens(title),
                    year = extractedYear
                )
            )
        }
        return entries
    }

    private suspend fun fetchSitemapEntries(): List<SitemapEntry>? {
        val now = System.currentTimeMillis()
        if (sitemapCache != null && sitemapCache!!.expiresAt > now) {
            return sitemapCache!!.entries
        }

        StreamITALogger.log(TAG, "Fetching sitemap catalog...")

        try {
            val sitemapPath = "/news_pages.xml"
            val firstPageUrl = "$WORKER_BASE$sitemapPath?page=1&perPage=500"
            StreamITALogger.log(TAG, "Sitemap page 1: $firstPageUrl")
            val firstResp = app.get(firstPageUrl, headers = headers)
            val totalEntries = firstResp.headers["x-total-entries"]?.toIntOrNull() ?: 0
            val firstXml = firstResp.text
            var allEntries = parseSitemapEntries(firstXml)

            if (totalEntries > 0) {
                val totalPages = (totalEntries + 499) / 500
                for (p in 2..totalPages) {
                    try {
                        val pageUrl = "$WORKER_BASE$sitemapPath?page=$p&perPage=500"
                        val resp = app.get(pageUrl, headers = headers)
                        allEntries = allEntries + parseSitemapEntries(resp.text)
                    } catch (_: Exception) {}
                }
            }

            if (allEntries.isNotEmpty()) {
                sitemapCache = SitemapCache(allEntries, now + SITEMAP_CACHE_MS)
                StreamITALogger.log(TAG, "Sitemap loaded: ${allEntries.size} entries")
                return allEntries
            }
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "Sitemap pagination fallito: ${e.message}")
        }

        StreamITALogger.log(TAG, "Fallback sitemap full fetch...")
        val xml = fetchViaWorker(SITEMAP_URL) ?: return null
        if (!isBlockedResponse(xml)) {
            val entries = parseSitemapEntries(xml)
            if (entries.isNotEmpty()) {
                sitemapCache = SitemapCache(entries, now + SITEMAP_CACHE_MS)
                StreamITALogger.log(TAG, "Sitemap loaded: ${entries.size} entries")
                return entries
            }
        }

        StreamITALogger.log(TAG, "Sitemap worker blocked, fallback CFK...")
        val cfkXml = try { app.get(SITEMAP_URL, headers = headers, interceptor = cfKiller).text } catch (_: Exception) { null }
        if (cfkXml != null && !isBlockedResponse(cfkXml)) {
            val entries = parseSitemapEntries(cfkXml)
            if (entries.isNotEmpty()) {
                sitemapCache = SitemapCache(entries, now + SITEMAP_CACHE_MS)
                return entries
            }
        }
        return null
    }

    internal suspend fun resolveViaSitemap(imdbId: String, isTvSeries: Boolean): String? {
        val cacheKey = "$TMDB_CACHE_KEY_PREFIX$imdbId"

        val cached = StreamITACache.get(cacheKey)
        if (cached != null) return cached

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

        val kind = if (isTvSeries) "tv-series" else "movies"
        val entries = fetchSitemapEntries() ?: return null

        val ranked = entries
            .filter { it.kind == kind }
            .map { entry -> entry to scoreSitemapEntry(entry, titles, year) }
            .filter { it.second >= 250 }
            .sortedByDescending { it.second }

        if (ranked.isEmpty()) {
            StreamITALogger.log(TAG, "Nessun match confidente per $title (best < 250)")
            return null
        }

        for ((candidate, _) in ranked.take(3)) {
            val candidateImdbId = verifyCandidateImdb(candidate.url, imdbId)
            if (candidateImdbId == imdbId.lowercase()) {
                StreamITALogger.log(TAG, "IMDb verificato: $title → ${candidate.url}")
                StreamITACache.put(cacheKey, candidate.url, StreamITACache.CacheProfile.CINEMACITY_TMDB)
                return candidate.url
            }
            if (candidateImdbId != null) {
                StreamITALogger.log(TAG, "IMDb mismatch: ${candidate.url} ha $candidateImdbId, atteso $imdbId")
            }
        }

        val bestScore = ranked.first().second
        if (bestScore < 950) {
            StreamITALogger.log(TAG, "Match non verificato IMDb, score $bestScore < 950")
            return null
        }

        val bestUrl = ranked.first().first.url
        StreamITALogger.log(TAG, "Match high confidence: $bestUrl (score=$bestScore)")
        StreamITACache.put(cacheKey, bestUrl, StreamITACache.CacheProfile.CINEMACITY_TMDB)
        return bestUrl
    }

    private suspend fun verifyCandidateImdb(candidateUrl: String, expectedImdbId: String): String? {
        if (!Regex("""^tt\d{5,}$""", RegexOption.IGNORE_CASE).matches(expectedImdbId)) return null
        try {
            val html = fetchViaWorker(candidateUrl) ?: return null
            val imdbId = extractImdbIdFromHtml(html)
            if (imdbId != null) {
                StreamITALogger.log(TAG, "IMDb check $candidateUrl: $imdbId")
            }
            return imdbId
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val isOk = msg.contains("403") || msg.contains("503") ||
                Regex("Cloudflare has blocked|Error solving the challenge", RegexOption.IGNORE_CASE).containsMatchIn(msg)
            if (!isOk) {
                StreamITALogger.log(TAG, "IMDb check error: ${e.message}")
            }
            return null
        }
    }

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

            var html = fetchViaWorker(pageUrl)
            if (html != null && !isBlockedResponse(html)) {
                StreamITALogger.log(TAG, "Pagina via worker")
            } else {
                StreamITALogger.log(TAG, "Worker bloccato, fallback CFK...")
                html = try { app.get(pageUrl, headers = headers, interceptor = cfKiller).text } catch (_: Exception) { null }
                if (html == null || isBlockedResponse(html)) {
                    StreamITALogger.log(TAG, "Anche CFK bloccato")
                    return false
                }
            }

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
        if (baseEnd == -1) return null
        val cdnBase = fileVal.substring(0, baseEnd + "/public_files/".length)
        val rest = fileVal.substring(baseEnd + "/public_files/".length)
        val parts = rest.split(",")

        val video = parts.find { it.contains("1080p") && it.endsWith(".mp4") }
            ?: parts.find { it.endsWith(".mp4") } ?: return null
        val itaAudio = parts.find { Regex("""italian|italiano""", RegexOption.IGNORE_CASE).containsMatchIn(it) && it.endsWith(".m4a") }
            ?: return null

        val hasM3u8 = parts.any { it.contains(".m3u8") }
        val suffix = if (hasM3u8) "" else ".urlset/master.m3u8"

        return cdnBase + rest + suffix
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try {
            val baseUrl = java.net.URL(base)
            java.net.URL(baseUrl, relative).toString()
        } catch (_: Exception) {
            relative
        }
    }
}
