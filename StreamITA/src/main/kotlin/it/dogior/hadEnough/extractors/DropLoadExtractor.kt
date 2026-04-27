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

class DropLoadExtractor : ExtractorApi() {
    override val name = "DropLoad"
    override val mainUrl = "dr0pstream.com"
    override val requiresReferer = false

    companion object {
        private const val TAG = "DropLoadExtractor"
        private const val TIMEOUT_SECONDS = 15L

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
            val lowerUrl = url.lowercase()
            return lowerUrl.contains("master.m3u8") ||
                   (lowerUrl.contains("dropcdn.io") && lowerUrl.contains(".m3u8"))
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "Sniffing DropLoad: $url")
        val videoUrl = extractWithWebView(url)

        if (videoUrl != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "DropLoad",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
                        "Referer" to "https://dr0pstream.com/",
                        "Origin" to "https://dr0pstream.com",
                    )
                    this.referer = "https://dr0pstream.com/"
                }
            )
        }
    }

    private suspend fun extractWithWebView(embedUrl: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            var extractedUrl: String? = null
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
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url.toString()

                            if (!found && isTargetUrl(requestUrl)) {
                                found = true
                                extractedUrl = requestUrl
                                Log.i(TAG, "!!! Master M3U8 trovato: $requestUrl")

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
                    }

                    webView.loadUrl(embedUrl)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            Log.w(TAG, "Timeout: Nessun master M3U8 trovato")
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
