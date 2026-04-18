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
        
        val resolver = WebViewResolver(
            interceptUrl = Regex("""playlist.*token"""),
            useOkhttp = true,
            timeout = 15000L
        )
        
        try {
            Log.d(TAG, "🌐 Loading WebView...")
            val response = app.get(
                url = url,
                referer = mainUrl,
                interceptor = resolver,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
            
            val m3u8Url = response.url
            
            if (m3u8Url.contains("playlist") && m3u8Url.contains("token")) {
                Log.i(TAG, "✅ Sniffed: $m3u8Url")
                M3u8Helper.generateM3u8(name, m3u8Url, mainUrl).forEach(callback)
            } else {
                Log.e(TAG, "❌ Not a playlist: $m3u8Url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed: ${e.message}")
        }
    }
}
