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
        private const val TIMEOUT_SECONDS = 30L
        
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
        
        // Lista di estensioni da IGNORARE completamente
        private val IGNORED_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".bmp", ".ico",
            ".css", ".woff", ".woff2", ".ttf", ".eot",
            ".js", ".json", ".xml", ".txt"
        )
        
        // Parole chiave che identificano un VIDEO
        private val VIDEO_KEYWORDS = listOf(
            ".mp4", ".m3u8", ".ts", ".mkv", ".webm",
            "video", "stream", "delivery", "v2/", "playlist"
        )
        
        private fun isVideoUrl(url: String): Boolean {
            val lowerUrl = url.lowercase()
            
            // Se è un'immagine o risorsa statica, IGNORA
            if (IGNORED_EXTENSIONS.any { lowerUrl.contains(it) }) {
                return false
            }
            
            // Deve contenere almeno una keyword video
            return VIDEO_KEYWORDS.any { lowerUrl.contains(it) }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "Getting video from: $url")
        
        val videoId = url.substringAfterLast("/").trim()
        val embedUrl = "https://mixdrop.top/e/$videoId"
        Log.d(TAG, "Loading embed URL: $embedUrl")
        
        val videoUrl = extractWithWebView(embedUrl, videoId)
        
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
    
    private suspend fun extractWithWebView(embedUrl: String, videoId: String): String? {
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
                        blockNetworkImage = true  // Blocca immagini per evitare falsi positivi
                        blockNetworkLoads = false
                        javaScriptCanOpenWindowsAutomatically = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                    }
                    
                    var found = false
                    var pageLoaded = false
                    val interceptedUrls = mutableSetOf<String>()  // Per debug
                    
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url.toString()
                            
                            // Logga tutte le richieste per debug (solo prime 10)
                            if (interceptedUrls.size < 10) {
                                interceptedUrls.add(requestUrl)
                                Log.d(TAG, "Request: $requestUrl")
                            }
                            
                            // Usa il filtro intelligente
                            if (!found && isVideoUrl(requestUrl)) {
                                Log.d(TAG, "✅ VIDEO FOUND: $requestUrl")
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
                            
                            // Blocca immagini per performance
                            if (requestUrl.contains(".jpg") || requestUrl.contains(".png") || requestUrl.contains(".webp")) {
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }
                            
                            return super.shouldInterceptRequest(view, request)
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            pageLoaded = true
                            Log.d(TAG, "Page loaded: $url")
                            
                            // Script per forzare il play
                            view?.evaluateJavascript("""
                                (function() {
                                    console.log('MixDrop Extractor: Forcing play...');
                                    
                                    // Muta e forza play su tutti i video
                                    var videos = document.querySelectorAll('video');
                                    videos.forEach(function(v) {
                                        v.muted = true;
                                        v.play().catch(e => console.log('Play error:', e));
                                    });
                                    
                                    // Clicca qualsiasi pulsante di play
                                    var selectors = [
                                        '.vjs-big-play-button',
                                        '.jw-icon-playback',
                                        '.plyr__control--overlaid',
                                        '[class*="play"]',
                                        'button[aria-label*="Play"]',
                                        '.play-button'
                                    ];
                                    
                                    selectors.forEach(function(sel) {
                                        var el = document.querySelector(sel);
                                        if (el) {
                                            el.click();
                                            console.log('Clicked:', sel);
                                        }
                                    });
                                    
                                    // Se c'è un iframe, prova a interagire
                                    var iframe = document.querySelector('iframe');
                                    if (iframe && iframe.src) {
                                        console.log('Iframe found:', iframe.src);
                                    }
                                })();
                            """.trimIndent(), null)
                            
                            // Riprova dopo 2 secondi
                            Handler(Looper.getMainLooper()).postDelayed({
                                view?.evaluateJavascript("""
                                    document.querySelectorAll('video').forEach(v => { v.muted = true; v.play(); });
                                    document.querySelector('[class*="play"]')?.click();
                                """.trimIndent(), null)
                            }, 2000)
                        }
                    }
                    
                    webView.loadUrl(embedUrl)
                    
                    // Timeout
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            Log.e(TAG, "WebView timeout - Intercepted URLs: ${interceptedUrls.joinToString(", ")}")
                            
                            // Prova URL alternativo
                            val altUrl = embedUrl.replace("mixdrop.top", "mixdrop.click")
                                .replace("m1xdrop.click", "mixdrop.click")
                            
                            if (altUrl != embedUrl) {
                                Log.d(TAG, "Trying alternative: $altUrl")
                                webView.loadUrl(altUrl)
                                
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!found) {
                                        webView.stopLoading()
                                        webView.destroy()
                                        latch.countDown()
                                        continuation.resume(null)
                                    }
                                }, 12000)
                            } else {
                                webView.stopLoading()
                                webView.destroy()
                                latch.countDown()
                                continuation.resume(null)
                            }
                        }
                    }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                    
                } catch (e: Exception) {
                    Log.e(TAG, "WebView error: ${e.message}", e)
                    continuation.resume(null)
                }
            }
            
            latch.await(TIMEOUT_SECONDS + 15, TimeUnit.SECONDS)
        }
    }
}
