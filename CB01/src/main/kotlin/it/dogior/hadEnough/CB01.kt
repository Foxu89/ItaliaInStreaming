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
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.MaxStreamExtractor
import it.dogior.hadEnough.extractors.MixDropExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
        return title.replace(Regex("""[-–] Stagione \d+"""), "")
            .replace(Regex("""[-–] ITA"""), "")
            .replace(Regex("""[-–] *\d+[x×]\d*(/?\d*)*"""), "")
            .replace(Regex("""[-–] COMPLETA"""), "").trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}/page/$page/" else request.data
        val response = app.get(url)

        if (actualMainUrl.isEmpty()) {
            actualMainUrl = response.okhttpResponse.request.url.toString().substringBeforeLast('/')
        }

        val document = response.document
        val items = document.selectFirst(".sequex-one-columns")?.select(".post")
        if (items == null) {
            Log.d("CB01 Page Response", document.toString())
            return null
        }
        val posts = items.mapNotNull { card ->
            val poster = card.selectFirst("img")?.attr("src")
            val data = card.selectFirst("script")?.data()
            val fixedData = data?.substringAfter("=")?.substringBefore(";")
            val post = tryParseJson<Post>(fixedData)
            post?.let { it.poster = poster }
            post
        }
        val pagination = document.selectFirst(".pagination")?.select(".page-item")!!
        val lastPage = pagination[pagination.size - 2].text().replace(".", "").toInt()
        val hasNext = page < lastPage

        val searchResponses = posts.map {
            if (request.data.contains("serietv")) {
                val title = fixTitle(it.title, false)
                newTvSeriesSearchResponse(title, it.permalink, TvType.TvSeries) {
                    addPoster(it.poster)
                }
            } else {
                val quality = if (it.title.contains("HD")) SearchQuality.HD else null
                newMovieSearchResponse(
                    fixTitle(it.title, true),
                    it.permalink,
                    TvType.Movie
                ) {
                    addPoster(it.poster)
                    this.quality = quality
                }
            }
        }
        val section = HomePageList(request.name, searchResponses, false)
        return newHomePageResponse(section, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchLinks = listOf("$mainUrl/?s=$query", "$mainUrl/serietv/?s=$query")
        val results = searchLinks.amap { link ->
            val response = app.get(link)
            val document = response.document
            val itemColumn = document.selectFirst(".sequex-one-columns")
            val items = itemColumn?.select(".post")
            val posts = items?.mapNotNull { card ->
                val poster = card.selectFirst("img")?.attr("src")
                val data = card.selectFirst("script")?.data()
                val fixedData = data?.substringAfter("=")?.substringBefore(";")
                val post = tryParseJson<Post>(fixedData)
                post?.let { it.poster = poster }
                post
            }
            posts?.map {
                if (link.contains("serietv")) {
                    newTvSeriesSearchResponse(
                        fixTitle(it.title, false),
                        it.permalink,
                        TvType.TvSeries
                    ) {
                        addPoster(it.poster)
                    }
                } else {
                    val quality = if (it.title.contains("HD")) SearchQuality.HD else null
                    newMovieSearchResponse(
                        fixTitle(it.title, true),
                        it.permalink,
                        TvType.Movie
                    ) {
                        addPoster(it.poster)
                        this.quality = quality
                    }
                }
            }
        }.filterNotNull().flatten()
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val mainContainer = document.selectFirst(".sequex-main-container")
        if (mainContainer == null) {
            Log.d("CB01", document.toString())
            return null
        }
        val poster = mainContainer.selectFirst("img.responsive-locandina")?.attr("src")
        val banner = mainContainer.selectFirst("#sequex-page-title-img")?.attr("data-img")
        val title = mainContainer.selectFirst("h1")?.text()!!
        val isMovie = !url.contains("serietv")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (isMovie) {
            val year = Regex("\\d{4}").find(title)?.value?.toIntOrNull()
            val plot = mainContainer.selectFirst(".ignore-css > p:nth-child(2)")?.text()
                ?.replace("+Info »", "")
            val tags = mainContainer.selectFirst(".ignore-css > p:nth-child(1) > strong:nth-child(1)")
                ?.text()?.split('–')
            val runtime = tags?.find { it.contains("DURATA") }?.trim()
                ?.removePrefix("DURATA")
                ?.removeSuffix("′")?.trim()?.toInt()

            val table = mainContainer.selectFirst("table.cbtable")
            val movieLinks = table?.select("a")?.filter {
                it.text().contains("Mixdrop", true) || it.text().contains("Maxstream", true)
            }?.map { it.attr("href") } ?: emptyList()

            newMovieLoadResponse(fixTitle(title, true), url, type, movieLinks.toJson()) {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
                this.tags = tags?.mapNotNull {
                    if (it.contains("DURATA")) null else it.trim()
                }
                this.duration = runtime
                this.year = year
            }
        } else {
            val description = mainContainer.selectFirst(".ignore-css > p:nth-child(1)")?.text()
                ?.split(Regex("""\(\d{4}-(\d{4})?\)"""))
            val plot = description?.last()?.trim()
            val tags = description?.first()?.split('/')
            val (episodes, seasons) = getEpisodes(document)
            newTvSeriesLoadResponse(fixTitle(title, false), url, type, episodes) {
                addPoster(poster)
                addSeasonNames(seasons)
                this.plot = plot
                this.backgroundPosterUrl = banner
                this.tags = tags?.map { it.trim() }
            }
        }
    }

    private suspend fun getEpisodes(page: Document): Pair<List<Episode>, List<SeasonData>> {
        val seasonsData = mutableListOf<SeasonData>()
        val episodeList = mutableListOf<Episode>()
        val seasonDropdowns = page.select(".sp-wrap")

        seasonDropdowns.forEachIndexed { index, dropdown ->
            val seasonName = dropdown.selectFirst("div.sp-head")?.text() ?: "Stagione ${index + 1}"
            val seasonNumber = "\\d+".toRegex().find(seasonName)?.value?.toIntOrNull() ?: (index + 1)
            seasonsData.add(SeasonData(seasonNumber, seasonName.replace("- ITA", "").replace("- HD", "").trim()))

            dropdown.select("div.sp-body p, div.sp-body li").forEach { line ->
                val lineText = line.text()
                if (lineText.contains("×") || lineText.contains("x")) {
                    var epName = lineText.substringBefore("–").trim()
                    val epNumber = "\\d+".toRegex().find(lineText.substringAfter("×"))?.value?.toIntOrNull()

                    val linksInRow = line.select("a[href*=/l/], a[href*=stayonline], a[href*=uprot]")
                        .map { it.attr("href") }

                    if (linksInRow.isNotEmpty()) {
                        val finalData: String

                        if (linksInRow.size >= 2) {
                            finalData = listOf(linksInRow[1]).toJson()
                        } else {
                            finalData = linksInRow.toJson()
                            epName = "$epName [LINK NON SUPPORTATO]"
                        }

                        episodeList.add(newEpisode(finalData) {
                            this.name = epName
                            this.season = seasonNumber
                            this.episode = epNumber
                        })
                    }
                }
            }
        }
        return episodeList to seasonsData
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data == "null" || data.isEmpty()) return false
        val links = parseJson<List<String>>(data)

        links.forEach { link ->
            try {
                var finalUrl: String? = null
                when {
                    link.contains("stayonline") -> finalUrl = bypassStayOnline(link)
                    link.contains("uprot") -> finalUrl = unshortenUprot(link)
                }

                finalUrl?.let { url ->
                    when {
                        url.contains("maxstream") -> MaxStreamExtractor().getUrl(url, null, subtitleCallback, callback)
                        url.contains("mixdrop") || url.contains("m1xdrop") -> MixDropExtractor().getUrl(url, null, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e("CB01", "Errore link: ${e.message}")
            }
        }
        return true
    }

    private suspend fun unshortenUprot(url: String): String {
        var currentUrl = url
        val visited = mutableSetOf<String>()
        while (currentUrl.contains("uprot.net") && currentUrl !in visited) {
            visited.add(currentUrl)
            val response = app.get(currentUrl, allowRedirects = false)
            currentUrl = response.headers["location"] ?: break
        }
        return currentUrl
    }

    private suspend fun bypassStayOnline(link: String): String? {
        val pageHtml = app.get(link).text
        val idMatch = Regex("""var linkId\s*=\s*"([^"]+)";""").find(pageHtml)
        val linkId = idMatch?.groupValues?.get(1) ?: link.split("/").last()

        val response = app.post(
            "https://stayonline.pro/ajax/linkView.php",
            headers = mapOf("Referer" to link, "X-Requested-With" to "XMLHttpRequest"),
            requestBody = "id=$linkId&ref=".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        ).text

        return try {
            val json = JSONObject(response)
            if (json.optString("status") == "success") {
                val realUrl = json.getJSONObject("data").getString("value")
                if (realUrl.contains("m1xdrop.net/f/")) "https://mixdrop.top/e/${realUrl.substringAfterLast("/")}" else realUrl
            } else null
        } catch (e: Exception) {
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
