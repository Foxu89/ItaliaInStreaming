package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject

class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "https://vixsrc.to"
    override val name = "VixSrc"
    override val requiresReferer = true
    val TAG = "VixSrcExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "🎬 URL: $url")
        
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
        val seasonEpisode = Regex("""/tv/\d+/(\d+)/(\d+)""").find(url)
        val season = seasonEpisode?.groupValues?.get(1)
        val episode = seasonEpisode?.groupValues?.get(2)
        
        if (tmdbId == null) {
            Log.e(TAG, "❌ Cannot extract TMDB ID")
            return
        }
        
        // METODO 1: API
        try {
            val apiUrl = if (season != null && episode != null) {
                "$mainUrl/api/tv/$tmdbId/$season/$episode?lang=it"
            } else {
                "$mainUrl/api/movie/$tmdbId?lang=it"
            }
            
            Log.d(TAG, "🌐 API: $apiUrl")
            val apiResponse = app.get(apiUrl, referer = mainUrl)
            val srcPattern = Regex(""""src"\s*:\s*"([^"]+)"""")
            val srcMatch = srcPattern.find(apiResponse.text)
            
            if (srcMatch != null) {
                val embedPath = srcMatch.groupValues[1].replace("\\/", "/")
                val playlistUrl = extractFromPath(embedPath)
                
                if (playlistUrl != null) {
                    Log.i(TAG, "✅ Using API method")
                    // USA loadExtractor invece di callback diretto!
                    loadExtractor(playlistUrl, mainUrl, subtitleCallback, callback)
                    return
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ API failed: ${e.message}")
        }
        
        // METODO 2: Direct HTML
        try {
            val headers = mapOf(
                "Accept" to "*/*",
                "Referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0"
            )
            
            val response = app.get(url, headers = headers)
            val html = response.text
            
            val pattern = Regex("""window\.masterPlaylist\s*=\s*(\{[^}]+\})""")
            val match = pattern.find(html)
            
            if (match != null) {
                var jsonStr = match.groupValues[1]
                jsonStr = jsonStr.replace(Regex(""",(\s*[}\]])"""), "$1")
                jsonStr = jsonStr.replace("'", "\"")
                
                val json = JSONObject(jsonStr)
                val playlistUrl = json.getString("url")
                val params = json.getJSONObject("params")
                val token = params.getString("token")
                val expires = params.getString("expires")
                
                val finalUrl = if (playlistUrl.contains("?b")) {
                    playlistUrl.replace("?b:1", "?b=1") + "&token=$token&expires=$expires"
                } else {
                    "$playlistUrl?token=$token&expires=$expires"
                }
                
                Log.i(TAG, "✅ Using direct method")
                // USA loadExtractor!
                loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Direct method failed: ${e.message}")
        }
    }
    
    private fun extractFromPath(embedPath: String): String? {
        val embedId = Regex("""/embed/(\d+)""").find(embedPath)?.groupValues?.get(1)
        val token = Regex("""[?&]token=([^&]+)""").find(embedPath)?.groupValues?.get(1)
        val expires = Regex("""[?&]expires=([^&]+)""").find(embedPath)?.groupValues?.get(1)
        
        return if (embedId != null && token != null && expires != null) {
            "$mainUrl/playlist/$embedId?token=$token&expires=$expires"
        } else null
    }
}
