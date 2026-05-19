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
        private const val TAG = "VidxGoExtractor"
        private const val TIMEOUT_SECONDS = 60L  // Timeout più lungo per gestire i tentativi
        private const val REFERER = "https://altadefinizione.you/"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/148.0.0.0 Safari/537.36"
        
        // Headers completi
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
        
        // Pattern per riconoscere pubblicità (domini che finiscono con .com, .net, .org, .xyz, .click, .top, .live)
        private val AD_DOMAIN_PATTERNS = listOf(
            Regex("""https?://[^/]+\.com(?:/|$)"""),      // Qualsiasi .com
            Regex("""https?://[^/]+\.net(?:/|$)"""),      // .net
            Regex("""https?://[^/]+\.org(?:/|$)"""),      // .org
            Regex("""https?://[^/]+\.xyz(?:/|$)"""),      // .xyz
            Regex("""https?://[^/]+\.click(?:/|$)"""),    // .click
            Regex("""https?://[^/]+\.top(?:/|$)"""),      // .top
            Regex("""https?://[^/]+\.live(?:/|$)"""),     // .live
            Regex("""https?://[^/]+\.club(?:/|$)"""),     // .club
            Regex("""https?://[^/]+\.online(?:/|$)"""),   // .online
            Regex("""https?://[^/]+\.site(?:/|$)"""),     // .site
            Regex("""https?://[^/]+\.win(?:/|$)"""),      // .win
            Regex("""https?://[^/]+\.bid(?:/|$)"""),      // .bid
            Regex("""https?://[^/]+\.space(?:/|$)"""),    // .space
            Regex("""https?://[^/]+\.web(?:/|$)"""),      // .web
            Regex("""https?://[^/]+\.host(?:/|$)"""),     // .host
            Regex("""https?://[^/]+\.press(?:/|$)"""),    // .press
            Regex("""https?://[^/]+\.work(?:/|$)"""),     // .work
            Regex("""https?://[^/]+\.date(?:/|$)"""),     // .date
            Regex("""https?://[^/]+\.men(?:/|$)"""),      // .men
            Regex("""https?://[^/]+\.loan(?:/|$)"""),     // .loan
            Regex("""https?://[^/]+\.download(?:/|$)"""), // .download
            Regex("""https?://[^/]+\.stream(?:/|$)"""),   // .stream
            Regex("""https?://[^/]+\.life(?:/|$)"""),     // .life
            Regex("""https?://[^/]+\.me(?:/|$)"""),       // .me
            Regex("""https?://[^/]+\.xyz(?:/|$)""")       // .xyz
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
        
        private fun isAdUrl(url: String): Boolean {
            val lowerUrl = url.lowercase()
            
            // Escludi il dominio principale (v.vidxgo.co) - NON è pubblicità
            if (lowerUrl.contains("v.vidxgo.co")) {
                return false
            }
            
            // Escludi domini CDN e streaming validi
            if (lowerUrl.contains("d2b.your") || 
                lowerUrl.contains("media") && lowerUrl.contains("your") ||
                lowerUrl.contains("cloudfront.net") ||
                lowerUrl.contains("cdn") ||
                lowerUrl.contains(".m3u8")) {
                return false
            }
            
            // Blocca TUTTI i domini che finiscono con .com, .net, .org, ecc.
            for (pattern in AD_DOMAIN_PATTERNS) {
                if (pattern.containsMatchIn(lowerUrl)) {
                    return true
                }
            }
            
            // Pattern aggiuntivi per riconoscere pubblicità
            return lowerUrl.contains("popunder") ||
                   lowerUrl.contains("popup") ||
                   lowerUrl.contains("adservice") ||
                   lowerUrl.contains("doubleclick") ||
                   lowerUrl.contains("googlead") ||
                   lowerUrl.contains("exoclick") ||
                   lowerUrl.contains("adsterra") ||
                   lowerUrl.contains("propeller") ||
                   lowerUrl.contains("taboola") ||
                   lowerUrl.contains("outbrain") ||
                   (lowerUrl.contains("click") && lowerUrl.contains("redirect"))
        }
        
        private fun isVideoUrl(url: String): Boolean {
            val lowerUrl = url.lowercase()
            
            // Non considerare le pubblicità come video
            if (isAdUrl(lowerUrl)) {
                return false
            }
            
            return lowerUrl.contains(".m3u8") || 
                   lowerUrl.contains("master.m3u8") ||
                   lowerUrl.contains("playlist.m3u8") ||
                   lowerUrl.contains("/hls/") ||
                   (lowerUrl.contains("media") && lowerUrl.contains("your") && lowerUrl.contains(".m3u8")) ||
                   lowerUrl.contains(".mp4") ||
                   lowerUrl.contains(".ts")
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val targetUrl = buildTargetUrl(url)
        log("VidxGo", "🎬 URL target: $targetUrl")
        
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
    
    private fun buildTargetUrl(url: String): String {
        if (url.contains("v.vidxgo.co")) return url
        
        val patterns = listOf(
            Regex("""tt(\d+)"""),
            Regex("""^(\d+)$""")
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { id ->
                return "https://v.vidxgo.co/$id"
            }
        }
        
        val seriePattern = Regex("""(?:tt)?(\d+)[-/](\d+)[-/](\d+)""")
        seriePattern.find(url)?.let { match ->
            val id = match.groupValues[1]
            val season = match.groupValues[2]
            val episode = match.groupValues[3]
            return "https://v.vidxgo.co/$id/$season/$episode"
        }
        
        return url
    }
    
    private suspend fun extractWithWebView(targetUrl: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            var extractedUrl: String? = null
            var found = false
            var clickAttempts = 0
            val maxClickAttempts = 10  // Massimo 10 tentativi di click
            
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
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = USER_AGENT
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
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
                            
                            // Blocca TUTTE le pubblicità (qualsiasi dominio .com, .net, ecc.)
                            if (isAdUrl(requestUrl)) {
                                log("VidxGo", "🚫 Ad bloccata: ${requestUrl.take(80)}")
                                return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                            }
                            
                            // Log delle richieste interessanti
                            if (!found && (requestUrl.contains(".m3u8") || requestUrl.contains("delivery") || requestUrl.contains("your"))) {
                                log("VidxGo", "🔍 Richiesta interessante: ${requestUrl.take(100)}")
                            }
                            
                            if (!found && isVideoUrl(requestUrl)) {
                                found = true
                                extractedUrl = requestUrl
                                log("VidxGo", "🔥🔥🔥 VIDEO TROVATO! URL: $requestUrl")
                                
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
                        
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url.toString()
                            
                            // Blocca redirect verso pubblicità
                            if (isAdUrl(url)) {
                                log("VidxGo", "🚫 Redirect ad bloccato: ${url.take(80)}")
                                return true
                            }
                            
                            // Se è un video, catturalo
                            if (isVideoUrl(url)) {
                                log("VidxGo", "🔥🔥🔥 Redirect verso video: $url")
                                extractedUrl = url
                                found = true
                                continuation.resume(url)
                                return true
                            }
                            
                            return false
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            log("VidxGo", "📄 Pagina caricata: ${url?.take(80)}")
                            
                            // Script per cliccare sul play e rimuovere overlay
                            val playScript = """
                                (function() {
                                    console.log('VidxGo: Tentativo click #${clickAttempts + 1}');
                                    
                                    // Rimuovi eventuali overlay/popup
                                    var overlays = document.querySelectorAll('[class*="overlay"], [class*="popup"], [class*="modal"], [class*="ad"], [class*="banner"], [style*="position"][style*="fixed"]');
                                    overlays.forEach(function(el) {
                                        if (el.style.position === 'fixed' || el.style.position === 'absolute') {
                                            el.style.display = 'none';
                                            el.remove();
                                        }
                                    });
                                    
                                    // Cerca QUALSIASI pulsante che potrebbe avviare il video
                                    var selectors = [
                                        'video',
                                        '.vjs-big-play-button',
                                        '.play-button',
                                        '[class*="play"]',
                                        '[class*="Play"]',
                                        'button:has(> svg)',
                                        'div[role="button"]',
                                        '#resumeFromStart',
                                        '#resumeContinue',
                                        '.resume-btn',
                                        'button:contains("Riprendi")',
                                        'button:contains("Dall")',
                                        'button:contains("inizio")'
                                    ];
                                    
                                    // Prova con il video diretto
                                    var video = document.querySelector('video');
                                    if (video) {
                                        video.muted = true;
                                        video.play();
                                        return 'video_played';
                                    }
                                    
                                    // Cerca tutti i bottoni
                                    var buttons = document.querySelectorAll('button, a, div[role="button"]');
                                    for (var i = 0; i < buttons.length; i++) {
                                        var btn = buttons[i];
                                        var text = (btn.innerText || '').toLowerCase();
                                        var classes = (btn.className || '').toLowerCase();
                                        
                                        if (text.includes('play') || classes.includes('play') || 
                                            text.includes('riprendi') || text.includes('inizio') ||
                                            text.includes('dall') || classes.includes('vjs')) {
                                            btn.click();
                                            return 'clicked_' + (text.substring(0,20) || classes.substring(0,20));
                                        }
                                    }
                                    
                                    return 'no_button_found';
                                })();
                            """.trimIndent()
                            
                            view?.evaluateJavascript(playScript) { result ->
                                log("VidxGo", "📝 JS risultato: $result")
                                
                                // Se non abbiamo ancora trovato il video e ci sono tentativi rimasti, riprova
                                if (!found && clickAttempts < maxClickAttempts) {
                                    clickAttempts++
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        view?.evaluateJavascript(playScript, null)
                                    }, 3000)  // Aspetta 3 secondi tra un tentativo e l'altro
                                }
                            }
                        }
                    }
                    
                    log("VidxGo", "📡 Caricamento WebView: $targetUrl")
                    log("VidxGo", "📋 Headers: ${CUSTOM_HEADERS.keys.joinToString()}")
                    webView.loadUrl(targetUrl, CUSTOM_HEADERS)
                    
                    // Timeout generale
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            log("VidxGo", "⏰ Timeout finale dopo ${TIMEOUT_SECONDS}s (tentativi click: $clickAttempts)")
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
