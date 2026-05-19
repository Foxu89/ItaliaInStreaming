package it.dogior.hadEnough.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.StreamITALogger.log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class VidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "v.vidxgo.co"
    override val requiresReferer = false

    companion object {
        private const val TIMEOUT_SECONDS = 35L
        private const val REFERER = "https://altadefinizione.you/"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/148.0.0.0 Safari/537.36"
        
        private val CUSTOM_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Priority" to "u=0, i",
            "Referer" to REFERER,
            "Sec-Ch-Ua" to "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Storage-Access" to "active",
            "Upgrade-Insecure-Requests" to "1",
            "User-Agent" to USER_AGENT
        )
        
        private fun getApplicationContext(): Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
                val activityThread = currentActivityThreadMethod.invoke(null)
                val getApplicationMethod = activityThreadClass.getMethod("getApplication")
                getApplicationMethod.invoke(activityThread) as? Application
            } catch (e: Exception) {
                Log.e("VidxGoExtractor", "Failed to get Application context: ${e.message}")
                null
            }
        }
        
        private fun isVideoUrl(url: String): Boolean {
            val lowerUrl = url.lowercase()
            
            if (lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") || 
                lowerUrl.contains(".png") || lowerUrl.contains(".webp") || 
                lowerUrl.contains(".gif") || lowerUrl.contains(".css") ||
                lowerUrl.contains(".js") || lowerUrl.contains("favicon") ||
                lowerUrl.contains("poster") || lowerUrl.contains("backdrop")) {
                return false
            }
            
            return lowerUrl.contains("master.m3u8") ||
                   lowerUrl.contains(".m3u8") ||
                   (lowerUrl.contains("d2b.your")) ||
                   (lowerUrl.contains("media-") && lowerUrl.contains("your"))
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // ============================================================
        // ESTRAZIONE ID - Supporta tutti i formati:
        // - "tt1375666"
        // - "1375666"  
        // - "https://v.vidxgo.co/1375666"
        // - "https://v.vidxgo.co/1375666/1/1"
        // ============================================================
        val regex = Regex("""(\d+)""")
        val match = regex.find(url)
        val rawId = match?.value
        
        if (rawId == null) {
            log("VidxGo", "❌ Nessun ID numerico trovato in: $url")
            return
        }
        
        // Controlla se l'URL contiene già stagione/episodio
        val seasonEpisodeMatch = Regex("""/(\d+)/(\d+)$""").find(url)
        val targetUrl = if (seasonEpisodeMatch != null) {
            // Serie TV: mantieni stagione/episodio
            "https://v.vidxgo.co/$rawId/${seasonEpisodeMatch.groupValues[1]}/${seasonEpisodeMatch.groupValues[2]}"
        } else {
            // Film
            "https://v.vidxgo.co/$rawId"
        }
        
        log("VidxGo", "🎬 ID: $rawId → URL: $targetUrl")
        
        val videoUrl = extractWithWebView(targetUrl)

        if (videoUrl != null) {
            log("VidxGo", "🎯🎯🎯 M3U8 TROVATO: $videoUrl")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "VidxGo",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to REFERER,
                        "Origin" to "https://v.vidxgo.co",
                        "Accept" to "*/*"
                    )
                    this.referer = REFERER
                }
            )
        } else {
            log("VidxGo", "❌ Nessun M3U8 trovato dopo ${TIMEOUT_SECONDS}s")
        }
    }
    
    private suspend fun extractWithWebView(targetUrl: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            var found = false
            
            Handler(Looper.getMainLooper()).post {
                try {
                    val context = getApplicationContext() ?: run {
                        continuation.resume(null)
                        return@post
                    }
                    
                    @SuppressLint("SetJavaScriptEnabled")
                    val webView = WebView(context)
                    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = USER_AGENT
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        setSupportMultipleWindows(false)
                    }
                    
                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            log("VidxGo", "🚫 Pop-up bloccato")
                            return true
                        }
                    }
                    
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url.toString()
                            
                            if (!found && isVideoUrl(requestUrl)) {
                                found = true
                                log("VidxGo", "🔥🔥🔥 VIDEO TROVATO: $requestUrl")
                                
                                Handler(Looper.getMainLooper()).post {
                                    webView.stopLoading()
                                    webView.destroy()
                                    latch.countDown()
                                    continuation.resume(requestUrl)
                                }
                                return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            log("VidxGo", "📄 Pagina caricata: ${url?.take(80)}")
                            
                            val clickScript = """
                                (function() {
                                    var overlay = document.getElementById('resumeOverlay');
                                    if (overlay) overlay.style.display = 'none';
                                    
                                    var resumeBtn = document.getElementById('resumeFromStart');
                                    if (resumeBtn) {
                                        resumeBtn.click();
                                        return 'clicked_resumeFromStart';
                                    }
                                    
                                    var continueBtn = document.getElementById('resumeContinue');
                                    if (continueBtn) {
                                        continueBtn.click();
                                        return 'clicked_resumeContinue';
                                    }
                                    
                                    var playBtn = document.querySelector('.play-pause-center, #playPauseCenter');
                                    if (playBtn) {
                                        playBtn.click();
                                        return 'clicked_centerPlay';
                                    }
                                    
                                    return 'nothing_found';
                                })();
                            """.trimIndent()
                            
                            view?.evaluateJavascript(clickScript) { result ->
                                log("VidxGo", "📝 JS: $result")
                            }
                        }
                    }
                    
                    log("VidxGo", "📡 Caricamento: $targetUrl")
                    webView.loadUrl(targetUrl, CUSTOM_HEADERS)
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            log("VidxGo", "⏰ Timeout dopo ${TIMEOUT_SECONDS}s")
                            webView.destroy()
                            latch.countDown()
                            continuation.resume(null)
                        }
                    }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                    
                } catch (e: Exception) {
                    log("VidxGo", "❌ Errore: ${e.message}")
                    continuation.resume(null)
                }
            }
            latch.await(TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
        }
    }
}
