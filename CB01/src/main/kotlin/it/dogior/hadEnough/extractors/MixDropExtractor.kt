package it.dogior.hadEnough.extractors

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class MixDropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "mixdrop.top"
    override val requiresReferer = false

    companion object {
        private const val TAG = "MixDropExtractor"
        private const val TIMEOUT_SECONDS = 15L
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "Getting video from: $url")
        
        val videoId = url.substringAfterLast("/")
        val embedUrl = "https://mixdrop.top/e/$videoId"
        Log.d(TAG, "Loading embed URL: $embedUrl")
        
        // Estrae l'URL del video usando WebView
        val videoUrl = extractWithWebView(embedUrl)
        
        if (videoUrl != null) {
            Log.d(TAG, "Video URL extracted: $videoUrl")
            
            val videoHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
                "Referer" to "https://mixdrop.top/"
            )
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "MixDrop",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = videoHeaders
                    this.referer = "https://mixdrop.top/"
                }
            )
        } else {
            Log.e(TAG, "Failed to extract video URL with WebView")
            
            // Fallback: prova ancora il metodo statico per retrocompatibilità
            extractStatic(url, subtitleCallback, callback)
        }
    }
    
    private suspend fun extractWithWebView(embedUrl: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            var extractedUrl: String? = null
            
            // Handler per eseguire sul thread UI
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                @SuppressLint("SetJavaScriptEnabled")
                val webView = WebView(android.app.Application.ActivityLifecycleCallbacks::class.java.classLoader?.let { 
                    try {
                        val context = android.app.ActivityThread.currentApplication()
                        WebView(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create WebView context")
                        null
                    }
                } ?: run {
                    Log.e(TAG, "WebView creation failed")
                    continuation.resume(null)
                    return@post
                })
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // Blocca immagini per risparmiare banda e velocizzare
                    blockNetworkImage = true
                    blockNetworkLoads = false
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                }
                
                var found = false
                
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url.toString()
                        
                        // Intercetta l'URL del video
                        if (!found && (requestUrl.contains(".mp4") || requestUrl.contains("delivery") || requestUrl.contains("mxcontent"))) {
                            Log.d(TAG, "Intercepted: $requestUrl")
                            found = true
                            extractedUrl = requestUrl
                            
                            // Cleanup e resume
                            Handler(android.os.Looper.getMainLooper()).post {
                                webView.stopLoading()
                                webView.destroy()
                                latch.countDown()
                                continuation.resume(requestUrl)
                            }
                            return null
                        }
                        
                        // Blocca immagini, CSS e font per risparmiare risorse
                        val blockExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".css", ".woff", ".woff2", ".ttf")
                        if (blockExtensions.any { requestUrl.lowercase().contains(it) }) {
                            return WebResourceResponse("text/plain", "UTF-8", null)
                        }
                        
                        return super.shouldInterceptRequest(view, request)
                    }
                    
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "Page loaded: $url")
                        
                        // Forza il click sul pulsante Play
                        view?.evaluateJavascript("""
                            (function() {
                                var playBtn = document.querySelector('.vjs-big-play-button') || 
                                              document.querySelector('[class*="play"]') ||
                                              document.querySelector('button');
                                if (playBtn) playBtn.click();
                                
                                // Prova anche a chiamare play() sul video se esiste
                                var video = document.querySelector('video');
                                if (video) video.play();
                            })();
                        """.trimIndent(), null)
                    }
                }
                
                webView.loadUrl(embedUrl)
                
                // Timeout di sicurezza
                Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!found) {
                        Log.e(TAG, "WebView timeout")
                        webView.stopLoading()
                        webView.destroy()
                        latch.countDown()
                        continuation.resume(null)
                    }
                }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            }
            
            // Aspetta il completamento o timeout
            latch.await(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)
        }
    }
    
    // Fallback statico (il vecchio metodo) per retrocompatibilità
    private suspend fun extractStatic(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val videoId = url.substringAfterLast("/")
            val pageUrl = "https://mixdrop.top/e/$videoId"
            
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                .header("Referer", "https://mixdrop.top/")
                .build()
            
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return
            
            // Cerca MDCore pattern
            val mdcoreRegex = Regex("""MDCore\.[a-zA-Z0-9]+\s*=\s*["'](//[^"']+\.mp4[^"']*)["']""")
            val mdcoreMatch = mdcoreRegex.find(html)
            
            if (mdcoreMatch != null) {
                var videoUrl = mdcoreMatch.groupValues[1]
                if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "MixDrop (Static)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://mixdrop.top/"
                    }
                )
                return
            }
            
            Log.e(TAG, "Static extraction also failed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Static extraction error: ${e.message}")
        }
    }
}
