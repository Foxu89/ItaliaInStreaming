package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class CalcioStreaming : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://fig.direttecommunity.online/"
    override var name = "CalcioStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    override var sequentialMainPage = true
    val cfKiller = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            "$mainUrl/partite-streaming.html"
        ).document
        val sections =
            document.select("div.slider-title")
                .filter { it -> it.select("div.owl-carousel").isNotEmpty() }
        if (sections.isEmpty()) throw ErrorLoadingException()

        return newHomePageResponse(sections.mapNotNull { it ->
            val categoryName = it.selectFirst("div.header-wrap > div.label")!!.text()
            val shows = it.select("div.owl-carousel > .slider-tile-inner > .box-16x9").map {
                val href = it.selectFirst("a")!!.attr("href")
                val name = ""
                val posterUrl = fixUrl(it.selectFirst("img.tile-image")!!.attr("src"))
                    .replace("//uploads", "/uploads")
                newLiveSearchResponse(name, href, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            }
            if (shows.isEmpty()) return@mapNotNull null
            HomePageList(
                categoryName,
                shows,
                isHorizontalImages = true
            )
        }, false)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val posterUrl =
            document.select("div.background-image.bg-image").attr("style").substringAfter("url(")
                .substringBefore(");")
        val infoBlock = document.select(".info-wrap")
        val title = infoBlock.select("h1").text()
        val description = infoBlock.select("div.info-span > span").toList().joinToString(" - ")
        return newLiveStreamLoadResponse(name = title, url = url, dataUrl = url) {
            this.posterUrl = fixUrl(posterUrl)
                .replace("//uploads", "/uploads")
            this.plot = description
        }
    }

    private suspend fun extractVideoStreamWithWebView(url: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            var extractedUrl: String? = null
            var found = false

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val context = runCatching {
                        val activityThreadClass = Class.forName("android.app.ActivityThread")
                        val currentActivityThreadMethod =
                            activityThreadClass.getMethod("currentActivityThread")
                        val activityThread = currentActivityThreadMethod.invoke(null)
                        val getApplicationMethod = activityThreadClass.getMethod("getApplication")
                        (getApplicationMethod.invoke(activityThread) as? android.app.Application)
                            ?: throw Exception("No Application context")
                    }.getOrNull() ?: run {
                        continuation.resume(null)
                        return@post
                    }

                    val webView = android.webkit.WebView(context)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    }

                    webView.webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val requestUrl = request?.url.toString()

                            if (!found && requestUrl.contains(".m3u8") && requestUrl.contains("/hls/")) {
                                found = true
                                extractedUrl = requestUrl
                                Log.d("CalcioStreaming", "!!! TROVATO m3u8: $requestUrl")

                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    webView.stopLoading()
                                    webView.destroy()
                                    latch.countDown()
                                    continuation.resume(requestUrl)
                                }
                            }

                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    webView.loadUrl(url)

                    // Timeout dopo 15 secondi
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            Log.w("CalcioStreaming", "Timeout sniffing per $url")
                            webView.stopLoading()
                            webView.destroy()
                            latch.countDown()
                            continuation.resume(null)
                        }
                    }, 20000)

                } catch (e: Exception) {
                    Log.e("CalcioStreaming", "Errore WebView: ${e.message}")
                    continuation.resume(null)
                }
            }

            latch.await(25, TimeUnit.SECONDS)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val buttons = document.select("div.embed-container > div.langs > button")

        if (buttons.isEmpty()) {
            Log.w("CalcioStreaming", "Nessun pulsante lingua trovato")
            return false
        }

        // Avvia tutte le WebView in parallelo
        val results = coroutineScope {
            buttons.map { button ->
                async(Dispatchers.IO) {
                    val lang = button.text().trim()
                    val phpUrl = button.attr("data-link")
                    Log.d("CalcioStreaming", "Provando: $lang -> $phpUrl")
                    val m3u8 = extractVideoStreamWithWebView(phpUrl)
                    if (m3u8 != null) {
                        Log.d("CalcioStreaming", "OK $lang: $m3u8")
                        Link(lang, m3u8, phpUrl)
                    } else {
                        Log.w("CalcioStreaming", "Fallito per $lang")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        results.forEach { link ->
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name - ${link.lang}",
                    url = link.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = 0
                    this.referer = link.ref
                }
            )
        }

        return results.isNotEmpty()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = cfKiller.intercept(chain)
                return response
            }
        }
    }

    data class Link(
        val lang: String,
        val url: String,
        val ref: String
    )
}
