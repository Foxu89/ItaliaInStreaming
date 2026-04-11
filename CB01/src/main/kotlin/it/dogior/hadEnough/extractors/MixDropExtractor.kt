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
        private const val TIMEOUT_SECONDS = 25L  // Aumentato
        
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
        
        val videoId = url.substringAfterLast("/").trim()
        // Prova sia .top che .click (il server fa redirect)
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
                        blockNetworkImage = false  // Alcuni player hanno bisogno delle immagini per funzionare
                        blockNetworkLoads = false
                        javaScriptCanOpenWindowsAutomatically = true
                        mediaPlaybackRequiresUserGesture = false  // IMPORTANTE: permette autoplay
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                    }
                    
                    var found = false
                    var pageLoaded = false
                    
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url.toString()
                            
                            // Intercetta qualsiasi URL che sembra un video
                            if (!found && (
                                requestUrl.contains(".mp4") || 
                                requestUrl.contains(".m3u8") ||
                                requestUrl.contains("delivery") || 
                                requestUrl.contains("mxcontent") ||
                                requestUrl.contains("stream") ||
                                (requestUrl.contains("mixdrop") && requestUrl.contains("video"))
                            )) {
                                Log.d(TAG, "Intercepted video URL: $requestUrl")
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
                            
                            return super.shouldInterceptRequest(view, request)
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            pageLoaded = true
                            Log.d(TAG, "Page loaded: $url")
                            
                            // Script di click più aggressivo
                            view?.evaluateJavascript("""
                                (function() {
                                    function clickAllButtons() {
                                        // Selettori più completi
                                        var selectors = [
                                            '.vjs-big-play-button',
                                            '.play-button',
                                            '.play-btn',
                                            '[class*="play"]',
                                            '[id*="play"]',
                                            'button',
                                            '.video-js button',
                                            '.jw-icon-playback',
                                            '.plyr__control--overlaid',
                                            '[aria-label="Play"]',
                                            '[title="Play"]'
                                        ];
                                        
                                        selectors.forEach(function(sel) {
                                            var el = document.querySelector(sel);
                                            if (el) {
                                                // Simula click reale
                                                el.dispatchEvent(new MouseEvent('click', {
                                                    view: window,
                                                    bubbles: true,
                                                    cancelable: true
                                                }));
                                                console.log('Clicked: ' + sel);
                                            }
                                        });
                                        
                                        // Prova a chiamare play() su tutti i video
                                        var videos = document.querySelectorAll('video');
                                        videos.forEach(function(v) {
                                            v.play().catch(e => console.log('Play error:', e));
                                            v.muted = true;  // Mute per permettere autoplay
                                            v.play();
                                        });
                                        
                                        // Cerca iframe e tenta di cliccare al loro interno
                                        var iframes = document.querySelectorAll('iframe');
                                        iframes.forEach(function(iframe) {
                                            try {
                                                var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                                                var btn = iframeDoc.querySelector('button, .play-button, [class*="play"]');
                                                if (btn) btn.click();
                                            } catch(e) {}
                                        });
                                    }
                                    
                                    // Esegui subito
                                    clickAllButtons();
                                    
                                    // Riprova dopo 1 e 3 secondi
                                    setTimeout(clickAllButtons, 1000);
                                    setTimeout(clickAllButtons, 3000);
                                })();
                            """.trimIndent(), null)
                        }
                    }
                    
                    webView.loadUrl(embedUrl)
                    
                    // Timeout più lungo
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            Log.e(TAG, "WebView timeout - pageLoaded: $pageLoaded")
                            
                            // Ultimo tentativo: prova l'URL alternativo .click
                            if (embedUrl.contains("mixdrop.top")) {
                                val altUrl = embedUrl.replace("mixdrop.top", "m1xdrop.click")
                                Log.d(TAG, "Trying alternative URL: $altUrl")
                                webView.loadUrl(altUrl)
                                
                                // Nuovo timeout per l'URL alternativo
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!found) {
                                        webView.stopLoading()
                                        webView.destroy()
                                        latch.countDown()
                                        continuation.resume(null)
                                    }
                                }, 10000)
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
