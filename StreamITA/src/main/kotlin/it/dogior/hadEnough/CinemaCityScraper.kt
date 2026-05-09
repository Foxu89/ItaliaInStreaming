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
import org.jsoup.Jsoup

object CinemaCityScraper {
    private const val TAG = "CinemaCityScraper"
    private const val BASE_URL = "https://cinemacity.cc"

    private val headers = mapOf(
        "Cookie" to base64Decode("ZGxlX3VzZXJfaWQ9MzI3Mjk7IGRsZV9wYXNzd29yZD04OTQxNzFjNmE4ZGFiMThlZTU5NGQ1YzY1MjAwOWEzNTs="),
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )

    private val cfKiller = CloudflareKiller()

    suspend fun loadLinks(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        StreamITALogger.log(TAG, "Avvio CinemaCity per $imdbId...")

        try {
            // 1. Search per IMDB ID (con CloudflareKiller)
            val searchUrl = "$BASE_URL/?do=search&subaction=search&search_start=0&full_search=0&story=$imdbId"
            val searchResponse = app.get(searchUrl, headers = headers, interceptor = cfKiller)
            val searchDoc = Jsoup.parse(searchResponse.text)

            val firstResult = searchDoc.selectFirst("div.dar-short_item > a")
            if (firstResult == null) {
                StreamITALogger.log(TAG, "Nessun risultato per $imdbId")
                return false
            }

            val contentUrl = firstResult.attr("href")
            val fullUrl = if (contentUrl.startsWith("http")) contentUrl else "$BASE_URL$contentUrl"
            StreamITALogger.log(TAG, "Trovato: $fullUrl")

            // 2. Carica la pagina del contenuto (senza CloudflareKiller)
            val pageResponse = app.get(fullUrl, headers = headers)
            val doc = Jsoup.parse(pageResponse.text)

            // 3. Trova il PlayerJS
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

            // 4. Estrai file
            val rawFile = playerJson.opt("file") ?: return false
            val fileArray: JSONArray = when (rawFile) {
                is JSONArray -> rawFile
                is String -> if (rawFile.startsWith("[")) JSONArray(rawFile)
                else JSONArray().put(JSONObject().put("file", rawFile))
                else -> return false
            }

            // 5. Film o Serie TV?
            if (season != null && episode != null) {
                return extractSeriesLinks(fileArray, season, episode, fullUrl, callback)
            } else {
                val movieFile = fileArray.optJSONObject(0)?.optString("file")
                if (!movieFile.isNullOrBlank()) {
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
                    StreamITALogger.log(TAG, "CinemaCity OK")
                    return true
                }
            }
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "CinemaCity fallito: ${e.message}")
        }

        return false
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
