package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.SubtitleFile
import it.dogior.hadEnough.extractors.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class IlGenioDelloStreaming : MainAPI() {
    override var mainUrl = "https://il-geniodellostreaming.homes"
    override var name = "IlGenioDelloStreaming"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/film/" to "Archivio Film",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/avventura/" to "Avventura",
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/crime/" to "Crime",
        "$mainUrl/documentario/" to "Documentario",
        "$mainUrl/drammatico/" to "Drammatico",
        "$mainUrl/erotico/" to "Erotico",
        "$mainUrl/famiglia/" to "Famiglia",
        "$mainUrl/fantascienza/" to "Fantascienza",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/giallo/" to "Giallo",
        "$mainUrl/guerra/" to "Guerra",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/musical/" to "Musical",
        "$mainUrl/poliziesco/" to "Poliziesco",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/sportivo/" to "Sportivo",
        "$mainUrl/storico-streaming/" to "Storico",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/western/" to "Western",
        "$mainUrl/prossimamente/" to "Prossimamente"
    )

    data class MovieData(
        val embedUrl: String,
        val mirrors: List<String> = emptyList()
    )

    data class MirrorData(
        val season: Int,
        val episode: Int,
        val mirrors: List<String>
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == mainUrl) {
            mainUrl
        } else {
            request.data
        }
        
        val doc = app.get(url).document
        val items = doc.select("#dt-insala .item, .items .item, #slider-movies-tvshows .item").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = request.data == mainUrl),
            hasNext = false
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val title = select("h3 a, .data h3 a, .title a").text().ifEmpty { 
            select("img").attr("alt").ifEmpty { "Sconosciuto" }
        }
        val poster = select("img").attr("src").ifEmpty { 
            select("img").attr("data-src") 
        }
        
        val isSeries = select(".se_num").isNotEmpty() || link.contains("/serie-tv/")
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(link)) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = emptyMap()
            }
        } else {
            newMovieSearchResponse(title, fixUrl(link)) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = emptyMap()
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.post(
            "$mainUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query
            )
        ).document
        
        return doc.select("#dle-content .item, .items .item").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.select("h1").text().replace(" streaming", "").trim()
        val poster = doc.select(".poster img, .sheader .poster img").attr("src")
        val plot = doc.select(".wp-content p").text().ifEmpty { 
            doc.select("[itemprop=description]").attr("content")
        }
        val rating = doc.select(".dt_rating_vgs").text().ifEmpty { 
            doc.select("[itemprop=ratingValue]").attr("content")
        }
        val year = doc.select(".date, .extra .date").text().substringAfterLast(" ").toIntOrNull()
        val genres = doc.select(".sgeneros a, .generos a").map { it.text() }
        
        return if (url.contains("/serie-tv/") || doc.select("#tv_tabs").isNotEmpty()) {
            loadSeries(doc, url, title, poster, plot, rating, year, genres)
        } else {
            loadMovie(doc, url, title, poster, plot, rating, year, genres)
        }
    }

    private suspend fun loadMovie(
        doc: Document,
        url: String,
        title: String,
        poster: String,
        plot: String,
        rating: String,
        year: Int?,
        genres: List<String>
    ): LoadResponse {
        
        val iframeSrc = doc.select("iframe").attr("src").ifEmpty {
            doc.select("div.player_sist iframe").attr("src")
        }
        
        val embedUrl = if (iframeSrc.isNotEmpty()) {
            iframeSrc
        } else {
            val script = doc.select("script").find { it.data().contains("player_sist") }
            val match = Regex("src\\s*=\\s*[\"']([^\"']+)[\"']").find(script?.data() ?: "")
            match?.groupValues?.get(1) ?: ""
        }

        
        val mirrors = mutableListOf<String>()
        
        
        val scripts = doc.select("script")
        scripts.forEach { script ->
            val scriptData = script.data()
            
            Regex("data-link\\s*=\\s*[\"'](https?://[^\"']+)[\"']").findAll(scriptData).forEach {
                val link = it.groupValues[1]
                if (link.isNotEmpty() && !link.contains("youtube") && !link.contains("javascript")) {
                    mirrors.add(link)
                }
            }
            
            
            Regex("(https?://(?:dropload|supervideo|mixdrop|streamhg|guardahd)\\.[^\"'\\s]+)").findAll(scriptData).forEach {
                val link = it.groupValues[1]
                if (link.isNotEmpty()) {
                    mirrors.add(link)
                }
            }
        }

        
        doc.select("[data-link]").forEach { element ->
            val link = element.attr("data-link")
            if (link.isNotEmpty() && !link.contains("#") && !link.contains("youtube")) {
                mirrors.add(fixUrl(link))
            }
        }

        
        doc.select("script[type*='json']").forEach { script ->
            try {
                val jsonText = script.data()
                Regex("\"file\":\"([^\"]+)\"").findAll(jsonText).forEach {
                    val videoUrl = it.groupValues[1].replace("\\/", "/")
                    if (videoUrl.isNotEmpty()) {
                        mirrors.add(videoUrl)
                    }
                }
            } catch (e: Exception) { }
        }

        val data = MovieData(
            embedUrl = embedUrl,
            mirrors = mirrors.distinct()
        )

        return newMovieLoadResponse(title, url, TvType.Movie, data.toJson()) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.tags = genres
            this.year = year
            addScore(rating)
        }
    }

    private suspend fun loadSeries(
        doc: Document,
        url: String,
        title: String,
        poster: String,
        plot: String,
        rating: String,
        year: Int?,
        genres: List<String>
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()
        
        
        val seasonTabs = doc.select("#tv_tabs .tt_season ul li a, .tt_season ul li a")
        if (seasonTabs.isNotEmpty()) {
            for (seasonTab in seasonTabs) {
                val seasonNumber = seasonTab.text().toIntOrNull() ?: continue
                val seasonId = seasonTab.attr("href").removePrefix("#")
                val episodeContainer = doc.select("#$seasonId ul li, .tab-pane#$seasonId ul li")
                
                for (episodeItem in episodeContainer) {
                    val episodeLink = episodeItem.select("a").first()
                    val episodeNumber = episodeLink?.attr("data-num")?.substringAfter("x")?.toIntOrNull() ?: 
                                        episodeItem.select("a").text().toIntOrNull()
                    
                    val episodeTitle = episodeLink?.attr("data-title")
                    val episodeDesc = episodeLink?.attr("data-title") ?: "" // La descrizione è nel titolo
                    
                    val mirrors = episodeItem.select(".mirrors a[data-link]").map { 
                        it.attr("data-link") 
                    }.filter { it.isNotEmpty() }
                    
                    if (episodeNumber != null && mirrors.isNotEmpty()) {
                        
                        val cleanTitle = episodeTitle?.takeIf { it.length < 50 } ?: "Episodio $episodeNumber"
                        val cleanDesc = if (episodeTitle?.length ?: 0 > 50) episodeTitle else ""
                        
                        episodes.add(
                            newEpisode(MirrorData(seasonNumber, episodeNumber, mirrors).toJson()) {
                                this.name = cleanTitle
                                this.description = cleanDesc
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.posterUrl = fixUrlNull(poster)
                            }
                        )
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.tags = genres
            this.year = year
            addScore(rating)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val movieData = parseJson<MovieData>(data)
            if (movieData.embedUrl.isNotEmpty()) {
                loadExtractor(movieData.embedUrl, mainUrl, subtitleCallback, callback)
            }
            movieData.mirrors.forEach { mirrorUrl ->
                loadMirror(mirrorUrl, subtitleCallback, callback)
            }
            return true
        } catch (e: Exception) {
            try {
                val mirrorData = parseJson<MirrorData>(data)
                mirrorData.mirrors.forEach { mirrorUrl ->
                    loadMirror(mirrorUrl, subtitleCallback, callback)
                }
                return true
            } catch (e2: Exception) {
                try {
                    val mirrors = parseJson<List<String>>(data)
                    mirrors.forEach { mirrorUrl ->
                        loadMirror(mirrorUrl, subtitleCallback, callback)
                    }
                    return true
                } catch (e3: Exception) {
                    if (data.isNotEmpty() && !data.startsWith("{")) {
                        loadMirror(data, subtitleCallback, callback)
                        return true
                    }
                }
            }
        }
        return false
    }

    private suspend fun loadMirror(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("dropload") || url.contains("dr0pstream") -> {
                DroploadExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            }
            url.contains("supervideo") -> {
                MySupervideoExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            }
            url.contains("mixdrop") -> {
                MixdropExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            }
            url.contains("streamhg") -> {
                StreamHGXExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            }
            url.contains("guardahd") -> {
                GuardaHDExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            }
            else -> {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }
    }
}
