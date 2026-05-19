package it.dogior.hadEnough.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
        private const val TAG = "VidxGoExtractor"
        private const val TIMEOUT_SECONDS = 25L
        private const val REFERER = "https://altadefinizione.you/"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/148.0.0.0 Safari/537.36"
        
        // ============================================================
        // TUTTI GLI HEADERS dalla tua cattura
        // ============================================================
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
                Log.e(TAG, "Failed to get Application context: ${e.message}")
                null
            }
        }
        
        private fun isVideoUrl(url: String): Boolean {
            val lowerUrl = url.lowercase()
            
            // Escludi immagini, CSS, JS, poster, backdrop
            if (lowerUrl.contains(".jpg") || 
                lowerUrl.contains(".jpeg") || 
                lowerUrl.contains(".png") || 
                lowerUrl.contains(".webp") || 
                lowerUrl.contains(".gif") ||
                lowerUrl.contains(".svg") ||
                lowerUrl.contains(".css") ||
                lowerUrl.contains(".js") ||
                lowerUrl.contains("favicon") ||
                lowerUrl.contains("poster") ||
                lowerUrl.contains("backdrop") ||
                lowerUrl.contains("thumbnail")) {
                return false
            }
            
            // Accetta .m3u8 e delivery di streaming
            return lowerUrl.contains(".m3u8") || 
                   lowerUrl.contains("delivery") || 
                   lowerUrl.contains("master.m3u8") ||
                   lowerUrl.contains("playlist.m3u8")
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Converte l'URL nel formato corretto per VidxGo
        val targetUrl = buildTargetUrl(url)
        log("VidxGo", "🎬 URL target: $targetUrl")
        log("VidxGo", "📋 Headers: ${CUSTOM_HEADERS.keys.joinToString()}")
        
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
            log("VidxGo", "❌ Nessun M3U8 trovato")
        }
    }
    
    private fun buildTargetUrl(url: String): String {
        log("VidxGo", "🔨 buildTargetUrl - Input: $url")
        
        // Formato 1: URL già completo
        if (url.contains("v.vidxgo.co")) {
            log("VidxGo", "✅ URL già completo: $url")
            return url
        }
        
        // Formato 2: solo ID (tt33504773 o 33504773)
        val patterns = listOf(
            Regex("""tt(\d+)"""),
            Regex("""^(\d+)$""")
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { id ->
                val result = "https://v.vidxgo.co/$id"
                log("VidxGo", "✅ Pattern matchato: ID=$id → $result")
                return result
            }
        }
        
        // Formato 3: serie TV (tt4574334-1-1 o 4574334-1-1)
        val seriePattern = Regex("""(?:tt)?(\d+)[-/](\d+)[-/](\d+)""")
        seriePattern.find(url)?.let { match ->
            val id = match.groupValues[1]
            val season = match.groupValues[2]
            val episode = match.groupValues[3]
            val result = "https://v.vidxgo.co/$id/$season/$episode"
            log("VidxGo", "✅ Serie TV: ID=$id, S=$season, E=$episode → $result")
            return result
        }
        
        // Fallback
        log("VidxGo", "⚠️ Nessun pattern matchato, uso URL originale: $url")
        return url
    }
    
    private suspend fun extractWithWebView(targetUrl: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            var extractedUrl: String? = null
            var found = false
            
            Handler(Looper.getMainLooper()).post {
                try {
                    val context = getApplicationContext() ?: run {
                        log("VidxGo", "❌ Context null")
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
                        mediaPlaybackRequiresUserGesture = false  // IMPORTANTE per autoplay
                        userAgentString = USER_AGENT
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    }
                    
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url.toString()
                            
                            log("VidxGo", "🔍 Intercettata richiesta: $requestUrl")
                            
                            if (!found && isVideoUrl(requestUrl)) {
                                found = true
                                extractedUrl = requestUrl
                                log("VidxGo", "🔥🔥🔥 M3U8 INTERCETTATO: $requestUrl")
                                
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
                            log("VidxGo", "📄 Pagina caricata: $url")
                            
                            // Inietta JavaScript per fare auto-click sul pulsante "Dall'inizio"
                            view?.evaluateJavascript("""
                                (function() {
                                    console.log('VidxGo: Cerco pulsanti...');
                                    
                                    // Prova a cliccare sul pulsante "Dall'inizio"
                                    var resumeBtn = document.getElementById('resumeFromStart');
                                    if (resumeBtn) {
                                        console.log('Trovato resumeFromStart, click!');
                                        resumeBtn.click();
                                        return 'clicked_resume';
                                    }
                                    
                                    // Prova il pulsante "Riprendi"
                                    var continueBtn = document.getElementById('resumeContinue');
                                    if (continueBtn) {
                                        console.log('Trovato resumeContinue, click!');
                                        continueBtn.click();
                                        return 'clicked_continue';
                                    }
                                    
                                    // Cerca qualsiasi pulsante di play
                                    var playBtn = document.querySelector('.vjs-big-play-button, .play-button, [class*="play"], [class*="Play"]');
                                    if (playBtn) {
                                        console.log('Trovato pulsante play, click!');
                                        playBtn.click();
                                        return 'clicked_play';
                                    }
                                    
                                    // Prova a far partire il video direttamente
                                    var video = document.querySelector('video');
                                    if (video) {
                                        video.muted = true;
                                        video.play();
                                        console.log('Video avviato direttamente');
                                        return 'video_played';
                                    }
                                    
                                    console.log('Nessun pulsante trovato');
                                    return 'nothing_found';
                                })();
                            """.trimIndent()) { result ->
                                log("VidxGo", "📝 JS result: $result")
                            }
                        }
                        
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            log("VidxGo", "❌ WebView error: $errorCode - $description")
                        }
                    }
                    
                    log("VidxGo", "📡 Caricamento WebView: $targetUrl")
                    log("VidxGo", "📋 Headers: ${CUSTOM_HEADERS.keys}")
                    
                    // Carica con TUTTI gli headers
                    webView.loadUrl(targetUrl, CUSTOM_HEADERS)
                    
                    // Timeout
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            log("VidxGo", "⏰ Timeout dopo ${TIMEOUT_SECONDS}s")
                            webView.stopLoading()
                            webView.destroy()
                            latch.countDown()
                            continuation.resume(null)
                        }
                    }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                    
                } catch (e: Exception) {
                    log("VidxGo", "❌ Errore WebView: ${e.message}")
                    continuation.resume(null)
                }
            }
            
            latch.await(TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
        }
    }
}
