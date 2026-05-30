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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class LoonexExtractor : ExtractorApi() {
    override val name = "Loonex"
    override val mainUrl = "loonex.eu"
    override val requiresReferer = false

    companion object {
        private const val TAG = "LoonexExtractor"
        private const val TIMEOUT_SECONDS = 20L

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

        private fun isTargetUrl(url: String): Boolean {
            val lower = url.lowercase()
            if (lower.contains(".jpg") ||
                lower.contains(".jpeg") ||
                lower.contains(".png") ||
                lower.contains(".webp") ||
                lower.contains(".gif") ||
                lower.contains(".svg") ||
                lower.contains(".css") ||
                lower.contains(".js") ||
                lower.contains("favicon") ||
                lower.contains("poster") ||
                lower.contains("thumbnail")
            ) return false
            if (lower.contains("output.m3u8") || lower.contains("playlist.m3u8")) return true
            if (lower.startsWith("https://vd697.okcdn.ru/") || lower.endsWith(".mp4")) return true
            return false
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "Sniffing: $url")
        val videoUrls = extractWithWebView(url)

        for (videoUrl in videoUrls) {
            val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val displayName = if (type == ExtractorLinkType.M3U8) "M3U8" else "MP4"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Loonex $displayName",
                    url = videoUrl,
                    type = type,
                ) {
                    this.referer = "https://loonex.eu/"
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
                        "Referer" to "https://loonex.eu/",
                        "Origin" to "https://loonex.eu",
                    )
                }
            )
        }
    }

    private suspend fun extractWithWebView(embedUrl: String): Set<String> {
        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            val foundUrls = mutableSetOf<String>()

            Handler(Looper.getMainLooper()).post {
                try {
                    val context = getApplicationContext() ?: run {
                        continuation.resume(emptySet())
                        return@post
                    }

                    @SuppressLint("SetJavaScriptEnabled")
                    val webView = WebView(context)

                    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url.toString()

                            if (isTargetUrl(requestUrl)) {
                                if (foundUrls.add(requestUrl)) {
                                    Log.i(TAG, "Video trovato: $requestUrl")
                                }
                            }

                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    webView.loadUrl(embedUrl)

                    val handler = Handler(Looper.getMainLooper())
                    val clicker = object : Runnable {
                        var count = 0
                        override fun run() {
                            if (count < 6) {
                                webView.evaluateJavascript("""
                                    (function() {
                                        var v = document.querySelector('video');
                                        if(v) { v.muted = true; v.play(); }
                                        document.querySelector('.vjs-big-play-button, #vplayer, .play-button, .btn-play-sm')?.click();
                                    })();
                                """.trimIndent(), null)
                                count++
                                handler.postDelayed(this, 2500)
                            }
                        }
                    }
                    handler.postDelayed(clicker, 3000)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (foundUrls.isEmpty()) {
                            Log.w(TAG, "Timeout: nessun video trovato")
                        } else {
                            Log.i(TAG, "Trovati ${foundUrls.size} video: $foundUrls")
                        }
                        webView.stopLoading()
                        webView.destroy()
                        latch.countDown()
                        continuation.resume(foundUrls.toSet())
                    }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

                } catch (e: Exception) {
                    Log.e(TAG, "Errore WebView: ${e.message}")
                    continuation.resume(emptySet())
                }
            }

            latch.await(TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
        }
    }
}
