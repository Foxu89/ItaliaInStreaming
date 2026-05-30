package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class CloudflareBypassInterceptor : Interceptor {

    private var cachedCookies: String? = null
    private val cookieLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toString()

        synchronized(cookieLock) {
            if (cachedCookies != null) {
                val request = chain.request().newBuilder()
                    .addHeader("Cookie", cachedCookies!!)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
                    .build()
                return chain.proceed(request)
            }
        }

        Log.d("CloudflareBypass", "Bypassando Cloudflare con WebView...")
        val result = runBlocking { fetchWithWebView(url) }

        if (result.cookies.isNotBlank()) {
            synchronized(cookieLock) {
                cachedCookies = result.cookies
            }
            Log.d("CloudflareBypass", "Cloudflare bypassato! Cookie salvati.")
        }

        return Response.Builder()
            .request(chain.request())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(okhttp3.ResponseBody.create(null, result.html))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchWithWebView(url: String): WebViewResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            var pageContent = ""
            var cookiesObtained = ""

            val context = getApplicationContext() ?: run {
                continuation.resume(WebViewResult("", ""))
                return@suspendCancellableCoroutine
            }

            val webView = WebView(context)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        webView.evaluateJavascript("window.scrollTo(0, 100);", null)

                        Handler(Looper.getMainLooper()).postDelayed({
                            webView.evaluateJavascript("window.scrollTo(0, 300);", null)

                            Handler(Looper.getMainLooper()).postDelayed({
                                val cookieManager = CookieManager.getInstance()
                                cookiesObtained = cookieManager.getCookie(url) ?: ""

                                webView.evaluateJavascript("document.documentElement.outerHTML") { result ->
                                    pageContent = result?.trim('"')?.replace("\\\"", "\"") ?: ""
                                    webView.stopLoading()
                                    webView.destroy()
                                    latch.countDown()
                                }
                            }, 2000)
                        }, 1000)
                    }, 3000)
                }
            }

            webView.loadUrl(url)

            val success = latch.await(15, TimeUnit.SECONDS)
            if (!success) {
                webView.stopLoading()
                webView.destroy()
            }

            continuation.resume(WebViewResult(pageContent, cookiesObtained))
        }
    }

    private fun getApplicationContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val activityThread = currentActivityThreadMethod.invoke(null)
            val getApplicationMethod = activityThreadClass.getMethod("getApplication")
            getApplicationMethod.invoke(activityThread) as? Context
        } catch (e: Exception) {
            null
        }
    }

    private data class WebViewResult(val html: String, val cookies: String)

    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}

class Loonex : MainAPI() {
    override var mainUrl = "https://loonex.eu/cartoni/"
    override var name = "Loonex"
    override var lang = "it"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Cartoon)
    override val hasMainPage = true

    private val client = app.baseClient.newBuilder()
        .addInterceptor(CloudflareBypassInterceptor())
        .build()

    override val mainPage = mainPageOf(
        mainUrl to "Novità",
        "${mainUrl}index.php?cat=all" to "Tutti i Cartoni",
    )

    private suspend fun fetch(url: String): String {
        val response = client.newCall(okhttp3.Request.Builder().url(url).build()).execute()
        return response.body?.string() ?: ""
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = fetch(request.data)
        val document = Jsoup.parse(html)

        if (request.data.contains("cat=all")) {
            val cards = document.select("a[href*=?cartone=]").mapNotNull { link ->
                val card = link.selectFirst(".cartoon-card-cinematic") ?: return@mapNotNull null
                parseCard(link, card)
            }
            return newHomePageResponse(
                listOf(HomePageList(request.name, cards, false)),
                false
            )
        }

        val lists = mutableListOf<HomePageList>()
        val bands = document.select("div.seamless-band")

        for (band in bands) {
            if (band.id() == "trendingBand") continue

            val titleEl = band.selectFirst("h3.brand-font")
            val sectionName = titleEl?.text()?.trim() ?: continue
            val scroller = band.selectFirst("div.horizontal-scroller")
            if (scroller == null || scroller.select("div.scroller-item").isEmpty()) continue

            val items = scroller.select("div.scroller-item").mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val card = item.selectFirst(".cartoon-card-cinematic") ?: return@mapNotNull null
                parseCard(link, card)
            }
            if (items.isNotEmpty()) {
                lists.add(HomePageList(sectionName, items, false))
            }
        }

        val trendingBand = document.selectFirst("#trendingBand")
        if (trendingBand != null) {
            val scroller = trendingBand.selectFirst("div.horizontal-scroller")
            if (scroller != null) {
                val items = scroller.select("div.scroller-item").mapNotNull { item ->
                    val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                    val card = item.selectFirst(".cartoon-card-cinematic") ?: return@mapNotNull null
                    parseCard(link, card)
                }
                if (items.isNotEmpty()) {
                    lists.add(HomePageList("I più visti", items, false))
                }
            }
        }

        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}index.php?search=${java.net.URLEncoder.encode(query, "UTF-8")}&cat=all"
        val html = fetch(url)
        val document = Jsoup.parse(html)

        val results = document.select("a[href*=?cartone=]").mapNotNull { link ->
            val card = link.selectFirst(".cartoon-card-cinematic") ?: return@mapNotNull null
            parseCard(link, card)
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val html = fetch(url)
        val document = Jsoup.parse(html)

        val title = document.selectFirst("h1.display-4")?.text()?.trim() ?: "Sconosciuto"
        val poster = document.selectFirst("img.detail-poster")?.attr("src") ?: ""
        val plot = document.select("div.content-box-opaque div.text-secondary").first()?.text()?.trim() ?: ""
        val tags = document.select("span.badge.bg-secondary").mapNotNull { it.text().trim().ifBlank { null } }

        val backdropEl = document.selectFirst("div.hero-backdrop")
        val backdropStyle = backdropEl?.attr("style") ?: ""
        val backgroundUrl = Regex("""url\('?(.*?)'?\)""").find(backdropStyle)?.groupValues?.getOrNull(1) ?: ""

        val seasonTabs = document.select("ul#season-tabs li.nav-item button.nav-link")

        if (seasonTabs.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            val tabContent = document.selectFirst("div.tab-content#season-tabsContent")

            seasonTabs.forEachIndexed { index, tabButton ->
                val seasonText = tabButton.text().trim()
                val seasonNum = Regex("""Stagione (\d+)""").find(seasonText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val targetId = tabButton.attr("data-bs-target").removePrefix("#")
                val tabPane = tabContent?.selectFirst("div.tab-pane#$targetId") ?: return@forEachIndexed

                val rows = tabPane.select("div.episode-row")
                rows.forEachIndexed { epIndex, row ->
                    val epTitle = row.selectFirst(".episode-title")?.text()?.trim() ?: "Episodio ${epIndex + 1}"
                    val playUrl = row.selectFirst("a.btn-play-sm")?.attr("href") ?: return@forEachIndexed

                    var epNum = Regex("""^(\d+):""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (epNum == null) {
                        epNum = Regex("""Episodio (\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    }

                    episodes.add(newEpisode(playUrl) {
                        name = epTitle
                        season = seasonNum ?: (index + 1)
                        episode = epNum ?: (epIndex + 1)
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.backgroundUrl = backgroundUrl
                this.year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()
            }
        }

        val playUrl = document.selectFirst("a.btn-brand.auto-watch-btn, a.btn-play-sm.auto-watch-btn")?.attr("href") ?: ""

        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl = playUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.backgroundUrl = backgroundUrl
            this.year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false

        try {
            val html = fetch(data)
            val document = Jsoup.parse(html)

            val iframe = document.selectFirst("iframe")
            val videoSrc = document.selectFirst("video source")
            val videoEl = document.selectFirst("video")

            val videoUrl = videoSrc?.attr("src")
                ?: videoEl?.attr("src")
                ?: iframe?.attr("src")
                ?: data

            callback.invoke(
                ExtractorLink(
                    source = "Loonex",
                    name = "Loonex",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains("m3u8")
                )
            )
            return true
        } catch (e: Exception) {
            Log.e("Loonex", "Errore in loadLinks: ${e.message}")
            callback.invoke(
                ExtractorLink(
                    source = "Loonex",
                    name = "Loonex",
                    url = data,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
            return true
        }
    }

    private fun parseCard(link: Element, card: Element): SearchResponse? {
        val href = link.attr("href")
        val fullUrl = if (href.startsWith("http")) href else mainUrl.trimEnd('/') + "/" + href.removePrefix("/")

        val title = card.selectFirst(".card-title-cine")?.text()?.trim() ?: return null
        val poster = card.selectFirst(".card-img-bg")?.attr("src") ?: ""
        val isMovie = card.selectFirst(".badge-custom.movie-badge") != null
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, fullUrl, type) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fullUrl, type) { this.posterUrl = poster }
        }
    }
}
