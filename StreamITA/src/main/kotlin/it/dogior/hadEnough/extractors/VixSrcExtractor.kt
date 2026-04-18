package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "https://vixsrc.to"
    override val name = "VixSrc"
    override val requiresReferer = true
    private val TAG = "VixSrcExtractor"

    private val headers = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Connection" to "keep-alive",
        "Referer" to "https://vixsrc.to/",
        "Origin" to "https://vixsrc.to",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
        val seasonEpisode = Regex("""/tv/\d+/(\d+)/(\d+)""").find(url)
        val season = seasonEpisode?.groupValues?.get(1)
        val episode = seasonEpisode?.groupValues?.get(2)
        
        if (tmdbId == null) {
            Log.e(TAG, "Cannot extract TMDB ID")
            return
        }
        
        val apiUrl = if (season != null && episode != null) {
            "$mainUrl/api/tv/$tmdbId/$season/$episode?lang=it"
        } else {
            "$mainUrl/api/movie/$tmdbId?lang=it"
        }
        
        Log.d(TAG, "API: $apiUrl")
        
        try {
            val apiResponse = app.get(apiUrl, headers = headers)
            val json = JSONObject(apiResponse.text)
            val src = json.optString("src", "")
            
            if (src.isNotEmpty()) {
                val embedUrl = if (src.startsWith("http")) src else "$mainUrl$src"
                Log.d(TAG, "Embed: $embedUrl")
                
                val embedResponse = app.get(embedUrl, headers = headers)
                val html = embedResponse.text
                
                val pattern = Regex("""window\.masterPlaylist\s*=\s*(\{[^}]+\})""")
                val match = pattern.find(html)
                
                if (match != null) {
                    var jsonStr = match.groupValues[1]
                    jsonStr = jsonStr.replace(Regex(""",(\s*[}\]])"""), "$1")
                    jsonStr = jsonStr.replace("'", "\"")
                    
                    val data = JSONObject(jsonStr)
                    val playlistUrl = data.getString("url")
                    val params = data.getJSONObject("params")
                    val token = params.getString("token")
                    val expires = params.getString("expires")
                    
                    val finalUrl = if (playlistUrl.contains("?b")) {
                        playlistUrl.replace("?b:1", "?b=1") + "&token=$token&expires=$expires"
                    } else {
                        "$playlistUrl?token=$token&expires=$expires"
                    }
                    
                    Log.d(TAG, "M3U8: $finalUrl")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "VixSrc",
                            url = finalUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.headers = headers
                        }
                    )
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }
}
