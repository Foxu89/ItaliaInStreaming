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

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Formato slider home
        val sliderLink = this.select("a").first()?.attr("href")
        val sliderTitle = this.select("img").attr("alt")
        val sliderPoster = fixUrlNull(this.select("img").attr("src"))
        
        if (!sliderLink.isNullOrEmpty() && sliderTitle.isNotEmpty()) {
            return newMovieSearchResponse(sliderTitle, sliderLink, TvType.Movie) {
                this.posterUrl = sliderPoster
            }
        }
        
        // Formato box grid
        val box = this.selectFirst(".wrapperImage") ?: return null
        val img = box.selectFirst("img.wp-post-image")
        val href = box.selectFirst("a")?.attr("href") ?: return null
        val title = box.select("h2.titleFilm > a").text().trim()
        val poster = if (!img?.attr("data-src").isNullOrEmpty()) {
            fixUrlNull(img?.attr("data-src"))
        } else {
            fixUrlNull(img?.attr("src"))
        }
        val rating = this.selectFirst("div.imdb-rate")?.ownText()
        
        val type = if (href.contains("/serie-tv/")) TvType.TvSeries else TvType.Movie
        
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        when {
            request.data == "$mainUrl/" -> {
                val doc = app.get(request.data).document
                doc.select(".slider-item").forEach { element ->
                    element.toSearchResponse()?.let { items.add(it) }
                }
                doc.select("#dle-content > .col-lg-3, .mlnew").forEach { element ->
                    element.toSearchResponse()?.let { items.add(it) }
                }
                return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = true), false)
            }
            
            else -> {
                val url = if (page == 1) request.data else "${request.data}page/$page/"
                val doc = app.get(url).document
                
                doc.select("#dle-content > .col-lg-3, .mlnew, .boxgrid").forEach { element ->
                    element.toSearchResponse()?.let { items.add(it) }
                }
                
                val hasNext = doc.select(".pagin a, .mlnew-pagination a:contains(Next)").isNotEmpty()
                return newHomePageResponse(HomePageList(request.name, items), hasNext)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/index.php?do=search&story=${query.replace(" ", "+")}").document
        val items = mutableListOf<SearchResponse>()
        
        doc.select("#dle-content > .col-lg-3, .mlnew, .boxgrid").forEach { element ->
            element.toSearchResponse()?.let { items.add(it) }
        }
        
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val content = doc.selectFirst("#dle-content, .single_icerik") ?: return null
        
        val title = content.select("h1, .single_head h1").text()
            .replace("Streaming HD", "")
            .replace("Streaming", "")
            .trim()
            .ifEmpty { "Sconosciuto" }
        
        val poster = fixUrlNull(content.select("img.wp-post-image, .imagen img, .fix img").attr("src"))
        
        val plot = content.select("#sfull, .entry-content p, .full-text").text()
            .substringAfter("Trama")
            .substringBefore("Fonte")
            .trim()
        
        val rating = content.select("span.rateIMDB, .imdb_r .dato b, .entry-imdb").text()
            .replace("★", "")
            .replace("IMDB:", "")
            .trim()
        
        val details = content.select("#details > li, .data .meta_dd, .tv-info-list ul")
        var year: Int? = null
        val genres = mutableListOf<String>()
        
        details.forEach { detail ->
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
                addScore(rating)
            }
        } else {
            val mirrors = mutableListOf<String>()
            
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("youtube")) {
                    fixUrlNull(src)?.let { mirrors.add(it) }
                }
            }
            
            val playerFrame = doc.select("#mirrorFrame, .player-container iframe").attr("src")
            if (playerFrame.isNotEmpty()) {
                fixUrlNull(playerFrame)?.let { mirrors.add(it) }
            }
            
            val mostraGuardaLink = doc.select("iframe[src*='mostraguarda']").attr("src")
            if (mostraGuardaLink.isNotEmpty()) {
                try {
                    val mostraGuarda = app.get(mostraGuardaLink).document
                    mostraGuarda.select("ul._player-mirrors > li, .mirrors a.mr").forEach { mirror ->
                        val link = mirror.attr("data-link")
                        if (link.isNotEmpty() && !link.contains("mostraguarda")) {
                            fixUrlNull(link)?.let { mirrors.add(it) }
                        }
                    }
                } catch (e: Exception) {
                    Log.d("Altadefinizione", "Error parsing mostraGuarda: ${e.message}")
                }
            }
            
            newMovieLoadResponse(title, url, TvType.Movie, mirrors) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                addScore(rating)
            }
        }
    }

    private fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seriesPoster = fixUrlNull(doc.selectFirst("img.wp-post-image, .imagen img, .fix img")?.attr("src"))
        
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
                    
                    val mirrors = mutableListOf<String>()
                    episodeItem.select(".mirrors a.mr, .mirrors a").forEach { mirror ->
                        val link = mirror.attr("data-link")
                        if (link.isNotEmpty()) {
                            fixUrlNull(link)?.let { mirrors.add(it) }
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
                                this.posterUrl = seriesPoster
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
