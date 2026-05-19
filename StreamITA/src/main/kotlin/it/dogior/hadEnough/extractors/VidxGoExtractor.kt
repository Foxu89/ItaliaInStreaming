package it.dogior.hadEnough.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume

class VidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "v.vidxgo.co"
    override val requiresReferer = false

    companion object {
        private const val TIMEOUT_SECONDS = 45L
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
            } catch (e: Exception) { null }
        }
        
        private fun isVideoUrl(url: String): Boolean {
            val lowerUrl = url.lowercase()
            if (lowerUrl.contains(".jpg") || lowerUrl.contains(".png") || lowerUrl.contains(".css") ||
                lowerUrl.contains(".js") || lowerUrl.contains("favicon") || lowerUrl.contains("poster") ||
                lowerUrl.contains("backdrop") || lowerUrl.contains("thumbnail")) {
                return false
            }
            return lowerUrl.contains(".m3u8") || lowerUrl.contains("master.m3u8") ||
                   lowerUrl.contains("playlist.m3u8") || (lowerUrl.contains("media") && lowerUrl.contains("your"))
        }
        
        private fun getInputStreamWithDecompression(connection: HttpURLConnection): InputStream {
            var inputStream = connection.inputStream
            val contentEncoding = connection.contentEncoding
            if (contentEncoding != null) {
                when {
                    contentEncoding.contains("gzip", ignoreCase = true) -> {
                        inputStream = GZIPInputStream(inputStream)
                        log("VidxGo", "📦 Decompressione GZIP")
                    }
                    contentEncoding.contains("zstd", ignoreCase = true) -> {
                        log("VidxGo", "⚠️ Zstd non supportato, uso stream diretto")
                    }
                    contentEncoding.contains("deflate", ignoreCase = true) -> {
                        log("VidxGo", "📦 Decompressione DEFLATE")
                    }
                }
            }
            return inputStream
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val regex = Regex("""(\d+)""")
        val match = regex.find(url)
        val rawId = match?.value
        
        if (rawId == null) {
            log("VidxGo", "❌ Nessun ID numerico trovato in: $url")
            return
        }
        
        val targetUrl = "https://v.vidxgo.co/$rawId"
        log("VidxGo", "🎬 ID: $rawId → URL: $targetUrl")
        
        val videoUrl = extractWithWebView(targetUrl)

        if (videoUrl != null) {
            log("VidxGo", "🎯 M3U8 TROVATO: $videoUrl")
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
            var clickCount = 0
            
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
                        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
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
                                log("VidxGo", "🔥 VIDEO TROVATO: $requestUrl")
                                Handler(Looper.getMainLooper()).post {
                                    webView.stopLoading()
                                    webView.destroy()
                                    latch.countDown()
                                    continuation.resume(requestUrl)
                                }
                                return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                            }
                            
                            try {
                                val connection = URL(requestUrl).openConnection() as HttpURLConnection
                                CUSTOM_HEADERS.forEach { (key, value) ->
                                    connection.setRequestProperty(key, value)
                                }
                                connection.instanceFollowRedirects = true
                                connection.connectTimeout = 10000
                                connection.readTimeout = 10000
                                
                                val responseCode = connection.responseCode
                                
                                if (requestUrl == targetUrl) {
                                    log("VidxGo", "📊 Status per $targetUrl: $responseCode")
                                }
                                
                                val inputStream = if (responseCode in 200..299) {
                                    getInputStreamWithDecompression(connection)
                                } else {
                                    connection.errorStream
                                }
                                
                                val contentType = connection.contentType ?: "text/html"
                                return WebResourceResponse(contentType, "utf-8", inputStream)
                                
                            } catch (e: Exception) {
                                log("VidxGo", "⚠️ Errore fetch: ${e.message}")
                                return null
                            }
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            log("VidxGo", "📄 Pagina caricata: ${url?.take(80)}")
                            
                            // Stampa HTML
                            view?.evaluateJavascript(
                                "document.documentElement.outerHTML"
                            ) { html ->
                                if (html.isNotEmpty() && html != "null" && html != "undefined") {
                                    log("StreamITA", "========== HTML PAGE START ==========")
                                    val chunkSize = 3000
                                    var index = 0
                                    while (index < html.length) {
                                        val end = minOf(index + chunkSize, html.length)
                                        log("StreamITA", "[${index + 1}-${end}]:\n${html.substring(index, end)}")
                                        index = end
                                    }
                                    log("StreamITA", "========== HTML PAGE END (${html.length} caratteri) ==========")
                                } else {
                                    log("StreamITA", "⚠️ HTML non disponibile")
                                }
                            }
                            
                            // Click periodici
                            val handler = Handler(Looper.getMainLooper())
                            val clicker = object : Runnable {
                                override fun run() {
                                    if (!found && clickCount < 10) {
                                        webView.evaluateJavascript("""
                                            (function() {
                                                var overlay = document.getElementById('resumeOverlay');
                                                if (overlay) overlay.style.display = 'none';
                                                
                                                var video = document.querySelector('video');
                                                if (video) { video.muted = true; video.play(); }
                                                
                                                var resumeBtn = document.getElementById('resumeFromStart');
                                                if (resumeBtn) resumeBtn.click();
                                                
                                                var continueBtn = document.getElementById('resumeContinue');
                                                if (continueBtn) continueBtn.click();
                                                
                                                var playCenter = document.querySelector('.play-pause-center, #playPauseCenter');
                                                if (playCenter) playCenter.click();
                                                
                                                var playBtn = document.querySelector('#playBtn, .btn-play');
                                                if (playBtn) playBtn.click();
                                                
                                                var allButtons = document.querySelectorAll('button, a, div[role="button"]');
                                                for (var i = 0; i < allButtons.length; i++) {
                                                    var btn = allButtons[i];
                                                    var text = (btn.innerText || '').toLowerCase();
                                                    if (text.includes('play') || text.includes('riprendi') || text.includes('inizio')) {
                                                        btn.click();
                                                    }
                                                }
                                                
                                                var playElements = document.querySelectorAll('[class*="play"], [class*="Play"]');
                                                for (var i = 0; i < playElements.length; i++) {
                                                    playElements[i].click();
                                                }
                                                
                                                return 'click_attempt_' + (${clickCount + 1});
                                            })();
                                        """.trimIndent(), null)
                                        clickCount++
                                        handler.postDelayed(this, 3000)
                                    }
                                }
                            }
                            handler.postDelayed(clicker, 3000)
                        }
                    }
                    
                    log("VidxGo", "📡 Caricamento: $targetUrl")
                    webView.loadUrl(targetUrl)
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            log("VidxGo", "⏰ Timeout dopo ${TIMEOUT_SECONDS}s (click: $clickCount)")
                            webView.stopLoading()
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
