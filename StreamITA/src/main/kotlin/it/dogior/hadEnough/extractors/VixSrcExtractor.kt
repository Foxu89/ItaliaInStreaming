package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

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
        
        // Costruisci URL embed
        val embedUrl = if (season != null && episode != null) {
            "$mainUrl/embed/tv/$tmdbId/$season/$episode"
        } else {
            "$mainUrl/embed/movie/$tmdbId"
        }
        
        Log.d(TAG, "🔗 Embed URL: $embedUrl")
        
        // WebViewResolver per intercettare playlist/token/m3u8
        val resolver = WebViewResolver(
            interceptUrl = Regex("""playlist|/playlist/|\?token=|master\.m3u8|\.m3u8"""),
            useOkhttp = true,
            timeout = 15000L
        )
        
        try {
            Log.d(TAG, "🌐 Loading WebView...")
            val response = app.get(
                url = embedUrl,
                referer = mainUrl,
                interceptor = resolver
            )
            
            val interceptedUrl = response.url
            
            Log.i(TAG, "✅ Intercepted: $interceptedUrl")
            
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = interceptedUrl,
                referer = mainUrl
            ).forEach(callback)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ WebView failed: ${e.message}")
        }
    }
}
