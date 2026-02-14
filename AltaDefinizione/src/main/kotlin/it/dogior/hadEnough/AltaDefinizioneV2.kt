package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import it.dogior.hadEnough.extractors.DroploadExtractor
import it.dogior.hadEnough.extractors.MySupervideoExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AltaDefinizioneV2 : MainAPI() {
    override var mainUrl = "https://altadefinizione-01.stream"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    private val timeout = 60L

    override val mainPage = mainPageOf(
        "$mainUrl/cinema/" to "Al Cinema",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/avventura/" to "Avventura",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/drammatico/" to "Drammatico",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/fantascienza/" to "Fantascienza",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/crime/" to "Crime",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/famiglia/" to "Famiglia",
        "$mainUrl/western/" to "Western",
        "$mainUrl/documentario/" to "Documentario"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, timeout = timeout).document
        
        val items = doc.select("#dle-content > .col").mapNotNull {
            it.toSearchResponse()
        }
        
        val hasNext = doc.select("a[rel=next]").isNotEmpty()
        
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        val titleElement = this.selectFirst(".movie-title a") ?: return null
        val title = titleElement.text().trim()
        if (title.isBlank()) return null
        
        val href = fixUrl(titleElement.attr("href"))
        if (href.isBlank()) return null
        
        val imgElement = this.selectFirst("img.layer-image.lazy")
        val poster = imgElement?.attr("data-src") ?: imgElement?.attr("src")
        
        val ratingElement = this.selectFirst(".label.rate.small")
        val rating = ratingElement?.text()
        
        return newMovieSearchResponse(title, href) {
            this.posterUrl = fixUrlNull(poster)
            this.score = Score.from(rating, 10)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(searchUrl, timeout = timeout).document
        return doc.select("#dle-content > .col").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = timeout).document
        
        val content = doc.selectFirst("#movie-details, #dle-content, main, .container") ?: return null
        
        val title = doc.selectFirst("h1.movie_entry-title, h1, .movie_entry-title, .movie-title")?.text() 
            ?: "Sconosciuto"
        
        val posterImg = content.selectFirst("img.layer-image.lazy, img[data-src], img")
        val poster = posterImg?.attr("data-src") ?: posterImg?.attr("src")
        
        val plot = doc.selectFirst(".movie_entry-plot, #sfull, .plot, .description, .synopsis")?.text()
            ?.replace("...", "")?.replace("Leggi tutto", "")?.trim()
        
        val rating = content.selectFirst(".label.rate, .rateIMDB, .imdb-rate, .rating, span.label.imdb")?.text()
            ?.substringAfter("IMDb: ")?.substringBefore(" ") ?: ""
        
        val detailsContainer = content.selectFirst(".movie_entry-details, .details, .info, #details")
        val details = detailsContainer?.select("li, .row") ?: emptyList()
        
        val durationString = doc.selectFirst(".meta.movie_entry-info .meta-list span:contains(min)")?.text()
        val duration = durationString?.substringBefore(" min")?.trim()?.toIntOrNull()
        
        val year = details.find { it.text().contains("Anno:", ignoreCase = true) }
            ?.select("div")?.last()?.text()?.trim()?.toIntOrNull()
        
        val genres = details.find { it.text().contains("Genere:", ignoreCase = true) }
            ?.select("a")?.map { it.text() } ?: emptyList()
        
        val actors = details.find { it.text().contains("Cast:", ignoreCase = true) }
            ?.select("a")?.map { ActorData(Actor(it.text())) } ?: emptyList()
        
        val isSeries = url.contains("/serie-tv/") || 
                      doc.select(".series-select, .dropdown.seasons, .dropdown.episodes, .dropdown.mirrors").isNotEmpty()
        
        return if (isSeries) {
            val episodes = getEpisodes(doc, poster)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                this.actors = actors
                addScore(rating)
            }
        } else {
            val mirrors = extractMovieMirrors(doc)
            newMovieLoadResponse(title, url, TvType.Movie, mirrors) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                this.duration = duration
                this.actors = actors
                addScore(rating)
            }
        }
    }

    private suspend fun extractMovieMirrors(doc: Document): List<String> {
        val mirrors = mutableListOf<String>()
        
        // 1. Prendi l'iframe diretto
        val iframeSrc = doc.selectFirst("iframe")?.attr("src")
        if (iframeSrc != null && iframeSrc.contains("mostraguarda")) {
            try {
                // Vai alla pagina mostraguarda
                val mostraGuarda = app.get(iframeSrc, timeout = timeout).document
                
                // Estrai i link dai pulsanti
                mostraGuarda.select("ul._player-mirrors > li, .dropdown-menu a[data-link], span[data-link]").forEach {
                    val link = it.attr("data-link")
                    if (link.isNotBlank() && !link.contains("mostraguarda")) {
                        mirrors.add(fixUrl(link))
                        println("✅ Trovato link da mostraguarda: $link")
                    }
                }
                
                if (mirrors.isNotEmpty()) return mirrors.distinct()
            } catch (e: Exception) {
                println("❌ Errore nel caricare mostraguarda: ${e.message}")
            }
        }
        
        // 2. Se fallisce, cerca link diretti nei bottoni
        doc.select("a.buttona_stream[href]").forEach {
            val href = it.attr("href")
            if (href.isNotBlank() && (href.contains("/4k/") || href.contains("/streaming/"))) {
                mirrors.add(fixUrl(href))
            }
        }
        
        // 3. Cerca script DDL
        if (mirrors.isEmpty()) {
            doc.select("script[src*='mostraguarda.stream/ddl']").forEach {
                val src = it.attr("src")
                if (src.isNotBlank()) {
                    mirrors.add(fixUrl(src))
                }
            }
        }
        
        return mirrors.distinct()
    }

    private fun getEpisodes(doc: Document, poster: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        doc.select("div.dropdown.mirrors[data-season][data-episode]").forEach { container ->
            val seasonNum = container.attr("data-season").toIntOrNull()
            val episodeData = container.attr("data-episode")
            val episodeNum = episodeData.substringAfter("-").toIntOrNull()
            
            val mirrors = container.select("span[data-link]").mapNotNull {
                val link = it.attr("data-link")
                if (link.isNotBlank()) link else null
            }.distinct()
            
            if (mirrors.isNotEmpty()) {
                episodes.add(
                    newEpisode(mirrors) {
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.posterUrl = fixUrlNull(poster)
                    }
                )
            }
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseJson<List<String>>(data)
        if (links.isEmpty()) return false
        
        var found = false
        
        links.forEach { link ->
            when {
                link.contains("dropload.tv") || link.contains("dropload.pro") -> {
                    DroploadExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    found = true
                }
                link.contains("supervideo.tv") || link.contains("supervideo.cc") || link.contains("mysupervideo") -> {
                    MySupervideoExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    found = true
                }
                else -> {
                    try {
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                        found = true
                    } catch (_: Exception) { }
                }
            }
        }
        
        return found
    }
}
