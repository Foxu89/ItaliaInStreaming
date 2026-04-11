package it.dogior.hadEnough

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.VOEExtractor
import java.net.URLEncoder

class Toonitalia : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toonitalia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true
    override var sequentialMainPage = false

    override val mainPage = mainPageOf(
        "$mainUrl/anime-ita/" to "Anime ITA",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/film-animazione/" to "Film Animazione"
    )

    companion object {
        fun convertToVoeUrl(url: String): String {
            if (url.contains("chuckle-tube.com")) {
                val videoId = url.substringAfterLast("/")
                return "https://jessicaclearout.com/$videoId"
            }
            return url
        }
        
        fun buildPosterUrl(title: String, date: String?): String? {
            if (date.isNullOrEmpty()) return null
            val datePattern1 = Regex("""(\d{4})-(\d{2})-\d{2}""")
            val datePattern2 = Regex("""\d{2}/(\d{2})/(\d{4})""")
            var year: String? = null
            var month: String? = null
            datePattern1.find(date)?.let { match ->
                year = match.groupValues[1]
                month = match.groupValues[2]
            }
            if (year == null) {
                datePattern2.find(date)?.let { match ->
                    year = match.groupValues[2]
                    month = match.groupValues[1]
                }
            }
            if (year == null || month == null) return null
            var cleanTitle = title
                .replace(Regex("""[<>:"/\\|?*'’!]"""), "")
                .replace(Regex("""[&]"""), "-and-")
                .replace(Regex("""[\s]+"""), "-")
                .replace(Regex("""-+"""), "-")
                .trim('-')
                .lowercase()
            cleanTitle = try {
                URLEncoder.encode(cleanTitle, "UTF-8")
                    .replace("+", "-")
                    .replace("%2C", ",")
                    .replace("%3A", ":")
                    .replace("%2F", "/")
            } catch (e: Exception) {
                cleanTitle
            }
            return "https://toonitalia.xyz/wp-content/uploads/$year/$month/$cleanTitle.jpg"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val items = document.select("article.post")
        val home = mutableListOf<SearchResponse>()
        
        items.forEach { item ->
            val link = item.selectFirst("a[href]") ?: return@forEach
            val img = item.selectFirst("img")?.attr("src") 
                ?: item.selectFirst("img")?.attr("data-src") 
                ?: item.selectFirst("img")?.attr("data-lazy-src")
            
            val title = item.selectFirst(".entry-title, h2, h3")?.text()?.trim() 
                ?: link.text().trim()
            
            val itemUrl = link.attr("href")
            
            if (title.isNotBlank() && itemUrl.isNotBlank()) {
                val type = if (itemUrl.contains("/serie-tv/")) TvType.TvSeries else TvType.Movie
                val res = if (type == TvType.TvSeries) {
                    newTvSeriesSearchResponse(title, itemUrl, type) { addPoster(img) }
                } else {
                    newMovieSearchResponse(title, itemUrl, type) { addPoster(img) }
                }
                home.add(res)
            }
        }
        
        if (home.isEmpty()) {
            document.select("ul.lcp_catlist li a").forEach { link ->
                val title = link.text().trim()
                val itemUrl = link.attr("href")
                if (title.isNotBlank() && itemUrl.isNotBlank()) {
                    val type = if (request.data.contains("serie-tv")) TvType.TvSeries else TvType.Movie
                    val res = if (type == TvType.TvSeries) {
                        newTvSeriesSearchResponse(title, itemUrl, type)
                    } else {
                        newMovieSearchResponse(title, itemUrl, type)
                    }
                    home.add(res)
                }
            }
        }
        
        val hasNext = document.select("a.next.page-numbers").isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, home, false), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        
        return document.select("article.post").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val title = item.selectFirst(".entry-title, h2")?.text()?.trim() ?: link.text().trim()
            val itemUrl = link.attr("href")
            val poster = item.selectFirst("img")?.attr("src") 
                ?: item.selectFirst("img")?.attr("data-src")
            
            if (title.isBlank() || itemUrl.isBlank()) return@mapNotNull null
            
            val type = if (itemUrl.contains("film-animazione")) TvType.Movie else TvType.TvSeries
            
            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, itemUrl, type) { addPoster(poster) }
            } else {
                newMovieSearchResponse(title, itemUrl, type) { addPoster(poster) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        
        val dateElement = document.selectFirst("time[datetime]")
        val date = dateElement?.attr("datetime") ?: dateElement?.text()
        
        var poster = buildPosterUrl(title, date)
        if (poster.isNullOrEmpty()) {
            poster = document.selectFirst(".wp-post-image, .attachment-post-thumbnail")?.attr("src")
        }
        if (poster.isNullOrEmpty()) {
            poster = document.selectFirst(".cover-header")?.attr("style")?.let { style ->
                Regex("""background-image:\s*url\(['\"]?([^'\")]+)['\"]?\)""").find(style)?.groupValues?.get(1)
            }
        }
        
        val plot = document.select(".entry-content p").firstOrNull()?.text()?.trim()
        
        val voeLinks = document.select("a[href*='chuckle-tube.com']")
        val isSeries = voeLinks.size > 1 || document.text().contains("Episodio") || url.contains("serie-tv")
        
        return if (isSeries) {
            val episodes = getEpisodes(document)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                addPoster(poster)
                this.plot = plot
            }
        } else {
            val dataUrl = voeLinks.firstOrNull()?.attr("href") ?: url
            newMovieLoadResponse(title, url, TvType.Movie, convertToVoeUrl(dataUrl)) {
                addPoster(poster)
                this.plot = plot
            }
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val voeLinks = document.select("a[href*='chuckle-tube.com']")
        
        voeLinks.forEachIndexed { index, link ->
            val videoUrl = convertToVoeUrl(link.attr("href"))
            val parentText = link.parent()?.text() ?: ""
            val seasonEpMatch = Regex("""(\d+)[×x](\d+)""").find(parentText)
            
            val episodeNum = if (seasonEpMatch != null) {
                seasonEpMatch.groupValues[2].toIntOrNull() ?: (index + 1)
            } else {
                index + 1
            }
            
            episodes.add(
                newEpisode(videoUrl) {
                    name = "Episodio $episodeNum"
                    episode = episodeNum
                }
            )
        }
        return episodes.sortedBy { it.episode }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val videoUrl = convertToVoeUrl(data)
        if (videoUrl.contains("jessicaclearout.com")) {
            VOEExtractor().getUrl(videoUrl, null, subtitleCallback, callback)
        }
        return true
    }
}
