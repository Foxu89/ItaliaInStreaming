package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MixDropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "mixdrop.top"
    override val requiresReferer = false

    companion object {
        private const val TAG = "MixDropExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "========== START ==========")
        Log.d(TAG, "Input URL: $url")
        
        try {
            val videoId = url.substringAfterLast("/")
            Log.d(TAG, "Video ID: $videoId")
            
            // STEP 1: Prendi la pagina HTML con il client OkHttp (stessa sessione)
            val pageUrl = "https://mixdrop.top/e/$videoId"
            Log.d(TAG, "Page URL: $pageUrl")
            
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            
            val pageRequest = Request.Builder()
                .url(pageUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .addHeader("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            
            val pageResponse = client.newCall(pageRequest).execute()
            val html = pageResponse.body?.string() ?: ""
            Log.d(TAG, "HTML length: ${html.length}")
            
            // STEP 2: Estrai l'URL del video
            val videoUrl = extractVideoUrlFromHtml(html)
            Log.d(TAG, "Extracted video URL: $videoUrl")
            
            if (videoUrl != null) {
                // STEP 3: Headers per il video - Referer = pageUrl (coerente!)
                val videoHeaders = mutableMapOf(
                    "Referer" to pageUrl,  // ← FIX! Usa pageUrl, non m1xdrop.net!
                    "Origin" to "https://mixdrop.top",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                    "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                    "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Range" to "bytes=0-",
                    "Connection" to "keep-alive"
                )
                
                Log.d(TAG, "Video headers: $videoHeaders")
                Log.d(TAG, "Final URL: $videoUrl")
                
                // Usa lo stesso client per mantenere i cookie
                val videoRequest = Request.Builder()
                    .url(videoUrl)
                    .headers(okhttp3.Headers.of(videoHeaders))
                    .build()
                
                val videoResponse = client.newCall(videoRequest).execute()
                Log.d(TAG, "Video response code: ${videoResponse.code}")
                
                if (videoResponse.code == 200 || videoResponse.code == 206) {
                    Log.d(TAG, "✅ Video URL is valid!")
                } else {
                    Log.e(TAG, "❌ Video URL returned ${videoResponse.code}")
                }
                videoResponse.close()
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "MixDrop",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.headers = videoHeaders
                        this.referer = pageUrl  // ← FIX! Referer coerente!
                    }
                )
            } else {
                Log.e(TAG, "❌ Failed to extract video URL")
            }
            client.dispatcher.executorService.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error: ${e.message}")
            e.printStackTrace()
        }
        Log.d(TAG, "========== END ==========")
    }
    
    private fun extractVideoUrlFromHtml(html: String): String? {
        try {
            // Pattern per l'URL completo
            val urlPattern = Regex("""(https?://[a-z0-9]+\.mxcontent\.net/v2/[a-f0-9]+\.mp4\?s=[^&]+&e=[^&]+&_t=[^&"]+)""")
            val urlMatch = urlPattern.find(html)
            if (urlMatch != null) {
                var videoUrl = urlMatch.groupValues[1]
                videoUrl = videoUrl.replace(Regex("[\\s\\n\\r]"), "")
                Log.d(TAG, "✅ Found direct URL: $videoUrl")
                return videoUrl
            }
            
            // Costruisci dai token
            val vserver = Regex("""vserver\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.trim()
            var vfile = Regex("""vfile\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.trim()
            val tokenS = Regex("""s\s*=\s*([^&\s"]+)""").find(html)?.groupValues?.get(1)?.trim()
            val tokenE = Regex("""e\s*=\s*([^&\s"]+)""").find(html)?.groupValues?.get(1)?.trim()
            val tokenT = Regex("""_t\s*=\s*([^&\s"]+)""").find(html)?.groupValues?.get(1)?.trim()
            
            Log.d(TAG, "Parsed: vserver=$vserver, vfile=$vfile, s=$tokenS, e=$tokenE, _t=$tokenT")
            
            if (!vserver.isNullOrEmpty() && !vfile.isNullOrEmpty()) {
                vfile = vfile.replace(Regex("\\.mp4$"), "")
                return "https://${vserver}.mxcontent.net/v2/${vfile}.mp4?s=$tokenS&e=$tokenE&_t=$tokenT"
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Extraction error: ${e.message}")
        }
        return null
    }
}
