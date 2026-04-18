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
    val TAG = "VixSrcExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "🎬 URL: $url")
        
        // Estrai TMDB ID
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
        val seasonEpisode = Regex("""/tv/\d+/(\d+)/(\d+)""").find(url)
        val season = seasonEpisode?.groupValues?.get(1)
        val episode = seasonEpisode?.groupValues?.get(2)
        
        if (tmdbId == null) {
            Log.e(TAG, "❌ Cannot extract TMDB ID from URL")
            return
        }
        
        Log.d(TAG, "📊 TMDB: $tmdbId, S${season}E${episode}")
        
        // Costruisci URL API
        val apiUrl = if (season != null && episode != null) {
            "$mainUrl/api/tv/$tmdbId/$season/$episode?lang=it"
        } else {
            "$mainUrl/api/movie/$tmdbId?lang=it"
        }
        
        Log.d(TAG, "🌐 API URL: $apiUrl")
        
        try {
            // Chiamata API
            val response = app.get(apiUrl, referer = mainUrl)
            val responseText = response.text
            Log.d(TAG, "📄 API Response: ${responseText.take(300)}")
            
            // Estrai src usando regex (per evitare problemi di JSON malformato)
            val srcPattern = Regex(""""src"\s*:\s*"([^"]+)"""")
            val srcMatch = srcPattern.find(responseText)
            
            if (srcMatch == null) {
                Log.e(TAG, "❌ 'src' not found in API response")
                return
            }
            
            val embedPath = srcMatch.groupValues[1].replace("\\/", "/")
            
            // Costruisci URL finale del playlist
            val playlistUrl = buildPlaylistUrl(embedPath)
            
            Log.i(TAG, "✅ FINAL M3U8: $playlistUrl")
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "VixSrc",
                    url = playlistUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to mainUrl
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed: ${e.message}")
        }
    }
    
    private suspend fun buildPlaylistUrl(embedPath: String): String {
        Log.d(TAG, "🔧 Building playlist from: $embedPath")
        
        // Estrai ID dell'embed (es. /embed/695389)
        val embedId = Regex("""/embed/(\d+)""").find(embedPath)?.groupValues?.get(1)
        
        // Estrai parametri dall'URL
        val token = Regex("""[?&]token=([^&]+)""").find(embedPath)?.groupValues?.get(1)
        val expires = Regex("""[?&]expires=([^&]+)""").find(embedPath)?.groupValues?.get(1)
        
        if (embedId != null && token != null && expires != null) {
            Log.d(TAG, "   🆔 ID: $embedId")
            Log.d(TAG, "   🔑 Token: ${token.take(20)}...")
            Log.d(TAG, "   ⏰ Expires: $expires")
            
            return "$mainUrl/playlist/$embedId?token=$token&expires=$expires"
        }
        
        // Fallback: se non trova i parametri, prova a fetchare l'embed
        Log.d(TAG, "⚠️ Parameters not found in URL, fetching embed...")
        
        val embedUrl = if (embedPath.startsWith("http")) {
            embedPath
        } else {
            "$mainUrl$embedPath"
        }
        
        Log.d(TAG, "📥 Fetching embed: $embedUrl")
        
        val headers = mapOf(
            "Accept" to "*/*",
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0"
        )
        
        val response = app.get(embedUrl, headers = headers)
        val html = response.text
        
        // Cerca window.masterPlaylist
        val pattern = Regex("""window\.masterPlaylist\s*=\s*(\{[^}]+\})""")
        val match = pattern.find(html) ?: throw Exception("masterPlaylist not found")
        
        // Pulisci il JSON (rimuovi virgole finali)
        var jsonStr = match.groupValues[1]
        jsonStr = jsonStr.replace(Regex(""",(\s*[}\]])"""), "$1")
        jsonStr = jsonStr.replace("'", "\"")
        
        val json = JSONObject(jsonStr)
        val playlistUrl = json.getString("url")
        val params = json.getJSONObject("params")
        val fallbackToken = params.getString("token")
        val fallbackExpires = params.getString("expires")
        
        return if (playlistUrl.contains("?b")) {
            playlistUrl.replace("?b:1", "?b=1") + "&token=$fallbackToken&expires=$fallbackExpires"
        } else {
            "$playlistUrl?token=$fallbackToken&expires=$fallbackExpires"
        }
    }
}
