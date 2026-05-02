package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.VixCloudExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object AnimeUnityScraper {
    private const val TAG = "AnimeUnityScraper"
    private const val BASE_URL = "https://www.animeunity.so"
    private const val MAPPING_URL = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"

    private val headers = mutableMapOf(
        "Host" to "www.animeunity.so",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )

    data class Anime(
        val id: Int,
        val slug: String,
        val name: String,
        val anilistId: Int?,
        val malId: Int?,
        val isDub: Boolean,
    )

    data class Episode(
        val id: Int,
        val number: String,
        val name: String?,
    )

    private var csrfToken = ""
    private var cookieStr = ""
    private var sessionReady = false
    private var animeMappingCache: JSONArray? = null

    private suspend fun ensureSession() {
        if (sessionReady) return

        Log.d(TAG, "Ottenendo sessione AnimeUnity...")
        val response = app.get("$BASE_URL/archivio", headers = headers)

        val doc = Jsoup.parse(response.text)
        csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        val cookies = response.cookies
        val parts = mutableListOf<String>()
        cookies["XSRF-TOKEN"]?.let { parts.add("XSRF-TOKEN=$it") }
        cookies["animeunity_session"]?.let { parts.add("animeunity_session=$it") }
        cookieStr = parts.joinToString("; ")

        sessionReady = true
        Log.d(TAG, "Sessione AnimeUnity pronta")
    }

    private fun getApiHeaders(): Map<String, String> {
        return headers + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json;charset=utf-8",
            "X-CSRF-Token" to csrfToken,
            "Referer" to BASE_URL,
            "Cookie" to cookieStr,
        )
    }

    private suspend fun getAnimeMapping(): JSONArray {
        if (animeMappingCache != null) return animeMappingCache!!

        Log.d(TAG, "Scaricando anime-list-full.json...")
        return try {
            val response = app.get(MAPPING_URL)
            JSONArray(response.text).also { animeMappingCache = it }
        } catch (e: Exception) {
            Log.e(TAG, "Errore download mapping: ${e.message}")
            JSONArray()
        }
    }

    private suspend fun getSyncIds(tmdbId: Int): Pair<Int?, Int?> {
        val mapping = getAnimeMapping()
        for (i in 0 until mapping.length()) {
            val entry = mapping.optJSONObject(i) ?: continue
            if (entry.optNullableInt("themoviedb_id") == tmdbId) {
                return Pair(
                    entry.optNullableInt("anilist_id"),
                    entry.optNullableInt("mal_id")
                )
            }
        }
        return Pair(null, null)
    }

    private suspend fun search(title: String, tmdbId: Int? = null): List<Anime> {
        ensureSession()

        val (anilistId, malId) = if (tmdbId != null) getSyncIds(tmdbId) else Pair(null, null)
        Log.d(TAG, "Sync IDs per TMDB $tmdbId: anilist=$anilistId, mal=$malId")

        val body = JSONObject().apply {
            put("title", title)
            put("type", false)
            put("year", false)
            put("order", false)
            put("status", false)
            put("genres", false)
            put("season", false)
            put("dubbed", 0)
            put("offset", 0)
        }

        val requestBody = body.toString().toRequestBody("application/json;charset=utf-8".toMediaType())

        val response = app.post(
            "$BASE_URL/archivio/get-animes",
            headers = getApiHeaders(),
            requestBody = requestBody
        )

        val data = JSONObject(response.text)
        val records = data.optJSONArray("records") ?: JSONArray()

        val allResults = mutableListOf<Anime>()
        for (i in 0 until records.length()) {
            val item = records.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            val slug = item.optString("slug")
            val name = item.optString("name")
            if (id > 0 && slug.isNotBlank()) {
                allResults.add(
                    Anime(
                        id = id,
                        slug = slug,
                        name = name,
                        anilistId = item.optNullableInt("anilist_id"),
                        malId = item.optNullableInt("mal_id"),
                        isDub = item.optInt("dub") == 1 || name.contains("(ITA)", ignoreCase = true)
                    )
                )
            }
        }

        Log.d(TAG, "Trovati ${allResults.size} risultati per '$title'")

        // 1. Match esatto per anilist_id o mal_id
        if (anilistId != null || malId != null) {
            val matched = allResults.filter { anime ->
                (anilistId != null && anime.anilistId == anilistId) ||
                (malId != null && anime.malId == malId)
            }
            if (matched.isNotEmpty()) {
                Log.d(TAG, "Match esatto: ${matched.size} risultati")
                return matched
            }
        }

        // 2. Fallback: prendi il primo risultato
        if (allResults.isNotEmpty()) {
            Log.d(TAG, "Nessun match esatto, uso il primo risultato: ${allResults[0].name}")
            return listOf(allResults[0])
        }

        Log.d(TAG, "Nessun risultato trovato")
        return emptyList()
    }

    private suspend fun loadEpisodes(animeId: Int, slug: String): List<Episode> {
        ensureSession()

        val url = "$BASE_URL/anime/$animeId-$slug"
        val response = app.get(url, headers = getApiHeaders())
        val doc = Jsoup.parse(response.text)

        val videoPlayer = doc.selectFirst("video-player") ?: return emptyList()
        val episodesJson = videoPlayer.attr("episodes")

        return try {
            val episodes = JSONArray(episodesJson)
            (0 until episodes.length()).mapNotNull { i ->
                val ep = episodes.optJSONObject(i) ?: return@mapNotNull null
                val id = ep.optInt("id")
                val number = ep.optString("number")
                if (id > 0 && number.isNotBlank()) {
                    Episode(id = id, number = number, name = ep.optString("name").takeIf { it.isNotBlank() })
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore parsing episodi: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getEmbedUrl(animeId: Int, slug: String, episodeId: Int): String? {
        ensureSession()

        val url = "$BASE_URL/anime/$animeId-$slug/$episodeId"
        val response = app.get(url, headers = getApiHeaders())
        val doc = Jsoup.parse(response.text)

        return doc.selectFirst("video-player")?.attr("embed_url")?.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }?.takeIf { it > 0 }
    }

    /**
     * METODO PUBBLICO: cerca su AnimeUnity e carica i link.
     * Prova prima col titolo passato, poi col titolo inglese da TMDB.
     */
    suspend fun loadLinks(
        title: String,
        tmdbId: Int?,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        fetchEnglishTitle: suspend (Int, Boolean) -> String?
    ): Boolean {
        StreamITALogger.log(TAG, "Avvio AnimeUnity per '$title' (tmdbId=$tmdbId)...")

        // Prova prima col titolo italiano
        var auResults = search(title, tmdbId)

        // Se non trova, prova col titolo inglese da TMDB
        if (auResults.isEmpty() && tmdbId != null) {
            StreamITALogger.log(TAG, "Nessun risultato per '$title', provo titolo inglese...")
            val enTitle = fetchEnglishTitle(tmdbId, isMovie)
            if (enTitle != null && enTitle != title) {
                StreamITALogger.log(TAG, "Cerco AnimeUnity con titolo EN: '$enTitle'")
                auResults = search(enTitle, tmdbId)
            }
        }

        if (auResults.isEmpty()) {
            StreamITALogger.log(TAG, "Nessun risultato AnimeUnity")
            return false
        }

        val anime = auResults.first()
        val episodes = loadEpisodes(anime.id, anime.slug)

        val targetEp = if (season == null) {
            episodes.firstOrNull()
        } else {
            episodes.find { it.number == episode.toString() }
        }

        if (targetEp == null) {
            StreamITALogger.log(TAG, "Episodio $season-$episode non trovato")
            return false
        }

        val embedUrl = getEmbedUrl(anime.id, anime.slug, targetEp.id)
        if (embedUrl == null) {
            StreamITALogger.log(TAG, "Embed URL non trovato")
            return false
        }

        VixCloudExtractor().getUrl(embedUrl, BASE_URL, subtitleCallback, callback)
        StreamITALogger.log(TAG, "AnimeUnity OK: link trovato per ${anime.name} ep.${targetEp.number}")
        return true
    }
}
