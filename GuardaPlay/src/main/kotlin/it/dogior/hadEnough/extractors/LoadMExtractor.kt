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
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class LoadMExtractor : ExtractorApi() {
    override val name = "LoadM"
    override val mainUrl = "loadm.cam"
    override val requiresReferer = false

    companion object {
        private const val TAG = "LoadMExtractor"
        private const val TIMEOUT_SECONDS = 30L

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
            if (lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") ||
                lowerUrl.contains(".png") || lowerUrl.contains(".webp") ||
                lowerUrl.contains(".gif") || lowerUrl.contains(".svg") ||
                lowerUrl.contains(".css") || lowerUrl.contains(".js") ||
                lowerUrl.contains("favicon") || lowerUrl.contains("poster") ||
                lowerUrl.contains("thumbnail") || lowerUrl.contains("image")) {
                return false
            }
            return lowerUrl.contains(".m3u8") || lowerUrl.contains(".mp4")
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "getUrl: $url")
        val videoUrl = extractWithWebView(url)

        if (videoUrl != null) {
            M3u8Helper.generateM3u8(
                name, videoUrl, url,
                headers = mapOf("referer" to "https://loadm.cam/")
            ).forEach(callback)
        }
    }

    private suspend fun extractWithWebView(embedUrl: String): String? {
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
                        javaScriptCanOpenWindowsAutomatically = false
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url.toString()

                            if (!found && isVideoUrl(requestUrl)) {
                                found = true
                                Log.i(TAG, "!!! TARGET ACQUIRED !!! -> $requestUrl")

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

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)

                            Handler(Looper.getMainLooper()).postDelayed({
                                webView.evaluateJavascript("""
                                    (function() {
                                        try { Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en', 'it'] }); } catch(e) {}
                                        try { Object.defineProperty(navigator, 'language', { get: () => 'it' }); } catch(e) {}
                                        try { delete window.webdriver; } catch(e) {}
                                        try { window.chrome = { runtime: {} }; } catch(e) {}

                                        var el = document.getElementById('player-loading') || document.querySelector('.player-loading');
                                        if(el) el.click();

                                        var v = document.querySelector('video');
                                        if(v) { v.muted = true; v.play(); }
                                        document.querySelector('.vjs-big-play-button, #vplayer, .play-button, media-play-button, .vp-play')?.click();
                                    })();
                                """.trimIndent(), null)
                            }, 2000)
                        }
                    }

                    webView.loadUrl(embedUrl)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            Log.w(TAG, "Timeout: Nessun link rilevato")
                            webView.stopLoading()
                            webView.destroy()
                            latch.countDown()
                            continuation.resume(null)
                        }
                    }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

                } catch (e: Exception) {
                    Log.e(TAG, "Errore WebView: ${e.message}")
                    continuation.resume(null)
                }
            }

            latch.await(TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
        }
    }
}
