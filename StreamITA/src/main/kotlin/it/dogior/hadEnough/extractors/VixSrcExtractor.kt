package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
                
                // Restituisci SOLO il master playlist (non le singole qualità)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "VixSrc",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8,
                        quality = Qualities.P1080.value  // Il master contiene tutte le qualità
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to mainUrl
                        )
                    }
                )
            } else {
                Log.e(TAG, "❌ Not a playlist: $m3u8Url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed: ${e.message}")
        }
    }
}
