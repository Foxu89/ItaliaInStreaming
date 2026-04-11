package it.dogior.hadEnough.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
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
        
        // Ottiene il Context dell'applicazione tramite riflessione
        private fun getApplicationContext(): android.content.Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
                val activityThread = currentActivityThreadMethod.invoke(null)
                val getApplicationMethod = activityThreadClass.getMethod("getApplication")
                getApplicationMethod.invoke(activityThread) as? Application
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Application context: ${e.message}")
                null
            }
        }
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
        }
    }
    
    private suspend fun extractWithWebView(embedUrl: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            var extractedUrl: String? = null
            
            Handler(Looper.getMainLooper()).post {
                try {
                    val context = getApplicationContext() ?: run {
                        Log.e(TAG, "No context available")
                        continuation.resume(null)
                        return@post
                    }
                    
                    @SuppressLint("SetJavaScriptEnabled")
                    val webView = WebView(context)
                    
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
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
                            
                            if (!found && (requestUrl.contains(".mp4") || requestUrl.contains("delivery") || requestUrl.contains("mxcontent"))) {
                                Log.d(TAG, "Intercepted: $requestUrl")
                                found = true
                                extractedUrl = requestUrl
                                
                                Handler(Looper.getMainLooper()).post {
                                    webView.stopLoading()
                                    webView.destroy()
                                    latch.countDown()
                                    continuation.resume(requestUrl)
                                }
                                return null
                            }
                            
                            val blockExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".css", ".woff", ".woff2", ".ttf")
                            if (blockExtensions.any { requestUrl.lowercase().contains(it) }) {
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }
                            
                            return super.shouldInterceptRequest(view, request)
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d(TAG, "Page loaded: $url")
                            
                            view?.evaluateJavascript("""
                                (function() {
                                    var playBtn = document.querySelector('.vjs-big-play-button') || 
                                                  document.querySelector('[class*="play"]') ||
                                                  document.querySelector('button');
                                    if (playBtn) playBtn.click();
                                    
                                    var video = document.querySelector('video');
                                    if (video) video.play();
                                })();
                            """.trimIndent(), null)
                        }
                    }
                    
                    webView.loadUrl(embedUrl)
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            Log.e(TAG, "WebView timeout")
                            webView.stopLoading()
                            webView.destroy()
                            latch.countDown()
                            continuation.resume(null)
                        }
                    }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                    
                } catch (e: Exception) {
                    Log.e(TAG, "WebView error: ${e.message}")
                    continuation.resume(null)
                }
            }
            
            latch.await(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)
        }
    }
}
