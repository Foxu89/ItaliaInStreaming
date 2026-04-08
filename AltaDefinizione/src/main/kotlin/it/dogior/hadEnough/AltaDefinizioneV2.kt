package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.extractors.DroploadExtractor
import it.dogior.hadEnough.extractors.MySupervideoExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AltaDefinizioneV2 : MainAPI() {
    override var mainUrl = "https://altadefinizione-01.bar"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        Pair("$mainUrl/", "I titoli del momento"),
        Pair("$mainUrl/cinema/", "Al Cinema"),
        Pair("$mainUrl/serie-tv/", "Serie TV"),
        Pair("$mainUrl/netflix-streaming/", "Netflix"),
        Pair("$mainUrl/animazione/", "Animazione"),
        Pair("$mainUrl/avventura/", "Avventura"),
        Pair("$mainUrl/azione/", "Azione"),
        Pair("$mainUrl/biografico/", "Biografico"),
        Pair("$mainUrl/commedia/", "Commedia"),
        Pair("$mainUrl/crime/", "Crime"),
        Pair("$mainUrl/documentario/", "Documentario"),
        Pair("$mainUrl/drammatico/", "Drammatico"),
        Pair("$mainUrl/erotico/", "Erotico"),
        Pair("$mainUrl/famiglia/", "Famiglia"),
        Pair("$mainUrl/fantascienza/", "Fantascienza"),
        Pair("$mainUrl/fantasy/", "Fantasy"),
        Pair("$mainUrl/giallo/", "Giallo"),
        Pair("$mainUrl/guerra/", "Guerra"),
        Pair("$mainUrl/horror/", "Horror"),
        Pair("$mainUrl/musical/", "Musical"),
        Pair("$mainUrl/poliziesco/", "Poliziesco"),
        Pair("$mainUrl/romantico/", "Romantico"),
        Pair("$mainUrl/sportivo/", "Sportivo"),
        Pair("$mainUrl/storico-streaming/", "Storico"),
        Pair("$mainUrl/thriller/", "Thriller"),
        Pair("$mainUrl/western/", "Western")
    )

    data class EpisodeData(
        val mirrors: List<String>,
        val season: Int?,
        val episode: Int?,
        val title: String?,
        val description: String?
    )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Formato slider home (.slider-item)
        val sliderLink = this.select("a").first()?.attr("href")
        val sliderTitle = this.select("img").attr("alt")
        val sliderPoster = fixUrl(this.select("img").attr("src"))
        
        if (!sliderLink.isNullOrEmpty() && sliderTitle.isNotEmpty()) {
            return newMovieSearchResponse(sliderTitle, sliderLink, TvType.Movie) {
                this.posterUrl = sliderPoster
            }
        }
        
        // Formato box grid (.boxgrid)
        val boxLink = this.select(".cover_kapsul a").attr("href")
        val boxTitle = this.select(".cover boxcaption h2 a").text()
        val boxPoster = fixUrl(this.select(".cover_kapsul img").attr("data-src"))
        
        if (boxLink.isNotEmpty() && boxTitle.isNotEmpty()) {
            val type = if (boxLink.contains("/serie-tv/")) TvType.TvSeries else TvType.Movie
            return newMovieSearchResponse(boxTitle, boxLink, type) {
                this.posterUrl = boxPoster
            }
        }
        
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        
        // Cerca tutti i contenuti
        doc.select(".slider-item, .boxgrid").forEach { element ->
            element.toSearchResponse()?.let { items.add(it) }
        }
        
        val hasNext = doc.select(".page_nav a:contains(Next), .wp-pagenavi a:contains(Next)").isNotEmpty()
        
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/index.php?do=search&story=${query.replace(" ", "+")}").document
        val items = mutableListOf<SearchResponse>()
        
        doc.select(".boxgrid").forEach { element ->
            element.toSearchResponse()?.let { items.add(it) }
        }
        
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Titolo
        val title = doc.select("h1, .single_head h1, .movie_head h1").text()
            .replace("Streaming HD", "")
            .replace("Streaming", "")
            .trim()
            .ifEmpty { "Sconosciuto" }
        
        // Poster
        val poster = fixUrl(doc.select("img.wp-post-image, .imagen img, .fix img, .cover_kapsul img").attr("data-src"))
        
        // Trama
        val plot = doc.select(".entry-content p, #sfull, .full-text").text()
            .substringAfter("Trama")
            .substringBefore("Fonte")
            .trim()
        
        // Rating
        val rating = doc.select(".imdb_bg, .entry-imdb, .imdb_r .dato b").text()
            .replace("★", "")
            .replace("IMDB:", "")
            .trim()
        
        // Anno e generi
        var year: Int? = null
        val genres = mutableListOf<String>()
        
        doc.select(".meta_dd, .tv-info-list ul, .data .meta_dd").forEach { detail ->
            val text = detail.text()
            if (text.contains("Anno:") || text.contains("Anno produzione:")) {
                year = Regex("\\d{4}").find(text)?.value?.toIntOrNull()
            }
            if (text.contains("Genere:") || text.contains("Categorie:")) {
                detail.select("a").forEach { genre ->
                    genres.add(genre.text())
                }
            }
        }
        
        return if (url.contains("/serie-tv/")) {
            val episodes = getEpisodes(doc)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                if (rating.isNotEmpty()) addScore(rating)
            }
        } else {
            val mirrors = getMovieLinks(doc)
            newMovieLoadResponse(title, url, TvType.Movie, mirrors) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                if (rating.isNotEmpty()) addScore(rating)
            }
        }
    }
    
    private suspend fun getMovieLinks(doc: Document): List<String> {
        val mirrors = mutableListOf<String>()
        
        // Cerca l'iframe diretto
        val iframeSrc = doc.select("#mirrorFrame, .player-container iframe").attr("src")
        if (iframeSrc.isNotEmpty()) {
            val fullUrl = fixUrl(iframeSrc)
            if (fullUrl != null) mirrors.add(fullUrl)
        }
        
        // Cerca l'iframe di mostraguarda
        val mostraGuarda = doc.select("iframe[src*='mostraguarda']").attr("src")
        if (mostraGuarda.isNotEmpty()) {
            try {
                val embedDoc = app.get(mostraGuarda).document
                // Cerca i mirror dentro mostraguarda
                embedDoc.select("ul._player-mirrors li, .mirrors a.mr").forEach { mirror ->
                    val link = mirror.attr("data-link")
                    if (link.isNotEmpty() && !link.contains("mostraguarda")) {
                        fixUrl(link)?.let { mirrors.add(it) }
                    }
                }
                // Cerca iframe dentro mostraguarda
                embedDoc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotEmpty() && src.contains("supervideo") || src.contains("dropload")) {
                        fixUrl(src)?.let { mirrors.add(it) }
                    }
                }
            } catch (e: Exception) {
                Log.d("Altadefinizione", "Error parsing mostraGuarda: ${e.message}")
            }
        }
        
        // Cerca iframe diretti di supervideo/dropload
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && (src.contains("supervideo") || src.contains("dropload"))) {
                fixUrl(src)?.let { mirrors.add(it) }
            }
        }
        
        return mirrors.distinct()
    }

    private fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val poster = fixUrl(doc.selectFirst("img.wp-post-image, .imagen img, .fix img")?.attr("data-src"))
        
        // Cerca i tab delle stagioni
        val seasonTabs = doc.select(".tt_season ul li a, .tt-season ul li a")
        
        if (seasonTabs.isNotEmpty()) {
            for (seasonTab in seasonTabs) {
                val seasonNumber = seasonTab.text().toIntOrNull() ?: continue
                val seasonId = seasonTab.attr("href").removePrefix("#")
                val episodeContainer = doc.select("#$seasonId ul li, #$seasonId li")
                
                for (episodeItem in episodeContainer) {
                    val episodeLink = episodeItem.select("a").first()
                    val episodeNum = episodeLink?.attr("data-episode")?.toIntOrNull()
                        ?: episodeLink?.attr("data-num")?.substringAfter("x")?.toIntOrNull()
                        ?: continue
                    
                    // Titolo episodio
                    var episodeTitle = episodeLink?.attr("data-title")?.trim()
                    var episodeDescription: String? = null
                    
                    if (!episodeTitle.isNullOrEmpty()) {
                        if (episodeTitle.contains(":")) {
                            val parts = episodeTitle.split(":", limit = 2)
                            episodeTitle = parts[0].trim()
                            episodeDescription = parts.getOrNull(1)?.trim()
                        }
                    } else {
                        episodeTitle = "Episodio $episodeNum"
                    }
                    
                    // Cerca i mirror per questo episodio
                    val mirrors = mutableListOf<String>()
                    episodeItem.select(".mirrors a.mr, .mirrors a").forEach { mirror ->
                        val link = mirror.attr("data-link")
                        if (link.isNotEmpty() && (link.contains("supervideo") || link.contains("dropload"))) {
                            fixUrl(link)?.let { mirrors.add(it) }
                        }
                    }
                    
                    if (mirrors.isNotEmpty()) {
                        val episodeData = EpisodeData(mirrors, seasonNumber, episodeNum, episodeTitle, episodeDescription)
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
        }
        
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("Altadefinizione", "Loading links: $data")
        
        try {
            val episodeData = parseJson<EpisodeData>(data)
            var success = false
            
            episodeData.mirrors.forEach { link ->
                Log.d("Altadefinizione", "Processing mirror: $link")
                success = true
                
                when {
                    link.contains("dropload.tv") -> {
                        DroploadExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    }
                    link.contains("supervideo.cc") -> {
                        MySupervideoExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    }
                    link.contains("/4k/film/embed/") -> {
                        // Embed interno, cerca l'iframe dentro
                        try {
                            val embedDoc = app.get(link).document
                            val iframe = embedDoc.select("iframe").first()
                            if (iframe != null) {
                                val iframeSrc = iframe.attr("src")
                                if (iframeSrc.isNotEmpty()) {
                                    loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("Altadefinizione", "Error parsing embed: ${e.message}")
                        }
                    }
                    else -> {
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                    }
                }
            }
            
            return success
        } catch (e: Exception) {
            Log.d("Altadefinizione", "Error loading links: ${e.message}")
            return false
        }
    }
}
