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
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.MaxStreamExtractor
import it.dogior.hadEnough.extractors.StreamTapeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// ============================================
// CLOUDFLARE BYPASS INTERCEPTOR
// ============================================

class CloudflareBypassInterceptor : Interceptor {
    
    private var cachedCookies: String? = null
    private val cookieLock = Any()
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toString()
        
        // Se abbiamo già i cookie, usali per richieste HTTP normali
        synchronized(cookieLock) {
            if (cachedCookies != null) {
                val request = chain.request().newBuilder()
                    .addHeader("Cookie", cachedCookies!!)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
                    .build()
                return chain.proceed(request)
            }
        }
        
        // Prima richiesta: usa WebView per bypassare Cloudflare
        Log.d("CloudflareBypass", "🛡️ Bypassando Cloudflare con WebView...")
        val result = runBlocking { fetchWithWebView(url) }
        
        if (result.cookies.isNotBlank()) {
            synchronized(cookieLock) {
                cachedCookies = result.cookies
            }
            Log.d("CloudflareBypass", "✅ Cloudflare bypassato! Cookie salvati.")
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
                    Log.d("CloudflareBypass", "WebView caricata: $pageUrl")
                    
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
    
    // Helper per chiamare suspend da Java
    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}

// ============================================
// TOONITALIA PROVIDER
// ============================================

class ToonItalia : MainAPI() {
    override var mainUrl = "https://toonitalia.green/"
    override var name = "ToonItalia"
    override var lang = "it"
    override val supportedTypes =
        setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.AnimeMovie, TvType.Cartoon)
    override val hasMainPage = true

    private val client = app.baseClient.newBuilder()
        .addInterceptor(CloudflareBypassInterceptor())
        .build()

    override val mainPage = mainPageOf(
        mainUrl to "Ultimi Aggiunti",
        "${mainUrl}category/kids/" to "Serie Tv",
        "${mainUrl}category/anime/" to "Anime",
        "${mainUrl}film-anime/" to "Film",
    )

    private suspend fun fetch(url: String): String {
        val response = client.newCall(okhttp3.Request.Builder().url(url).build()).execute()
        return response.body?.string() ?: ""
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data + "page/$page"
        val html = fetch(url)
        val document = Jsoup.parse(html)

        val mainSection = document.select("#main").first()?.children()
        val list: List<SearchResponse> = mainSection?.mapNotNull {
            if (it.tagName() == "article") it.toSearchResponse(false) else null
        } ?: emptyList()

        val pageNumbersIndex = if (page == 1) 1 else 3
        val pageNumbers = try {
            document.select("div.nav-links > a.page-numbers")[pageNumbersIndex].text().toInt()
        } catch (e: IndexOutOfBoundsException) {
            0
        }

        return newHomePageResponse(
            HomePageList(request.name, list, false),
            page < pageNumbers
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val html = fetch("$mainUrl?s=$query")
            val document = Jsoup.parse(html)
            val list = document.select("article").mapNotNull {
                if (it.tagName() == "article") it.toSearchResponse(true) else null
            }
            if (list.isNotEmpty()) return list
        } catch (e: Exception) {
            Log.d("ToonItalia", "Ricerca diretta fallita: ${e.message}")
        }
        return braveSearch(query)
    }

    private suspend fun braveSearch(query: String): List<SearchResponse> {
        val response = app.get("https://search.brave.com/search?q=${query}+site%3A${mainUrl.toHttpUrl().host}")
        val document = response.document
        val results = document.select("#results .snippet[data-type=\"web\"]").map { 
            it.selectFirst("a")?.attr("href") 
        }

        val excludedUrls = listOf(
            mainUrl + "category/anime/",
            mainUrl + "category/kids/",
            mainUrl + "film-anime/",
            mainUrl + "lista-anime-e-cartoni/",
            mainUrl + "lista-film-anime/",
            mainUrl + "lista-serietv-ragazzi/"
        )

        return results.amap { url ->
            if (url != null && excludedUrls.all { !it.contains(url) }) {
                val html = fetch(url)
                val r = Jsoup.parse(html)
                val title = r.select(".entry-title").text().trim()
                val poster = r.select(".attachment-post-thumbnail").attr("src")
                val type = if (r.select(".cat-links > a:nth-child(1)").text() == "") 
                    TvType.Movie else TvType.TvSeries

                if (type == TvType.TvSeries) {
                    newTvSeriesSearchResponse(title, url, type) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, url, type) { this.posterUrl = poster }
                }
            } else null
        }.filterNotNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val html = fetch(url)
        val response = Jsoup.parse(html)
        
        var title = response.select(".entry-title").text().trim()
        var year: Int? = null
        if (title.takeLast(4).all { it.isDigit() }) {
            year = title.takeLast(4).toInt()
            title = title.replace(Regex(""" ?[-–]? ?\d{4}$"""), "")
        }
        val plot = response.select(".entry-content > p:nth-child(2)").text().trim()
        val poster = response.select(".attachment-post-thumbnail").attr("src")
        val type = if (response.select(".cat-links > a:nth-child(1)").text() == "") 
            TvType.Movie else TvType.TvSeries
        
        return if (type == TvType.TvSeries) {
            val episodes = getEpisodes(url)
            year = response.select(".no-border > tbody:nth-child(2) > tr:nth-child(3) > td:nth-child(1)")
                .text().takeLast(4).toIntOrNull()
            val genres = response.select(".no-border > tbody:nth-child(2) > tr:nth-child(1) > td:nth-child(2)")
                .text().substringAfterLast("Genere: ").split(',')
            val rating = response.select(".no-border > tbody:nth-child(2) > tr:nth-child(4) > td:nth-child(1)")
                .text().substringAfterLast("Voto: ")
            
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.plot = plot
                this.year = year
                this.posterUrl = poster
                this.tags = genres
                
            }
        } else {
            newMovieLoadResponse(title, url, type,
                dataUrl = "$url€${response.select(".entry-content > table:nth-child(5) > tbody:nth-child(2) > tr:nth-child(1) > td > a")}"
            ) {
                this.plot = plot
                this.year = year
                this.posterUrl = poster
            }
        }
    }

    private suspend fun getEpisodes(url: String): List<Episode> {
        val html = fetch(url)
        val response = Jsoup.parse(html)
        val table = response.select(".table_link > thead:nth-child(2)")
        var season: Int? = 1
        
        return table.select("tr").mapNotNull {
            if (it.childrenSize() == 0) {
                null
            } else if (it.childrenSize() == 1) {
                val seasonText = it.select("td:nth-child(1)").text()
                season = Regex("""\d+""").find(seasonText)?.value?.toInt()
                null
            } else {
                val title = it.select("td:nth-child(1)").text()
                newEpisode("$url€${it.select("a")}") {
                    name = title
                    this.season = season
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkData = data.split("€")
        val soup = Jsoup.parse(linkData[1])
        soup.select("a").forEach {
            val link = it.attr("href")
            if (link.contains("uprot")) {
                val url = bypassUprot(link)
                if (url != null) {
                    if (url.contains("streamtape")) {
                        StreamTapeExtractor().getUrl(url, null, subtitleCallback, callback)
                    } else {
                        MaxStreamExtractor().getUrl(url, null, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    private suspend fun bypassUprot(link: String): String? {
        val updatedLink = if ("msf" in link) link.replace("msf", "mse") else link
        val response = app.get(updatedLink, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        ), timeout = 10_000)
        return Jsoup.parse(response.body.string()).selectFirst("a")?.attr("href")
    }

    private fun Element.toSearchResponse(fromSearch: Boolean): SearchResponse {
        val title = this.select("h2 > a").text().trim().replace(Regex(""" ?[-–]? ?\d{4}$"""), "")
        val url = this.select("h2 > a").attr("href")
        val footer = this.select("footer > span > a").text()

        val type = if (fromSearch) {
            try {
                this.select(".cat-links > a:nth-child(1)").text()
                TvType.TvSeries
            } catch (e: Exception) {
                TvType.Movie
            }
        } else {
            if (footer != "") TvType.TvSeries else TvType.Movie
        }

        val posterUrl = if (fromSearch) {
            this.select("header:nth-child(1) > div:nth-child(2) > p:nth-child(1) > a:nth-child(1) > img:nth-child(1)").attr("src")
        } else {
            this.select("header:nth-child(1) > p:nth-child(2) > a:nth-child(1) > img:nth-child(1)").attr("src")
        }

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, type) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, url, type) { this.posterUrl = posterUrl }
        }
    }
}
