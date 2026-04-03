package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import it.dogior.hadEnough.VixSrcExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GuardaSerie : MainAPI() {
    override var mainUrl = "https://guarda-serie.ovh"
    override var name = "GuardaSerie"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "$mainUrl/" to "I titoli del momento",
        "$mainUrl/archive?sort=vote" to "Top IMDB",
        "$mainUrl/archive?genre_id=18&type=tv" to "Dramma",
        "$mainUrl/archive?genre_id=35&type=tv" to "Commedia",
        "$mainUrl/archive?genre_id=80&type=tv" to "Crime",
        "$mainUrl/archive?genre_id=10759&type=tv" to "Action & Adventure",
        "$mainUrl/archive?genre_id=16&type=tv" to "Animazione",
        "$mainUrl/archive?genre_id=10765&type=tv" to "Sci-Fi & Fantasy",
        "$mainUrl/archive?genre_id=9648&type=tv" to "Mistero",
        "$mainUrl/archive?genre_id=10768&type=tv" to "War & Politics",
        "$mainUrl/archive?genre_id=10766&type=tv" to "Soap",
        "$mainUrl/archive?genre_id=37&type=tv" to "Western"
    )

    data class EpisodeData(
        val season: Int,
        val episode: Int,
        val title: String?,
        val description: String?,
        val mirrors: List<MirrorLink>
    )

    data class MirrorLink(
        val name: String,
        val url: String
    )

    private fun getImageUrl(link: String?, size: String = "w200"): String? {
        if (link.isNullOrEmpty()) return null
        return if (link.startsWith("/")) "https://guarda-serie.ovh$link" else link
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val doc = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        
        // Slider items (I titoli del momento)
        if (url == "$mainUrl/") {
            doc.select(".slider-item").forEach { element ->
                val link = element.select("a").first()?.attr("href") ?: return@forEach
                val title = element.select("img").attr("alt")
                val poster = getImageUrl(element.select("img").attr("src"))
                
                if (link.isNotEmpty() && title.isNotEmpty()) {
                    items.add(
                        newTvSeriesSearchResponse(title, fixUrl(link)) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }
        
        // Archive items (categorie e top)
        if (url.contains("/archive")) {
            doc.select("#ranked-list ul li a.ranked-link, .mlnew").forEach { element ->
                val link = element.attr("href")
                val title = element.select(".rank-name").text()
                    .ifEmpty { element.select("h2 a, .rank-title .rank-name").text() }
                    .ifEmpty { element.ownText() }
                
                if (link.isNotEmpty() && title.isNotEmpty()) {
                    items.add(
                        newTvSeriesSearchResponse(title, fixUrl(link)) {
                            this.posterUrl = null
                        }
                    )
                }
            }
        }
        
        val hasNext = doc.select(".pagenavi a:contains(Next)").isNotEmpty()
        
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=${query.replace(" ", "+")}").document
        
        return doc.select(".mlnew, #ranked-list ul li a.ranked-link").mapNotNull { element ->
            val link = element.attr("href")
            val title = element.select(".rank-name").text()
                .ifEmpty { element.select("h2 a").text() }
                .ifEmpty { element.ownText() }
            
            if (link.isNotEmpty() && title.isNotEmpty() && link.contains("/detail/tv-")) {
                newTvSeriesSearchResponse(title, fixUrl(link)) {
                    this.posterUrl = null
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Titolo
        val title = doc.select("h1.front-title, .gs-detail-title").text()
            .replace("streaming", "")
            .trim()
        
        // Poster
        val poster = getImageUrl(doc.select("#tv-info-poster img").attr("src"), "w200")
        
        // Trama
        val plot = doc.select(".tv-info-right").text()
            .substringAfter("Trama")
            .substringBefore("Categoria")
            .trim()
        
        // Rating
        val ratingText = doc.select(".entry-imdb").text().replace("★", "").trim()
        
        // Anno
        val yearText = doc.select(".tv-info-list ul:contains(Anno) li:last-child").text()
        val year = Regex("\\d{4}").find(yearText)?.value?.toIntOrNull()
        
        // Generi
        val genres = doc.select(".tv-info-list ul:contains(Categoria) li:last-child a").map { it.text() }
        
        // Stato
        val status = if (yearText.contains("Returning Series")) ShowStatus.Ongoing else ShowStatus.Completed
        
        // Episodi
        val episodes = getEpisodes(doc, poster)
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = status
            addScore(ratingText)
        }
    }

    private fun getEpisodes(doc: Document, poster: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Cerca i tab delle stagioni
        val seasonTabs = doc.select(".tt-season ul li a")
        
        if (seasonTabs.isNotEmpty()) {
            for (seasonTab in seasonTabs) {
                val seasonNumber = seasonTab.text().toIntOrNull() ?: continue
                val seasonId = seasonTab.attr("href").removePrefix("#")
                val episodeContainer = doc.select("#$seasonId ul li")
                
                for (episodeItem in episodeContainer) {
                    val episodeLink = episodeItem.select("a").first()
                    val episodeNum = episodeLink?.attr("data-episode")?.toIntOrNull() 
                        ?: episodeLink?.text()?.toIntOrNull()
                        ?: continue
                    
                    val episodeTitle = episodeLink?.attr("data-title") ?: "Episodio $episodeNum"
                    
                    // Costruisci l'URL del player
                    val playerUrl = "https://vixsrc.to/tv/${getTmdbIdFromUrl(doc)}/$seasonNumber/$episodeNum?lang=it"
                    
                    val mirrors = listOf(
                        MirrorLink("VixSrc", playerUrl)
                    )
                    
                    val episodeData = EpisodeData(seasonNumber, episodeNum, episodeTitle, null, mirrors)
                    episodes.add(
                        newEpisode(episodeData.toJson()) {
                            this.name = episodeTitle
                            this.season = seasonNumber
                            this.episode = episodeNum
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }
        
        // Fallback: cerca episodi nello spoiler
        if (episodes.isEmpty()) {
            val spoilers = doc.select(".su-spoiler")
            
            for (spoiler in spoilers) {
                val seasonTitle = spoiler.select(".su-spoiler-title").text()
                val seasonNumber = Regex("\\d+").find(seasonTitle)?.value?.toIntOrNull() ?: continue
                val content = spoiler.select(".su-spoiler-content")
                
                val lines = content.html().split("<br />")
                for (line in lines) {
                    val episodeMatch = Regex("(\\d+)x(\\d+)").find(line)
                    if (episodeMatch != null) {
                        val episodeNum = episodeMatch.groupValues[2].toInt()
                        
                        val playerUrl = "https://vixsrc.to/tv/${getTmdbIdFromUrl(doc)}/$seasonNumber/$episodeNum?lang=it"
                        val mirrors = listOf(MirrorLink("VixSrc", playerUrl))
                        
                        val episodeData = EpisodeData(seasonNumber, episodeNum, "Episodio $episodeNum", null, mirrors)
                        episodes.add(
                            newEpisode(episodeData.toJson()) {
                                this.season = seasonNumber
                                this.episode = episodeNum
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }
        }
        
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }
    
    private fun getTmdbIdFromUrl(doc: Document): String {
        // Cerca lo script che contiene tmdbID
        val scripts = doc.select("script")
        for (script in scripts) {
            val data = script.data()
            val match = Regex("""var tmdbID\s*=\s*(\d+);""").find(data)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return "0"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val episodeData = parseJson<EpisodeData>(data)
            
            episodeData.mirrors.forEach { mirror ->
                // Usa solo VixSrcExtractor
                if (mirror.url.contains("vixsrc.to")) {
                    VixSrcExtractor().getUrl(mirror.url, mainUrl, subtitleCallback, callback)
                } else {
                    // Fallback generico
                    loadExtractor(mirror.url, mainUrl, subtitleCallback, callback)
                }
            }
            
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
