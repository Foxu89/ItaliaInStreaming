package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object AnimeUnityHelper {
    private const val TAG = "AnimeUnityHelper"
    private const val BASE_URL = "https://www.animeunity.so"

    private val headers = mutableMapOf(
        "Host" to "www.animeunity.so",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )

    data class AnimeUnityAnime(
        val id: Int,
        val slug: String,
        val name: String,
        val anilistId: Int?,
        val malId: Int?,
        val isDub: Boolean,
    )

    data class AnimeUnityEpisode(
        val id: Int,
        val number: String,
        val name: String?,
    )

    private var csrfToken = ""
    private var cookieStr = ""
    private var sessionReady = false

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

    suspend fun search(title: String): List<AnimeUnityAnime> {
        ensureSession()

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

        val results = mutableListOf<AnimeUnityAnime>()
        for (i in 0 until records.length()) {
            val item = records.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            val slug = item.optString("slug")
            val name = item.optString("name")
            if (id > 0 && slug.isNotBlank()) {
                results.add(
                    AnimeUnityAnime(
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

        Log.d(TAG, "Trovati ${results.size} risultati per '$title'")
        return results
    }

    suspend fun loadEpisodes(animeId: Int, slug: String): List<AnimeUnityEpisode> {
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
                    AnimeUnityEpisode(id = id, number = number, name = ep.optString("name").takeIf { it.isNotBlank() })
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore parsing episodi: ${e.message}")
            emptyList()
        }
    }

    suspend fun getEmbedUrl(animeId: Int, slug: String, episodeId: Int): String? {
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
}
