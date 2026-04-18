package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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
        
        // Costruisci URL API
        val apiUrl = if (season != null && episode != null) {
            "$mainUrl/api/tv/$tmdbId/$season/$episode?lang=it"
        } else {
            "$mainUrl/api/movie/$tmdbId?lang=it"
        }
        
        Log.d(TAG, "🌐 API: $apiUrl")
        
        try {
            val apiResponse = app.get(apiUrl, referer = mainUrl)
            
            // Estrai src usando regex (evita problemi JSON)
            val srcPattern = Regex(""""src"\s*:\s*"([^"]+)"""")
            val srcMatch = srcPattern.find(apiResponse.text)
            
            if (srcMatch != null) {
                var embedUrl = srcMatch.groupValues[1].replace("\\/", "/")
                
                // Se non è un URL completo, aggiungi mainUrl
                if (!embedUrl.startsWith("http")) {
                    embedUrl = "$mainUrl$embedUrl"
                }
                
                Log.i(TAG, "✅ Embed URL: $embedUrl")
                
                // PASSA L'EMBED URL A loadExtractor! NON COSTRUIRE PLAYLIST!
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API failed: ${e.message}")
        }
        
        // Fallback: passa URL originale
        loadExtractor(url, mainUrl, subtitleCallback, callback)
    }
}
