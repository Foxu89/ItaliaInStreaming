package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URLEncoder

object AnimeWorldScraper {
    private const val TAG = "AnimeWorldScraper"
    private const val BASE_URL = "https://www.animeworld.ac"
    private const val MAPPING_URL = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"

    private val headers = mapOf(
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )

    data class Anime(
        val url: String, val title: String, val otherTitle: String?,
        val isDub: Boolean, val anilistId: Int? = null, val malId: Int? = null,
    )

    data class Episode(val number: String, val token: String)

    data class EpisodeInfo(val grabber: String, val target: String, val label: String)

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
            val jsonText = StreamITACache.get("AW:MAPPING") ?: app.get(MAPPING_URL).text.also {
                StreamITACache.put("AW:MAPPING", it, StreamITACache.CacheProfile.ANIME_MAPPING)
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
            val results = searchRaw(searchTitle)
            allResults.addAll(results)
        }

        if (allResults.isEmpty()) {
            StreamITALogger.log(TAG, "Nessun risultato per '$title'")
            return TitleSources(null, null)
        }

        val uniqueResults = allResults.distinctBy { it.url }
        val enriched = uniqueResults.take(12).map { anime ->
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
        val url = "$BASE_URL/filter?sort=0&keyword=${URLEncoder.encode(title, "UTF-8")}"
        val cacheKey = "AW:SEARCH:${URLEncoder.encode(title, "UTF-8")}"

        val html = try {
            StreamITACache.get(cacheKey) ?: app.get(url, headers = headers).text.also {
                StreamITACache.put(cacheKey, it, StreamITACache.CacheProfile.ANIME_WORLD_SEARCH)
            }
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "Search error: ${e.message}")
            return emptyList()
        }

        val doc = Jsoup.parse(html, url)
        return doc.select("div.film-list > .item").mapNotNull { item ->
            val anchor = item.selectFirst("a.name[href]") ?: return@mapNotNull null
            val titleText = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val otherTitle = anchor.attr("data-jtitle").trim().takeIf { it.isNotBlank() }
            val itemUrl = if (anchor.attr("href").startsWith("http")) anchor.attr("href").trimEnd('/') else "$BASE_URL${anchor.attr("href")}".trimEnd('/')
            val isDub = item.select(".status .dub").isNotEmpty() || titleText.contains("(ITA)", ignoreCase = true) || otherTitle.orEmpty().contains("(ITA)", ignoreCase = true)
            Anime(url = itemUrl, title = titleText, otherTitle = otherTitle, isDub = isDub)
        }
    }

    private fun filterByTmdbMatch(animes: List<Anime>, tmdbId: Int): List<Anime> {
        val targetIds = mappingCache ?: return animes
        val targetAnilistIds = targetIds.filter { it.tmdbId == tmdbId }.mapNotNull { it.anilistId }.toSet()
        val targetMalIds = targetIds.filter { it.tmdbId == tmdbId }.mapNotNull { it.malId }.toSet()
        if (targetAnilistIds.isEmpty() && targetMalIds.isEmpty()) return animes

        val exactMatches = animes.filter { anime ->
            (anime.anilistId != null && anime.anilistId in targetAnilistIds) || (anime.malId != null && anime.malId in targetMalIds)
        }
        return if (exactMatches.isNotEmpty()) exactMatches else animes
    }

    private fun filterByTitleMatch(animes: List<Anime>, searchTitle: String): List<Anime> {
        val normalizedSearch = normalizeTitle(searchTitle)
        val matches = animes.filter { anime ->
            val animeTitle = normalizeTitle(anime.title)
            val animeOther = anime.otherTitle?.let { normalizeTitle(it) }
            animeTitle == normalizedSearch || animeOther == normalizedSearch ||
            animeTitle.contains(normalizedSearch) || normalizedSearch.contains(animeTitle) ||
            animeOther?.contains(normalizedSearch) == true
        }
        return matches.ifEmpty { animes }
    }

    private suspend fun loadPageData(animeUrl: String): PageData {
        val cacheKey = "AW:PAGE:$animeUrl"
        return try {
            val html = StreamITACache.get(cacheKey) ?: app.get(animeUrl, headers = headers).text.also {
                StreamITACache.put(cacheKey, it, StreamITACache.CacheProfile.ANIME_WORLD_DETAIL)
            }
            val doc = Jsoup.parse(html, animeUrl)
            PageData(
                anilistId = doc.selectFirst("#anilist-button[href]")?.attr("href")?.substringAfterLast('/')?.toIntOrNull(),
                malId = doc.selectFirst("#mal-button[href]")?.attr("href")?.substringAfterLast('/')?.toIntOrNull(),
            )
        } catch (e: Exception) {
            PageData(null, null)
        }
    }

    suspend fun loadEpisodes(animeUrl: String): List<Episode> {
        StreamITALogger.log(TAG, "Caricando episodi: $animeUrl")
        val cacheKey = "AW:DETAIL:$animeUrl"

        val html = try {
            StreamITACache.get(cacheKey) ?: app.get(animeUrl, headers = headers).text.also {
                StreamITACache.put(cacheKey, it, StreamITACache.CacheProfile.ANIME_WORLD_DETAIL)
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val doc = Jsoup.parse(html, animeUrl)
        val preferredAnchors = doc.select(".widget.servers .server[data-name=9] a[data-id][data-episode-num]")
        val anchors = preferredAnchors.ifEmpty { doc.select(".widget.servers a[data-id][data-episode-num]") }

        return anchors.mapNotNull { anchor ->
            val token = anchor.attr("data-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val number = anchor.attr("data-episode-num").takeIf { it.isNotBlank() } ?: anchor.attr("data-num").takeIf { it.isNotBlank() } ?: anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Episode(number = normalizeNumber(number) ?: number, token = token)
        }.distinctBy { normalizeNumber(it.number) ?: it.number }
    }

    suspend fun getEpisodeInfo(animeUrl: String, episodeToken: String, isDub: Boolean): EpisodeInfo? {
        val infoUrl = "$BASE_URL/api/episode/info?id=${URLEncoder.encode(episodeToken, "UTF-8")}"
        val cacheKey = "AW:PLAYER:${URLEncoder.encode(episodeToken, "UTF-8")}"
        StreamITALogger.log(TAG, "Info episodio: $infoUrl")

        val text = try {
            StreamITACache.get(cacheKey) ?: app.get(infoUrl, headers = headers + mapOf("Referer" to animeUrl)).text.also {
                StreamITACache.put(cacheKey, it, StreamITACache.CacheProfile.ANIME_WORLD_PLAYER)
            }
        } catch (e: Exception) {
            return null
        }

        val json = JSONObject(text)
        val grabber = json.optString("grabber", "").takeIf { it.isNotBlank() } ?: return null
        val target = json.optString("target", "")
        val label = if (isDub) "AnimeWorld [DUB]" else "AnimeWorld [SUB]"
        return EpisodeInfo(grabber = grabber, target = target, label = label)
    }

    suspend fun loadLinks(episodeInfo: EpisodeInfo, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (episodeInfo.target.contains("listeamed.net", ignoreCase = true) || episodeInfo.grabber.contains("listeamed.net", ignoreCase = true)) {
            return try {
                loadExtractor(episodeInfo.grabber, BASE_URL, subtitleCallback, callback)
            } catch (e: Exception) {
                StreamITALogger.log(TAG, "Errore loadExtractor listeamed: ${e.message}")
                false
            }
        }
        callback(newExtractorLink(source = "AnimeWorld", name = episodeInfo.label, url = episodeInfo.grabber, type = INFER_TYPE) {
            this.referer = BASE_URL; this.quality = Qualities.Unknown.value
        })
        return true
    }

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
