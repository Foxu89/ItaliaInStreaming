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
        
        // Estrai TMDB ID o IMDb ID dall'URL
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
        val imdbId = Regex("""/tt(\d+)""").find(url)?.groupValues?.get(1)
        val seasonEpisode = Regex("""/tv/\d+/(\d+)/(\d+)""").find(url)
        val season = seasonEpisode?.groupValues?.get(1)
        val episode = seasonEpisode?.groupValues?.get(2)
        
        Log.d(TAG, "📊 TMDB: $tmdbId, IMDb: $imdbId, S${season}E${episode}")
        
        // Costruisci l'URL dell'API
        val apiUrl = when {
            imdbId != null && season != null && episode != null -> {
                "https://vixsrc.to/api/tv/tt$imdbId/$season/$episode?lang=it"
            }
            imdbId != null -> {
                "https://vixsrc.to/api/movie/tt$imdbId?lang=it"
            }
            tmdbId != null && season != null && episode != null -> {
                "https://vixsrc.to/api/tv/$tmdbId/$season/$episode?lang=it"
            }
            tmdbId != null -> {
                "https://vixsrc.to/api/movie/$tmdbId?lang=it"
            }
            else -> throw Exception("❌ Cannot extract ID from URL")
        }
        
        Log.d(TAG, "🌐 API URL: $apiUrl")
        
        try {
            // Chiamata API
            val response = app.get(apiUrl, referer = mainUrl)
            val json = JSONObject(response.text)
            
            Log.d(TAG, "📄 API Response: ${response.text.take(200)}")
            
            // Estrai src
            val embedPath = json.optString("src", "")
            
            if (embedPath.isEmpty()) {
                Log.e(TAG, "❌ 'src' field not found in API response")
                throw Exception("No src in API response")
            }
            
            val embedUrl = if (embedPath.startsWith("http")) {
                embedPath
            } else {
                "$mainUrl$embedPath"
            }
            
            Log.d(TAG, "🔗 Embed URL: $embedUrl")
            
            // Ora estrai il masterPlaylist dall'embed
            val playlistUrl = getPlaylistFromEmbed(embedUrl)
            
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
            Log.e(TAG, "❌ API method failed: ${e.message}")
            
            // Fallback: prova il metodo diretto (quello vecchio)
            Log.d(TAG, "🔄 Trying direct method...")
            val playlistUrl = getPlaylistDirect(url)
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "VixSrc (Direct)",
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
        }
    }
    
    private suspend fun getPlaylistFromEmbed(embedUrl: String): String {
        Log.d(TAG, "📥 Fetching embed: $embedUrl")
        
        val headers = mapOf(
            "Accept" to "*/*",
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0"
        )
        
        val response = app.get(embedUrl, headers = headers)
        val html = response.text
        
        // Cerca window.masterPlaylist DIRETTAMENTE
        val pattern = Regex("""window\.masterPlaylist\s*=\s*(\{[^}]+\})""")
        val match = pattern.find(html) ?: throw Exception("masterPlaylist not found in embed")
        
        val jsonStr = match.groupValues[1]
        val json = JSONObject(jsonStr)
        
        val playlistUrl = json.getString("url")
        val params = json.getJSONObject("params")
        val token = params.getString("token")
        val expires = params.getString("expires")
        
        var finalUrl = if (playlistUrl.contains("?b")) {
            playlistUrl.replace("?b:1", "?b=1") + "&token=$token&expires=$expires"
        } else {
            "$playlistUrl?token=$token&expires=$expires"
        }
        
        if (html.contains("window.canPlayFHD = true")) {
            finalUrl += "&h=1"
        }
        
        return finalUrl
    }
    
    private suspend fun getPlaylistDirect(url: String): String {
        Log.d(TAG, "📥 Direct fetch: $url")
        
        val headers = mapOf(
            "Accept" to "*/*",
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0"
        )
        
        val response = app.get(url, headers = headers)
        val html = response.text
        
        // Cerca window.masterPlaylist
        val pattern = Regex("""window\.masterPlaylist\s*=\s*(\{[^}]+\})""")
        val match = pattern.find(html) ?: throw Exception("masterPlaylist not found")
        
        val jsonStr = match.groupValues[1]
        val json = JSONObject(jsonStr)
        
        val playlistUrl = json.getString("url")
        val params = json.getJSONObject("params")
        val token = params.getString("token")
        val expires = params.getString("expires")
        
        var finalUrl = if (playlistUrl.contains("?b")) {
            playlistUrl.replace("?b:1", "?b=1") + "&token=$token&expires=$expires"
        } else {
            "$playlistUrl?token=$token&expires=$expires"
        }
        
        if (html.contains("window.canPlayFHD = true")) {
            finalUrl += "&h=1"
        }
        
        return finalUrl
    }
}
