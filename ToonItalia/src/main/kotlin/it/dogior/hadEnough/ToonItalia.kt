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
    override var sequentialMainPage = true

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
        
        /**
         * Costruisce l'URL del poster dal titolo e dalla data
         * Formato: https://toonitalia.xyz/wp-content/uploads/{anno}/{mese}/{titolo-con-trattini}.jpg
         */
        fun buildPosterUrl(title: String, date: String?): String? {
            if (date.isNullOrEmpty()) return null
            
            // Estrai anno e mese dalla data (es. "2025-10-04" o "04/10/2025")
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
            
            // Pulisci il titolo: rimuovi caratteri speciali e sostituisci spazi con trattini
            var cleanTitle = title
                .replace(Regex("""[<>:"/\\|?*'’!]"""), "") // rimuovi caratteri non validi
                .replace(Regex("""[&]"""), "-and-")        // & → -and-
                .replace(Regex("""[\s]+"""), "-")          // spazi → trattini
                .replace(Regex("""-+"""), "-")             // trattini multipli → singolo
                .trim('-')
                .lowercase()
            
            // Codifica URL
            cleanTitle = try {
                URLEncoder.encode(cleanTitle, "UTF-8")
                    .replace("+", "-")
                    .replace("%2C", ",")
                    .replace("%3A", ":")
                    .replace("%2F", "/")
                    .replace("%3F", "?")
                    .replace("%23", "#")
                    .replace("%5B", "[")
                    .replace("%5D", "]")
            } catch (e: Exception) {
                cleanTitle
            }
            
            return "https://toonitalia.xyz/wp-content/uploads/$year/$month/$cleanTitle.jpg"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        // CERCA I WIDGET CON I POSTER (rpwwt-widget)
        val widgets = document.select(".rpwwt-widget")
        
        val allItems = mutableListOf<SearchResponse>()
        
        for (widget in widgets) {
            val items = widget.select("li")
            for (item in items) {
                val link = item.selectFirst("a[href]") ?: continue
                val posterUrl = item.selectFirst("img")?.attr("src")
                val title = link.selectFirst(".rpwwt-post-title")?.text()?.trim() 
                    ?: link.text().trim()
                val itemUrl = link.attr("href")
                
                if (title.isBlank() || itemUrl.isBlank()) continue
                
                val isSeries = itemUrl.contains("serie") || request.data.contains("serie-tv")
                
                val searchResponse = if (isSeries) {
                    newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                        addPoster(posterUrl)
                    }
                } else {
                    newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                        addPoster(posterUrl)
                    }
                }
                allItems.add(searchResponse)
            }
        }
        
        // Se non troviamo widget, cerchiamo la lista testuale (fallback)
        if (allItems.isEmpty()) {
            val textLinks = document.select("ul.lcp_catlist li a")
            for (link in textLinks) {
                val title = link.text().trim()
                val itemUrl = link.attr("href")
                if (title.isBlank() || itemUrl.isBlank()) continue
                
                val isSeries = itemUrl.contains("serie") || request.data.contains("serie-tv")
                
                val searchResponse = if (isSeries) {
                    newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries)
                } else {
                    newMovieSearchResponse(title, itemUrl, TvType.Movie)
                }
                allItems.add(searchResponse)
            }
        }
        
        val hasNext = document.select("a.next.page-numbers, .next, .nav-links .next, .pagination .next").isNotEmpty()
        val section = HomePageList(request.name, allItems, false)
        return newHomePageResponse(section, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        
        val results = mutableListOf<SearchResponse>()
        
        val searchResults = document.select("article.post, div.post, .search-results article, .rpwwt-widget li")
        
        for (item in searchResults) {
            val link = item.selectFirst("a[href]") ?: continue
            val title = link.selectFirst(".rpwwt-post-title")?.text()?.trim() 
                ?: link.text().trim()
            val itemUrl = link.attr("href")
            val poster = item.selectFirst("img")?.attr("src") 
                ?: item.selectFirst("img[data-src]")?.attr("data-src")
            
            if (title.isBlank() || itemUrl.isBlank()) continue
            
            val isSeries = itemUrl.contains("serie") || itemUrl.contains("anime")
            
            if (isSeries) {
                results.add(newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    addPoster(poster)
                })
            } else {
                results.add(newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    addPoster(poster)
                })
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        
        // Cerca la data di pubblicazione
        val dateElement = document.selectFirst("time[datetime], .post-date, .date, .published")
        var date = dateElement?.attr("datetime")
        if (date.isNullOrEmpty()) {
            date = dateElement?.text()
        }
        
        // Prova a costruire il poster dall'URL pattern
        var poster = buildPosterUrl(title, date)
        
        // Fallback: cerca nel HTML
        if (poster == null) {
            poster = document.selectFirst("img.wp-post-image, img.attachment-post-thumbnail, .cover-header img")?.attr("src")
        }
        if (poster.isNullOrEmpty()) {
            poster = document.selectFirst(".cover-header")?.attr("style")?.let { style ->
                Regex("""background-image:\s*url\(['\"]?([^'\")]+)['\"]?\)""").find(style)?.groupValues?.get(1)
            }
        }
        
        val banner = poster
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()
        
        val voeLinks = document.select("a[href*='chuckle-tube.com'], a[href*='jessicaclearout.com']")
        val isSeries = voeLinks.size > 1 || document.text().contains("Episodio")
        
        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        
        return if (isSeries) {
            val episodes = getEpisodes(document)
            newTvSeriesLoadResponse(title, url, type, episodes) {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
            }
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val voeLinks = document.select("a[href*='chuckle-tube.com'], a[href*='jessicaclearout.com']")
        
        voeLinks.forEachIndexed { index, link ->
            val videoUrl = convertToVoeUrl(link.attr("href"))
            
            val parentText = link.parent()?.text() ?: ""
            val episodePattern = Regex("""(\d+)×(\d+)""")
            val seasonEpMatch = episodePattern.find(parentText)
            
            val episodeNum = if (seasonEpMatch != null) {
                seasonEpMatch.groupValues[2].toIntOrNull() ?: (index + 1)
            } else {
                index + 1
            }
            
            val episodeName = "Episodio $episodeNum"
            
            episodes.add(
                newEpisode(videoUrl) {
                    name = episodeName
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
