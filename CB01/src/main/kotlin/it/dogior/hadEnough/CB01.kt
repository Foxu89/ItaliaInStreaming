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
        if (isMovie) {
            return title.replace(Regex("""(\[HD] )*\(\d{4}\)${'$'}"""), "")
        }
        return title.replace(Regex("""[-–] Stagione \d+.*${'$'}"""), "")
            .replace(Regex("""[-–] ITA.*${'$'}"""), "")
            .replace(Regex("""[-–] *\d+[x×]\d*(/?\d*)*.*${'$'}"""), "")
            .replace(Regex("""[-–] COMPLETA.*${'$'}"""), "").trim()
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
            // ========== FILM ==========
            val year = Regex("\\d{4}").find(title)?.value?.toIntOrNull()
            val plot = mainContainer.selectFirst(".ignore-css > p:nth-child(2)")?.text()
                ?.replace("+Info »", "")
            val tags = mainContainer.selectFirst(".ignore-css > p:nth-child(1) > strong:nth-child(1)")
                ?.text()?.split('–')
            val runtime = tags?.find { it.contains("DURATA") }?.trim()
                ?.removePrefix("DURATA")
                ?.removeSuffix("′")?.trim()?.toIntOrNull()

            val mixdropLink = mainContainer.selectFirst("a[href*='stayonline.pro']")?.attr("href")
            
            newMovieLoadResponse(fixTitle(title, true), url, TvType.Movie, mixdropLink ?: "null") {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
                this.tags = tags?.mapNotNull { if (it.contains("DURATA")) null else it.trim() }
                this.duration = runtime
                this.year = year
            }
        } else {
            // ========== SERIE TV ==========
            val description = mainContainer.selectFirst(".ignore-css > p:nth-child(1)")?.text()
                ?.split(Regex("""\(\d{4}-\d{4}\)"""))
            val plot = description?.lastOrNull()?.trim()
            val tags = description?.firstOrNull()?.split('/')?.map { it.trim() }
            
            val (episodes, seasons) = extractEpisodes(document)
            
            newTvSeriesLoadResponse(fixTitle(title, false), url, TvType.TvSeries, episodes) {
                addPoster(poster)
                addSeasonNames(seasons)
                this.plot = plot
                this.backgroundPosterUrl = banner
                this.tags = tags
            }
        }
    }
    
    // ========== ESTRAZIONE EPISODI CORRETTA ==========
    private fun extractEpisodes(document: org.jsoup.nodes.Document): Pair<List<Episode>, MutableList<SeasonData>> {
        val episodes = mutableListOf<Episode>()
        val seasonsData = mutableListOf<SeasonData>()
        val seasons = mutableMapOf<Int, String>()
        
        val seasonWraps = document.select(".sp-wrap")
        
        seasonWraps.forEachIndexed { index, wrap ->
            val seasonHeader = wrap.selectFirst(".sp-head")?.text() ?: return@forEachIndexed
            val seasonNumber = Regex("""STAGIONE\s*(\d+)""").find(seasonHeader.uppercase())?.groupValues?.get(1)?.toIntOrNull() 
                ?: (index + 1)
            
            val seasonName = seasonHeader.replace("- ITA", "").replace("- HD", "").trim()
            seasons[seasonNumber] = seasonName
            
            // Cerca gli episodi in tutto il wrap (anche se la tendina è chiusa)
            val episodeElements = wrap.select("p")
            
            episodeElements.forEach { epElement ->
                val epText = epElement.text()
                val epMatch = Regex("""(\d+)×(\d+)""").find(epText)
                
                if (epMatch != null) {
                    val epSeason = epMatch.groupValues[1].toIntOrNull() ?: seasonNumber
                    val epNumber = epMatch.groupValues[2].toIntOrNull() ?: return@forEach
                    val epName = epText.substringBefore("–").trim()
                    
                    val mixdropLink = epElement.select("a[href*='stayonline.pro']").lastOrNull()?.attr("href")
                    
                    if (mixdropLink != null) {
                        episodes.add(
                            newEpisode(mixdropLink) {
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
        if (data == "null") return false
        
        val links = try {
            parseJson<List<String>>(data)
        } catch (e: Exception) {
            listOf(data)
        }
        
        links.forEach { link ->
            var finalUrl: String? = link
            
            if (link.contains("stayonline.pro")) {
                finalUrl = bypassStayOnline(link)
            }
            
            finalUrl?.let { url ->
                Log.d("CB01", "Final URL: $url")
                
                when {
                    url.contains("mixdrop") || url.contains("m1xdrop") -> {
                        MixDropExtractor().getUrl(url, null, subtitleCallback, callback)
                    }
                }
            }
        }
        
        return true
    }

    // ========== BYPASS STAYONLINE ==========
    private suspend fun bypassStayOnline(link: String): String? {
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
                realUrl
            } else {
                null
            }
        } catch (e: JSONException) {
            Log.e("CB01:StayOnline", "JSON error: ${e.message}")
            null
        }
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
