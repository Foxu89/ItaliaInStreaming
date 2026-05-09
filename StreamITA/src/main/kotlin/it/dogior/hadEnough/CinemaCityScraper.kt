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

    /**
     * Cerca un film/serie su CinemaCity per IMDB ID
     * e restituisce i link video estratti.
     */
    suspend fun loadLinks(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        StreamITALogger.log(TAG, "Avvio CinemaCity per $imdbId...")

        try {
            // 1. Search per IMDB ID
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

            // 2. Carica la pagina del contenuto
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

            // 4. Estrai i link video
            val rawFile = playerJson.opt("file") ?: run {
                StreamITALogger.log(TAG, "Campo 'file' mancante")
                return false
            }

            val fileArray: JSONArray = when (rawFile) {
                is JSONArray -> rawFile
                is String -> {
                    if (rawFile.startsWith("[")) JSONArray(rawFile)
                    else JSONArray().put(JSONObject().put("file", rawFile))
                }
                else -> return false
            }

            // 5. Se è una serie TV, cerca la stagione/episodio
            if (season != null && episode != null) {
                extractSeriesLinks(fileArray, season, episode, fullUrl, callback)
            } else {
                // Film
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
                    StreamITALogger.log(TAG, "CinemaCity OK: link film trovato")
                    return true
                }
            }
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "CinemaCity fallito: ${e.message}")
        }

        return false
    }

    private fun extractSeriesLinks(
        fileArray: JSONArray,
        season: Int,
        episode: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val seasonRegex = Regex("Season\\s*(\\d+)", RegexOption.IGNORE
