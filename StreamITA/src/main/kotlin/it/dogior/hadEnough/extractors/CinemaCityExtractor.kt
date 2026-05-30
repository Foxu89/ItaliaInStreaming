package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.CinemaCityScraper
import org.json.JSONArray
import org.json.JSONObject

class CinemaCityExtractor : ExtractorApi() {
    override val name = "CinemaCity"
    override val mainUrl = "https://cinemacity.cc"
    override val requiresReferer = true

    private val cfKiller = CloudflareKiller()

    companion object {
        private const val TAG = "CinemaCityExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val imdbId = url
            val season = referer?.substringAfter("season=")?.substringBefore("&")?.toIntOrNull()
            val episode = referer?.substringAfter("episode=")?.substringBefore("&")?.toIntOrNull()
            val isTvSeries = season != null

            Log.d(TAG, "Cerco CinemaCity per IMDb: $imdbId, S${season}E${episode}")

            val pageUrl = CinemaCityScraper.resolveViaSitemap(imdbId, isTvSeries) ?: run {
                Log.e(TAG, "Nessun match sitemap per $imdbId")
                return
            }

            Log.d(TAG, "Pagina trovata: $pageUrl")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            )

            val pageDoc = app.get(pageUrl, headers = headers, interceptor = cfKiller).document
            val script = pageDoc.select("script:containsData(atob)")
                .getOrNull(1)
                ?.data()
                ?: run {
                    Log.e(TAG, "Script player non trovato")
                    return
                }

            val playerJson = JSONObject(
                base64Decode(
                    script.substringAfter("atob(\"").substringBefore("\")")
                ).substringAfter("new Playerjs(").substringBeforeLast(");")
            )

            val fileArray = JSONArray(playerJson.getString("file"))
            val firstFile = fileArray.getJSONObject(0)

            if (!firstFile.has("folder")) {
                val fileUrl = firstFile.getString("file")
                val quality = extractQuality(fileUrl)

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "CinemaCity Multi-Audio",
                        url = fileUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to pageUrl,
                            "Origin" to mainUrl
                        )
                    }
                )
                Log.d(TAG, "Link film estratto: $fileUrl")

            } else {
                if (season == null || episode == null) {
                    Log.e(TAG, "Season/Episode mancanti per serie TV")
                    return
                }

                for (i in 0 until fileArray.length()) {
                    val seasonJson = fileArray.getJSONObject(i)
                    val seasonTitle = seasonJson.optString("title", "")
                    val seasonNumber = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(seasonTitle)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?: continue

                    if (seasonNumber != season) continue

                    val episodes = seasonJson.getJSONArray("folder")
                    for (j in 0 until episodes.length()) {
                        val epJson = episodes.getJSONObject(j)
                        val epTitle = epJson.optString("title", "")
                        val episodeNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                            .find(epTitle)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?: continue

                        if (episodeNumber != episode) continue

                        val fileUrl = epJson.getString("file")
                        val quality = extractQuality(fileUrl)

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "CinemaCity S${season}E${episode}",
                                url = fileUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = pageUrl
                                this.quality = quality
                                this.headers = mapOf(
                                    "Referer" to pageUrl,
                                    "Origin" to mainUrl
                                )
                            }
                        )
                        Log.d(TAG, "Link episodio estratto: $fileUrl")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore CinemaCity: ${e.message}")
        }
    }

    private fun extractQuality(url: String): Int {
        return when {
            url.contains("2160p") -> Qualities.P2160.value
            url.contains("1440p") -> Qualities.P1440.value
            url.contains("1080p") -> Qualities.P1080.value
            url.contains("720p") -> Qualities.P720.value
            url.contains("480p") -> Qualities.P480.value
            url.contains("360p") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
