package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

object AnimeSaturnScraper {
    private const val TAG = "AnimeSaturnScraper"
    private const val BASE_URL = "https://www.animesaturn.so"
    private const val MAPPING_URL = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"

    private val headers = mapOf(
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )

    data class Anime(val url: String, val title: String, val isDub: Boolean, val anilistId: Int? = null, val malId: Int? = null)
    data class Episode(val number: String, val episodeUrl: String)
    data class TitleSources(val sub: Anime?, val dub: Anime?) {
        val hasSub: Boolean get() = sub != null
        val hasDub: Boolean get() = dub != null
        val best: Anime? get() = sub ?: dub
    }

    private var mappingCache: List<MappingEntry>? = null
    private data class MappingEntry(val tmdbId: Int, val anilistId: Int?, val malId: Int?)
    private data class PageData(val anilistId: Int?, val malId: Int?)

    private suspend fun getMapping(): List<MappingEntry> {
        mappingCache?.let { return it }
        return try {
            val jsonText = StreamITACache.get("AS:MAPPING") ?: app.get(MAPPING_URL).text.also {
                StreamITACache.put("AS:MAPPING", it, StreamITACache.CacheProfile.ANIME_MAPPING)
            }
            val json = org.json.JSONArray(jsonText)
            val entries = mutableListOf<MappingEntry>()
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val tmdbId = item.optNullableInt("themoviedb_id") ?: continue
                entries.add(MappingEntry(tmdbId = tmdbId, anilistId = item.optNullableInt("anilist_id"), malId = item.optNullableInt("mal_id")))
            }
            entries.also { mappingCache = it }
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "Errore download mapping: ${e.message}")
            emptyList()
        }
    }

    suspend fun searchWithSources(title: String, tmdbId: Int? = null, englishTitle: String? = null): TitleSources {
        val titlesToTry = mutableListOf(title)
        if (englishTitle != null && englishTitle != title) titlesToTry.add(englishTitle)

        val allResults = mutableListOf<Anime>()
        for (searchTitle in titlesToTry) {
            allResults.addAll(searchRaw(searchTitle))
        }

        if (allResults.isEmpty()) {
            StreamITALogger.log(TAG, "Nessun risultato per '$title'")
            return TitleSources(null, null)
        }

        val uniqueResults = allResults.distinctBy { it.url }
        val enriched = uniqueResults.take(8).map { anime ->
            val pageData = loadPageData(anime.url)
            anime.copy(anilistId = pageData.anilistId, malId = pageData.malId)
        }

        val filtered = if (tmdbId != null) filterByTmdbMatch(enriched, tmdbId) else filterByTitleMatch(enriched, title)
        val sub = filtered.firstOrNull { !it.isDub }
        val dub = filtered.firstOrNull { it.isDub }
        StreamITALogger.log(TAG, "SUB: ${sub?.title}, DUB: ${dub?.title}")
        return TitleSources(sub = sub, dub = dub)
    }

    private suspend fun searchRaw(title: String): List<Anime> {
        val url = "${BASE_URL}/animelist?search=${URLEncoder.encode(title, "UTF-8")}"
        val cacheKey = "AS:SEARCH:${URLEncoder.encode(title, "UTF-8")}"

        val html = try {
            StreamITACache.get(cacheKey) ?: app.get(url, headers = headers).text.also {
                StreamITACache.put(cacheKey, it, StreamITACache.CacheProfile.ANIME_SATURN_SEARCH)
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val doc = Jsoup.parse(html, url)
        return doc.select("li.list-group-item .item-archivio, div.item-archivio").mapNotNull { item ->
            val anchor = item.selectFirst("h3 a[href*=\"/anime/\"], a[href*=\"/anime/\"]") ?: return@mapNotNull null
            val titleText = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val itemUrl = if (anchor.attr("href").startsWith("http")) anchor.attr("href").trimEnd('/') else "$BASE_URL${anchor.attr("href")}".trimEnd('/')
            val isDub = titleText.contains("(ITA)", ignoreCase = true) || itemUrl.contains("-ITA", ignoreCase = true)
            Anime(url = itemUrl, title = titleText, isDub = isDub)
        }
    }

    private suspend fun loadPageData(animeUrl: String): PageData {
        val cacheKey = "AS:PAGE:$animeUrl"
        return try {
            val html = StreamITACache.get(cacheKey) ?: app.get(animeUrl, headers = headers).text.also {
                StreamITACache.put(cacheKey, it, StreamITACache.CacheProfile.ANIME_SATURN_DETAIL)
            }
            val doc = Jsoup.parse(html, animeUrl)
            PageData(
                anilistId = doc.select("a[href*=anilist.co/anime/]").firstOrNull()?.attr("href")?.let { extractAnilistId(it) },
                malId = doc.select("a[href*=myanimelist.net/anime/]").firstOrNull()?.attr("href")?.let { extractMalId(it) },
            )
        } catch (e: Exception) {
            PageData(null, null)
        }
    }

    private fun filterByTmdbMatch(animes: List<Anime>, tmdbId: Int): List<Anime> {
        val mapping = mappingCache ?: return animes
        val targetAnilistIds = mapping.filter { it.tmdbId == tmdbId }.mapNotNull { it.anilistId }.toSet()
        val targetMalIds = mapping.filter { it.tmdbId == tmdbId }.mapNotNull { it.malId }.toSet()
        if (targetAnilistIds.isEmpty() && targetMalIds.isEmpty()) return animes
        val exactMatches = animes.filter { a -> (a.anilistId != null && a.anilistId in targetAnilistIds) || (a.malId != null && a.malId in targetMalIds) }
        return if (exactMatches.isNotEmpty()) exactMatches else animes
    }

    private fun filterByTitleMatch(animes: List<Anime>, searchTitle: String): List<Anime> {
        val normalizedSearch = normalizeTitle(searchTitle)
        val matches = animes.filter { a ->
            val t = normalizeTitle(a.title)
            t == normalizedSearch || t.contains(normalizedSearch) || normalizedSearch.contains(t)
        }
        return matches.ifEmpty { animes }
    }

    suspend fun loadEpisodes(animeUrl: String): List<Episode> {
        StreamITALogger.log(TAG, "Caricando episodi: $animeUrl")
        val cacheKey = "AS:DETAIL:$animeUrl"

        val html = try {
            StreamITACache.get(cacheKey) ?: app.get(animeUrl, headers = headers).text.also {
                StreamITACache.put(cacheKey, it, StreamITACache.CacheProfile.ANIME_SATURN_DETAIL)
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val doc = Jsoup.parse(html, animeUrl)
        return doc.select("a[href*=/ep/], a[href*=/episode/]").mapNotNull { anchor ->
            val href = anchor.attr("href").takeIf { it.contains("/ep/") || it.contains("/episode/") } ?: return@mapNotNull null
            val number = extractEpisodeNumber(anchor) ?: return@mapNotNull null
            val normalizedNumber = normalizeNumber(number) ?: return@mapNotNull null
            val episodeUrl = if (href.startsWith("http")) href.trimEnd('/') else "$BASE_URL$href".trimEnd('/')
            Episode(number = normalizedNumber, episodeUrl = episodeUrl)
        }.distinctBy { it.number }
    }

    suspend fun getEpisodeVideoUrl(episodeUrl: String): String? {
        StreamITALogger.log(TAG, "Caricando video da: $episodeUrl")
        val cacheKey = "AS:PLAYER:$episodeUrl"

        val html = try {
            StreamITACache.get(cacheKey) ?: app.get(episodeUrl, headers = headers + mapOf("Referer" to BASE_URL)).text.also {
                StreamITACache.put(cacheKey, it, StreamITACache.CacheProfile.ANIME_SATURN_PLAYER)
            }
        } catch (e: Exception) {
            return null
        }

        val directUrl = extractVideoUrl(html)
        if (directUrl != null) return if (directUrl.startsWith("http")) directUrl else "$BASE_URL$directUrl"

        val doc = Jsoup.parse(html, episodeUrl)
        val watchLink = doc.selectFirst("a[href*=\"watch?file=\"]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val watchUrl = if (watchLink.startsWith("http")) watchLink else "$BASE_URL$watchLink"

        val watchHtml = try {
            app.get(watchUrl, headers = headers + mapOf("Referer" to episodeUrl)).text
        } catch (e: Exception) {
            return null
        }

        return extractVideoUrl(watchHtml)?.let { if (it.startsWith("http")) it else "$BASE_URL$it" }
    }

    suspend fun loadLinks(videoUrl: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        callback(newExtractorLink(source = "AnimeSaturn", name = "AnimeSaturn $label", url = videoUrl, type = INFER_TYPE) {
            this.referer = BASE_URL; this.quality = Qualities.P1080.value
        })
        return true
    }

    private fun extractVideoUrl(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.selectFirst("source[src], video[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { return cleanUrl(it) }
        val patterns = listOf(
            Regex("""file\s*:\s*["']([^"']+)["']"""),
            Regex("""src\s*:\s*["']([^"']+\.mp4[^"']*)["']"""),
            Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { p ->
            p.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { cleanUrl(it) }
        }
    }

    private fun cleanUrl(url: String): String = url.replace("\\/", "/").trim()

    private fun extractEpisodeNumber(anchor: Element): String? {
        val candidates = listOf(anchor.attr("data-episode"), anchor.attr("data-episode-number"), anchor.text(), anchor.attr("href"))
        candidates.forEach { candidate ->
            Regex("""(?i)(?:episodio|episode|ep)[\s._-]*(\d+(?:[.,]\d+)?)""").find(candidate)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun extractAnilistId(href: String): Int? = Regex("""anilist\.co/anime/(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun extractMalId(href: String): Int? = Regex("""myanimelist\.net/anime/(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun normalizeTitle(input: String): String {
        return input.lowercase().replace("&", "and")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\b(movie|the movie|ita|sub ita|subita|tv|ona|ova|special|season|stagione)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ").trim()
    }

    private fun normalizeNumber(number: String): String? {
        val normalized = number.trim().replace(',', '.')
        val numericValue = normalized.toDoubleOrNull() ?: return normalized.takeIf { it.isNotBlank() }
        val intValue = numericValue.toInt()
        return if (numericValue == intValue.toDouble()) intValue.toString() else numericValue.toString()
    }

    private fun org.json.JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }?.takeIf { it > 0 }
    }
}
