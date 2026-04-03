package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import it.dogior.hadEnough.VixSrcExtractor
import org.jsoup.nodes.Document

class GuardaSerie : MainAPI() {
    override var mainUrl = "https://guarda-serie.ovh"
    override var name = "GuardaSerie"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "$mainUrl/" to "🔥 I titoli del momento",
        "$mainUrl/archive?sort=vote" to "⭐ Top IMDB",
        "$mainUrl/archive?genre_id=18&type=tv" to "🎭 Dramma",
        "$mainUrl/archive?genre_id=35&type=tv" to "😄 Commedia",
        "$mainUrl/archive?genre_id=80&type=tv" to "🔪 Crime",
        "$mainUrl/archive?genre_id=10759&type=tv" to "⚔️ Action & Adventure",
        "$mainUrl/archive?genre_id=16&type=tv" to "🎨 Animazione",
        "$mainUrl/archive?genre_id=10765&type=tv" to "🚀 Sci-Fi & Fantasy",
        "$mainUrl/archive?genre_id=9648&type=tv" to "🕵️ Mistero",
        "$mainUrl/archive?genre_id=10768&type=tv" to "⚔️ War & Politics",
        "$mainUrl/archive?genre_id=10766&type=tv" to "🧼 Soap",
        "$mainUrl/archive?genre_id=37&type=tv" to "🤠 Western"
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

    private fun getImageUrl(link: String?): String? {
        if (link.isNullOrEmpty()) return null
        return if (link.startsWith("/")) "https://guarda-serie.ovh$link" else link
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        val doc = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        
        when {
            // Home page - slider "I titoli del momento"
            url == "$mainUrl/" -> {
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
            
            // Archive con classifica (Top IMDB, generi)
            url.contains("/archive") -> {
                // Lista classifica (#ranked-list)
                doc.select("#ranked-list ul li a.ranked-link").forEach { element ->
                    val link = element.attr("href")
                    val title = element.select(".rank-name").text()
                    
                    if (link.isNotEmpty() && title.isNotEmpty()) {
                        items.add(
                            newTvSeriesSearchResponse(title, fixUrl(link)) {
                                this.posterUrl = null
                            }
                        )
                    }
                }
                
                // Lista normale (.mlnew)
                doc.select(".mlnew").forEach { element ->
                    val link = element.select(".mlnh-thumb a").attr("href")
                    val title = element.select(".mlnh-2 h2 a").text()
                    val poster = getImageUrl(element.select(".mlnh-thumb img").attr("src"))
                    
                    if (link.isNotEmpty() && title.isNotEmpty()) {
                        items.add(
                            newTvSeriesSearchResponse(title, fixUrl(link)) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }
        }
        
        val hasNext = doc.select(".mlnew-pagination a:contains(Next)").isNotEmpty()
        
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=${query.replace(" ", "+")}").document
        
        val results = mutableListOf<SearchResponse>()
        
        // Cerca nella classifica
        doc.select("#ranked-list ul li a.ranked-link").forEach { element ->
            val link = element.attr("href")
            val title = element.select(".rank-name").text()
            
            if (link.isNotEmpty() && title.isNotEmpty() && link.contains("/detail/tv-")) {
                results.add(
                    newTvSeriesSearchResponse(title, fixUrl(link)) {
                        this.posterUrl = null
                    }
                )
            }
        }
        
        // Cerca nella lista normale
        doc.select(".mlnew").forEach { element ->
            val link = element.select(".mlnh-thumb a").attr("href")
            val title = element.select(".mlnh-2 h2 a").text()
            val poster = getImageUrl(element.select(".mlnh-thumb img").attr("src"))
            
            if (link.isNotEmpty() && title.isNotEmpty() && link.contains("/detail/tv-")) {
                results.add(
                    newTvSeriesSearchResponse(title, fixUrl(link)) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Titolo
        val title = doc.select("h1.front-title, .gs-detail-title").text()
            .replace("streaming", "")
            .trim()
        
        // Poster
        val poster = getImageUrl(doc.select("#tv-info-poster img").attr("src"))
        
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
        
        // Estrai TMDB ID dall'URL o dalla pagina
        val tmdbId = extractTmdbId(url, doc)
        
        // Episodi
        val episodes = getEpisodes(doc, poster, tmdbId)
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = status
            addScore(ratingText)
        }
    }
    
    private fun extractTmdbId(url: String, doc: Document): String {
        // Metodo 1: Dall'URL della pagina (formato /detail/tv-{ID}-nome-serie)
        val urlMatch = Regex("""/detail/tv-(\d+)""").find(url)
        if (urlMatch != null) {
            return urlMatch.groupValues[1]
        }
        
        // Metodo 2: Dallo script della pagina (var tmdbID)
        val scripts = doc.select("script")
        for (script in scripts) {
            val data = script.data()
            val match = Regex("""var tmdbID\s*=\s*(\d+);""").find(data)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        // Metodo 3: Dai meta tag
        val metaMatch = doc.select("meta[property=og:url]").attr("content")
        val metaId = Regex("""tv-(\d+)""").find(metaMatch)
        if (metaId != null) {
            return metaId.groupValues[1]
        }
        
        // Metodo 4: Dai link canonici
        val canonicalMatch = doc.select("link[rel=canonical]").attr("href")
        val canonicalId = Regex("""tv-(\d+)""").find(canonicalMatch)
        if (canonicalId != null) {
            return canonicalId.groupValues[1]
        }
        
        return "0"
    }

    private fun getEpisodes(doc: Document, poster: String?, tmdbId: String): List<Episode> {
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
                    
                    // Estrai titolo e descrizione dall'attributo data-title
                    val dataTitle = episodeLink?.attr("data-title")?.trim() ?: ""
                    var episodeTitle = "Episodio $episodeNum"
                    var episodeDescription: String? = null
                    
                    if (dataTitle.isNotEmpty()) {
                        if (dataTitle.contains(":")) {
                            val parts = dataTitle.split(":", limit = 2)
                            episodeTitle = parts[0].trim()
                            episodeDescription = parts.getOrNull(1)?.trim()
                        } else {
                            episodeTitle = dataTitle
                        }
                    }
                    
                    // Costruisci l'URL del player con il TMDB ID corretto
                    val playerUrl = "https://vixsrc.to/tv/$tmdbId/$seasonNumber/$episodeNum?lang=it"
                    
                    val mirrors = listOf(MirrorLink("VixSrc", playerUrl))
                    
                    val episodeData = EpisodeData(seasonNumber, episodeNum, episodeTitle, episodeDescription, mirrors)
                    episodes.add(
                        newEpisode(episodeData.toJson()) {
                            this.name = episodeTitle
                            this.description = episodeDescription
                            this.season = seasonNumber
                            this.episode = episodeNum
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }
        
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
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
                if (mirror.url.contains("vixsrc.to")) {
                    try {
                        VixSrcExtractor().getUrl(mirror.url, mainUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                        loadExtractor(mirror.url, mainUrl, subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(mirror.url, mainUrl, subtitleCallback, callback)
                }
            }
            
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
