package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONObject
import java.util.Locale

object StreamITASubtitles {
    private const val TAG = "StreamITASubtitles"

    /**
     * Carica sottotitoli da WyZIESUB (sub.wyzie.ru)
     */
    suspend fun loadWyzieSubs(
        imdbId: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val url = buildString {
            append("https://sub.wyzie.ru/search?id=$imdbId")
            if (season != null && episode != null) {
                append("&season=$season&episode=$episode")
            }
        }

        try {
            val response = app.get(url).text
            if (response.isBlank()) return

            val json = org.json.JSONArray(response)
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val subUrl = item.optString("url", "").takeIf { it.isNotBlank() } ?: continue
                val langName = item.optString("display", item.optString("language", "Unknown"))
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                subtitleCallback(newSubtitleFile("Wyzie - $langName", subUrl))
                StreamITALogger.log(TAG, "Wyzie subtitle: $langName")
            }
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "Errore WyZIESUB: ${e.message}")
        }
    }

    /**
     * Carica sottotitoli da OpenSubtitles (via Stremio)
     */
    suspend fun loadOpenSubtitles(
        imdbId: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val url = if (season == null) {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$season:$episode.json"
        }

        try {
            val response = app.get(url).text
            if (response.isBlank()) return

            val json = JSONObject(response)
            val subtitles = json.optJSONArray("subtitles") ?: return

            for (i in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(i) ?: continue
                val subUrl = item.optString("url", "").takeIf { it.isNotBlank() } ?: continue
                val lang = item.optString("lang", item.optString("language", "Unknown"))
                val langName = getLanguageName(lang)

                subtitleCallback(newSubtitleFile("OpenSubtitles - $langName", subUrl))
                StreamITALogger.log(TAG, "OpenSubtitles subtitle: $langName")
            }
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "Errore OpenSubtitles: ${e.message}")
        }
    }

    private fun getLanguageName(code: String): String {
        val normalized = code.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
        return when {
            normalized.contains("eng") || normalized.contains("en") -> "English"
            normalized.contains("ita") || normalized.contains("it") -> "Italian"
            normalized.contains("spa") || normalized.contains("es") -> "Spanish"
            normalized.contains("fra") || normalized.contains("fr") -> "French"
            normalized.contains("deu") || normalized.contains("de") -> "German"
            normalized.contains("jpn") || normalized.contains("ja") -> "Japanese"
            normalized.contains("por") || normalized.contains("pt") -> "Portuguese"
            normalized.contains("rus") || normalized.contains("ru") -> "Russian"
            normalized.contains("ara") || normalized.contains("ar") -> "Arabic"
            normalized.contains("hin") || normalized.contains("hi") -> "Hindi"
            normalized.contains("kor") || normalized.contains("ko") -> "Korean"
            normalized.contains("tur") || normalized.contains("tr") -> "Turkish"
            normalized.contains("pol") || normalized.contains("pl") -> "Polish"
            normalized.contains("nld") || normalized.contains("nl") -> "Dutch"
            else -> code.uppercase()
        }
    }
}
