package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.addSeasonNames
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
import com.lagradost.cloudstream3.utils.Qualities
import it.dogior.hadEnough.extractors.VOEExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
        // Converte chuckle-tube.com/ID in jessicaclearout.com/ID
        fun convertToVoeUrl(url: String): String {
            if (url.contains("chuckle-tube.com")) {
                val videoId = url.substringAfterLast("/")
                return "https://jessicaclearout.com/$videoId"
            }
            return url
        }
        
        // Estrae il poster da un elemento card
        private fun extractPoster(card: Element): String? {
            // Cerca img normale
            var poster = card.selectFirst("img.wp-post-image, img.attachment-post-thumbnail, img")?.attr("src")
            if (!poster.isNullOrEmpty()) return poster
            
            // Cerca img con data-src (lazy loading)
            poster = card.selectFirst("img[data-src]")?.attr("data-src")
            if (!poster.isNullOrEmpty()) return poster
            
            // Cerca nell'attributo style (background-image)
            val style = card.selectFirst(".post-thumbnail, .cover-header")?.attr("style")
            if (style != null) {
                val regex = Regex("""background-image:\s*url\(['\"]?([^'\")]+)['\"]?\)""")
                poster = regex.find(style)?.groupValues?.get(1)
                if (!poster.isNullOrEmpty()) return poster
            }
            
            return null
        }
        
        // Estrae il titolo da un elemento card
        private fun extractTitle(card: Element): String? {
            return card.selectFirst("h2.entry-title, h1.entry-title, h2 a, h1 a, .entry-title a")?.text()?.trim()
                ?: card.selectFirst("a[href]")?.text()?.trim()
        }
        
        // Estrae il link da un elemento card
        private fun extractLink(card: Element): String? {
            return card.selectFirst("h2.entry-title a, h1.entry-title a, .entry-title a, a[href]")?.attr("href")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        // Cerca gli articoli/posts
        val items = document.select("article.post, div.post, div.entry-content article, div.rpwwt-widget li")
        
        if (items.isEmpty()) {
            // Prova con i widget recent posts
            val widgetItems = document.select(".rpwwt-widget li, .recent-posts-widget-with-thumbnails li")
            if (widgetItems.isNotEmpty()) {
                return buildHomePageFromWidgets(widgetItems, request, page, url)
            }
            return null
        }
        
        val posts = items.mapNotNull { card ->
            val title = extractTitle(card) ?: return@mapNotNull null
            val link = extractLink(card) ?: return@mapNotNull null
            val poster = extractPoster(card)
            
            val isSeries = link.contains("serie") || link.contains("anime") || request.data.contains("serie-tv")
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    addPoster(poster)
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    addPoster(poster)
                }
            }
        }
        
        // Controlla se esiste una pagina successiva
        val hasNext = document.select("a.next.page-numbers, .next, .nav-links .next, .pagination .next").isNotEmpty()
        
        val section = HomePageList(request.name, posts, false)
        return newHomePageResponse(section, hasNext)
    }
    
    private suspend fun buildHomePageFromWidgets(
        items: org.jsoup.select.Elements,
        request: MainPageRequest,
        page: Int,
        url: String
    ): HomePageResponse? {
        val posts = items.mapNotNull { card ->
            val title = card.selectFirst("a")?.text()?.trim() ?: return@mapNotNull null
            val link = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.attr("src") ?: card.selectFirst("img[data-src]")?.attr("data-src")
            
            val isSeries = link.contains("serie") || link.contains("anime") || request.data.contains("serie-tv")
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    addPoster(poster)
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    addPoster(poster)
                }
            }
        }
        
        val hasNext = false // Nei widget non c'è paginazione
        val section = HomePageList(request.name, posts, false)
        return newHomePageResponse(section, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        
        val items = document.select("article.post, div.post, div.search-results article, .rpwwt-widget li")
        
        return items.mapNotNull { card ->
            val title = extractTitle(card) ?: return@mapNotNull null
            val link = extractLink(card) ?: return@mapNotNull null
            val poster = extractPoster(card)
            
            val isSeries = link.contains("serie") || link.contains("anime")
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    addPoster(poster)
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    addPoster(poster)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        
        // Poster dalla pagina (banner o immagine principale)
        var poster = document.selectFirst("img.wp-post-image, img.attachment-post-thumbnail, .post-thumbnail img")?.attr("src")
        if (poster.isNullOrEmpty()) {
            poster = document.selectFirst(".cover-header")?.attr("style")?.let { style ->
                Regex("""background-image:\s*url\(['\"]?([^'\")]+)['\"]?\)""").find(style)?.groupValues?.get(1)
            }
        }
        
        val banner = poster
        
        val plot = document.selectFirst(".entry-content p, .post-content p")?.text()?.trim()
        
        // Determina se è una serie (ha episodi)
        val isSeries = document.selectFirst(".entry-content h3, .entry-content h2")?.text()?.contains("Episodi") == true
            || url.contains("serie") || url.contains("anime")
            || document.select("a[href*='chuckle-tube.com']").isNotEmpty()
        
        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        
        return if (isSeries) {
            val episodes = getEpisodes(document)
            newTvSeriesLoadResponse(title, url, type, episodes) {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
            }
        } else {
            newMovieLoadResponse(title, url, type) {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
            }
        }
    }

    private suspend fun getEpisodes(document: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Cerca i link VOE nel contenuto
        val content = document.selectFirst(".entry-content")?.html() ?: return episodes
        
        // Pattern per trovare i link VOE: https://chuckle-tube.com/ID
        val voePattern = Regex("""(?:https?://)?(?:chuckle-tube\.com|jessicaclearout\.com)/([a-zA-Z0-9]+)""")
        val matches = voePattern.findAll(content).toList()
        
        // Cerca anche i numeri degli episodi
        val episodePattern = Regex("""(?:Ep|Episodio|E|×)\s*(\d+)""", RegexOption.IGNORE_CASE)
        val episodePattern2 = Regex("""(\d+)×(\d+)""") // Formato "5x01"
        
        matches.forEachIndexed { index, match ->
            val videoUrl = convertToVoeUrl("https://jessicaclearout.com/${match.groupValues[1]}")
            
            // Cerca il numero dell'episodio intorno alla posizione del match
            val contextStart = maxOf(0, match.range.first - 200)
            val contextEnd = minOf(content.length, match.range.last + 200)
            val context = content.substring(contextStart, contextEnd)
            
            var episodeNum: Int? = null
            
            // Prova pattern 5x01
            val seasonEpMatch = episodePattern2.find(context)
            if (seasonEpMatch != null) {
                episodeNum = seasonEpMatch.groupValues[2].toIntOrNull()
            }
            
            // Prova pattern Ep 01
            if (episodeNum == null) {
                val epMatch = episodePattern.find(context)
                if (epMatch != null) {
                    episodeNum = epMatch.groupValues[1].toIntOrNull()
                }
            }
            
            if (episodeNum == null) {
                episodeNum = index + 1
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
        // data contiene l'URL del video (jessicaclearout.com/ID o chuckle-tube.com/ID)
        val videoUrl = convertToVoeUrl(data)
        
        if (videoUrl.contains("jessicaclearout.com")) {
            VOEExtractor().getUrl(videoUrl, null, subtitleCallback, callback)
        }
        
        return true
    }
}
