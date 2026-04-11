package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ShortLink
import it.dogior.hadEnough.extractors.MaxStreamExtractor
import it.dogior.hadEnough.extractors.MixDropExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class CB01 : MainAPI() {
    override var mainUrl = "https://cb01uno.today"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        mainUrl to "Film",
        "$mainUrl/serietv" to "Serie TV"
    )

    companion object {
        var actualMainUrl = ""
    }

    private fun fixTitle(title: String, isMovie: Boolean): String {
        return if (isMovie) {
            title.replace(Regex("""(\[HD] )?\(\d{4}\)${'$'}"""), "").trim()
        } else {
            title.replace(Regex("""[-–] Stagione \d+.*${'$'}"""), "")
                .replace(Regex("""[-–] \d+[x×]\d+.*${'$'}"""), "")
                .replace(Regex("""[-–] ITA.*${'$'}"""), "")
                .replace(Regex("""[-–] COMPLETA.*${'$'}"""), "")
                .trim()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}/page/$page/" else request.data
        val response = app.get(url)

        if (actualMainUrl.isEmpty()) {
            actualMainUrl = response.url.substringBeforeLast('/')
        }

        val document = response.document
        val items = document.selectFirst(".sequex-one-columns")?.select(".post") ?: return null
        
        val posts = items.mapNotNull { card ->
            val poster = card.selectFirst("img")?.attr("src")
            val data = card.selectFirst("script")?.data()
            val fixedData = data?.substringAfter("=")?.substringBefore(";")
            val post = tryParseJson<Post>(fixedData)
            post?.let { it.poster = poster }
            post
        }
        
        val pagination = document.selectFirst(".pagination")?.select(".page-item") ?: return null
        val lastPage = pagination[pagination.size - 2].text().replace(".", "").toIntOrNull() ?: 1
        val hasNext = page < lastPage

        val searchResponses = posts.map { post ->
            if (request.data.contains("serietv")) {
                newTvSeriesSearchResponse(fixTitle(post.title, false), post.permalink, TvType.TvSeries) {
                    addPoster(post.poster)
                }
            } else {
                val quality = if (post.title.contains("HD")) SearchQuality.HD else null
                newMovieSearchResponse(fixTitle(post.title, true), post.permalink, TvType.Movie) {
                    addPoster(post.poster)
                    this.quality = quality
                }
            }
        }
        
        return newHomePageResponse(HomePageList(request.name, searchResponses, false), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchLinks = listOf("$mainUrl/?s=$query", "$mainUrl/serietv/?s=$query")
        return searchLinks.amap { link ->
            val document = app.get(link).document
            val items = document.selectFirst(".sequex-one-columns")?.select(".post") ?: return@amap emptyList()
            
            items.mapNotNull { card ->
                val poster = card.selectFirst("img")?.attr("src")
                val data = card.selectFirst("script")?.data()
                val fixedData = data?.substringAfter("=")?.substringBefore(";")
                val post = tryParseJson<Post>(fixedData)
                post?.let { it.poster = poster }
                post
            }.map { post ->
                if (link.contains("serietv")) {
                    newTvSeriesSearchResponse(fixTitle(post.title, false), post.permalink, TvType.TvSeries) {
                        addPoster(post.poster)
                    }
                } else {
                    val quality = if (post.title.contains("HD")) SearchQuality.HD else null
                    newMovieSearchResponse(fixTitle(post.title, true), post.permalink, TvType.Movie) {
                        addPoster(post.poster)
                        this.quality = quality
                    }
                }
            }
        }.flatten()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val mainContainer = document.selectFirst(".sequex-main-container") ?: return null
        
        val poster = mainContainer.selectFirst("img.responsive-locandina")?.attr("src")
        val banner = mainContainer.selectFirst("#sequex-page-title-img")?.attr("data-img")
        val title = mainContainer.selectFirst("h1")?.text() ?: return null
        val isMovie = !url.contains("serietv")
        
        return if (isMovie) {
            loadMovieResponse(mainContainer, title, url, poster, banner)
        } else {
            loadTvSeriesResponse(mainContainer, document, title, url, poster, banner)
        }
    }
    
    private suspend fun loadMovieResponse(
        mainContainer: org.jsoup.nodes.Element,
        title: String,
        url: String,
        poster: String?,
        banner: String?
    ): LoadResponse {
        val year = Regex("\\d{4}").find(title)?.value?.toIntOrNull()
        val plot = mainContainer.selectFirst(".ignore-css > p:nth-child(2)")?.text()
            ?.replace("+Info »", "")
        val tags = mainContainer.selectFirst(".ignore-css > p:nth-child(1) > strong:nth-child(1)")
            ?.text()?.split("–")
        val runtime = tags?.find { it.contains("DURATA") }?.trim()
            ?.removePrefix("DURATA")
            ?.removeSuffix("′")?.trim()?.toIntOrNull()

        val links = mutableListOf<String>()
        mainContainer.select("table.cbtable a[href*='stayonline.pro']").forEach { a ->
            links.add(a.attr("href"))
        }

        return newMovieLoadResponse(fixTitle(title, true), url, TvType.Movie, links.toJson()) {
            addPoster(poster)
            this.plot = plot
            this.backgroundPosterUrl = banner
            this.tags = tags?.mapNotNull { if (it.contains("DURATA")) null else it.trim() }
            this.duration = runtime
            this.year = year
        }
    }
    
    private suspend fun loadTvSeriesResponse(
        mainContainer: org.jsoup.nodes.Element,
        document: org.jsoup.nodes.Document,
        title: String,
        url: String,
        poster: String?,
        banner: String?
    ): LoadResponse {
        val description = mainContainer.selectFirst(".ignore-css > p:nth-child(1)")?.text()
            ?.split(Regex("""\(\d{4}-\d{4}\)"""))
        val plot = description?.lastOrNull()?.trim()
        val tags = description?.firstOrNull()?.split('/')?.map { it.trim() }
        
        val (episodes, seasons) = extractEpisodes(document)
        
        return newTvSeriesLoadResponse(fixTitle(title, false), url, TvType.TvSeries, episodes) {
            addPoster(poster)
            addSeasonNames(seasons)
            this.plot = plot
            this.backgroundPosterUrl = banner
            this.tags = tags
        }
    }
    
    private fun extractEpisodes(document: org.jsoup.nodes.Document): Pair<List<Episode>, MutableList<SeasonData>> {
        val episodes = mutableListOf<Episode>()
        val seasonsData = mutableListOf<SeasonData>()
        val seasons = mutableMapOf<Int, String>()
        
        val seasonWraps = document.select(".sp-wrap")
        
        seasonWraps.forEachIndexed { index, wrap ->
            val seasonHeader = wrap.selectFirst(".sp-head")?.text() ?: return@forEachIndexed
            val seasonNumber = Regex("""STAGIONE\s*(\d+)""").find(seasonHeader)?.groupValues?.get(1)?.toIntOrNull() 
                ?: (index + 1)
            
            val seasonName = seasonHeader.replace("- ITA", "").replace("- HD", "").trim()
            seasons[seasonNumber] = seasonName
            
            val episodeElements = wrap.select(".sp-body p")
            
            episodeElements.forEach { epElement ->
                val epText = epElement.text()
                val epMatch = Regex("""(\d+)×(\d+)""").find(epText)
                
                if (epMatch != null) {
                    val epSeason = epMatch.groupValues[1].toIntOrNull() ?: seasonNumber
                    val epNumber = epMatch.groupValues[2].toIntOrNull() ?: return@forEach
                    val epName = epText.substringBefore("–").trim()
                    
                    val links = epElement.select("a[href*='stayonline.pro']").map { it.attr("href") }
                    
                    if (links.isNotEmpty()) {
                        episodes.add(
                            newEpisode(links.toJson()) {
                                name = epName
                                season = epSeason
                                episode = epNumber
                            }
                        )
                    }
                }
            }
        }
        
        seasons.forEach { (num, name) ->
            seasonsData.add(SeasonData(num, name))
        }
        
        return episodes to seasonsData
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data == "null" || data == "[]") return false
        
        val links = try {
            parseJson<List<String>>(data)
        } catch (e: Exception) {
            Log.e("CB01", "Failed to parse links: ${e.message}")
            return false
        }
        
        Log.d("CB01", "Processing links: $links")
        
        links.forEach { link ->
            ioSafe {
                try {
                    val finalUrl = when {
                        link.contains("stayonline.pro") -> bypassStayOnline(link)
                        link.contains("uprot.net") -> bypassUprot(link)
                        else -> link
                    }
                    
                    if (finalUrl != null) {
                        Log.d("CB01", "Final URL: $finalUrl")
                        
                        when {
                            finalUrl.contains("maxstream") || finalUrl.contains("uprot.stream") -> {
                                MaxStreamExtractor().getUrl(finalUrl, null, subtitleCallback, callback)
                            }
                            finalUrl.contains("mixdrop") || finalUrl.contains("m1xdrop") -> {
                                MixDropExtractor().getUrl(finalUrl, null, subtitleCallback, callback)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CB01", "Error processing link $link: ${e.message}")
                }
            }
        }
        
        return true
    }

    private suspend fun bypassStayOnline(link: String): String? {
        Log.d("CB01:StayOnline", "Processing: $link")
        
        val pageResponse = app.get(link)
        val pageHtml = pageResponse.body.string()
        
        var linkId = link.substringAfterLast("/")
        val idPattern = Regex("""var linkId\s*=\s*"([^"]+)";""")
        val idMatch = idPattern.find(pageHtml)
        if (idMatch != null) {
            linkId = idMatch.groupValues[1]
        }
        
        val headers = mapOf(
            "Origin" to "https://stayonline.pro",
            "Referer" to link,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        
        val data = "id=$linkId&ref="
        val response = app.post(
            "https://stayonline.pro/ajax/linkView.php",
            headers = headers,
            requestBody = data.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
        )

        val jsonResponse = response.body.string()
        
        return try {
            val json = JSONObject(jsonResponse)
            if (json.optString("status") == "success") {
                var realUrl = json.getJSONObject("data").getString("value")
                
                if (realUrl.contains("m1xdrop.net/f/")) {
                    val videoId = realUrl.substringAfterLast("/")
                    realUrl = "https://mixdrop.top/e/$videoId"
                }
                if (realUrl.contains("uprot.net")) {
                    realUrl = unshortenUprot(realUrl)
                }
                realUrl
            } else {
                null
            }
        } catch (e: JSONException) {
            Log.e("CB01:StayOnline", "JSON error: ${e.message}")
            null
        }
    }
    
    private suspend fun unshortenUprot(url: String): String {
        var currentUrl = url
        val visited = mutableSetOf<String>()
        
        while (currentUrl.contains("uprot.net") && currentUrl !in visited) {
            visited.add(currentUrl)
            val response = app.get(currentUrl, allowRedirects = false)
            val location = response.headers["location"]
            
            if (location != null) {
                currentUrl = location
            } else {
                break
            }
        }
        return currentUrl
    }

    private suspend fun bypassUprot(link: String): String? {
        val updatedLink = if ("msf" in link) link.replace("msf", "mse") else link
        
        val response = app.get(updatedLink, timeout = 10_000)
        val document = Jsoup.parse(response.body.string())
        
        return document.selectFirst("a")?.attr("href")
    }

    data class Post(
        @JsonProperty("id") val id: String,
        @JsonProperty("popup") val popup: String,
        @JsonProperty("unique_id") val uniqueId: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("permalink") val permalink: String,
        @JsonProperty("item_id") val itemId: String,
        var poster: String? = null,
    )
}
