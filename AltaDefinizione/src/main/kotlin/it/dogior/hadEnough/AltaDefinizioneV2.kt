package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
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

    override val mainPage = mainPageOf(
        "$mainUrl/page/1/" to "Ultimi Aggiunti",
        "$mainUrl/cinema/" to "Ora al Cinema",
        "$mainUrl/netflix-streaming/" to "Netflix",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/avventura/" to "Avventura",
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/biografico/" to "Biografico",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/crime/" to "Crimine",
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
        "$mainUrl/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = if (request.data.contains("page/")) {
            request.data.replace("page/1/", "page/$page/")
        } else {
            "${request.data}page/$page/"
        }
        
        val doc = app.get(baseUrl).document
        // CORREZIONE 1: Selettore corretto basato sull'HTML
        val items = doc.select("#dle-content .boxgrid.caption").mapNotNull {
            it.toSearchResponse()
        }
        
        // CORREZIONE 2: Paginazione corretta
        val hasNext = doc.select(".page_nav a").any { it.text() == (page + 1).toString() }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        // CORREZIONE 3: Selettori corretti basati sull'HTML
        val linkElement = this.selectFirst("h3 a, .cover h2 a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.text().trim()
        
        val img = this.selectFirst("img.lazyload")
        val poster = img?.attr("data-src") ?: img?.attr("src")
        
        val rating = this.selectFirst(".imdb_bg")?.text()?.trim()
        
        val type = if (this.selectFirst(".se_num") != null) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = fixUrlNull(poster)
            this.score = rating?.let { Score.from(it, 10, 1) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/index.php?do=search").document
        // CORREZIONE 4: Form corretto per la ricerca
        val searchDoc = app.post(
            url = "$mainUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "search_start" to "1",
                "full_search" to "1",
                "result_from" to "1",
                "story" to query
            )
        ).document
        
        return searchDoc.select("#dle-content .boxgrid.caption").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val content = doc.selectFirst("#dle-content, #son_eklenen_kapsul") ?: return null
        
        // CORREZIONE 5: Estrazione dati migliorata basata sull'HTML
        val title = doc.select("h1, h2, .h4").firstOrNull()?.text() 
            ?: doc.select("title").text().replace(" - Streaming", "").trim()
        
        val poster = doc.select("img.lazyload").firstOrNull()?.attr("data-src") 
            ?: doc.select("img.wp-post-image").attr("src")
        
        val plot = doc.select(".ml-item-hiden p").text().takeIf { it.isNotBlank() }
            ?: doc.select("meta[name=description]").attr("content")
        
        val rating = doc.select(".ml-imdb b, .imdb_bg").text().trim().replace(",", ".")
        
        val genres = doc.select(".ml-cat a").map { it.text() }.filter { 
            !it.contains("Serie TV") && !it.contains("Cinema") 
        }
        
        val year = doc.select(".ml-label").firstOrNull()?.text()?.trim()?.take(4)

        val isSeries = url.contains("/serie-tv/") || doc.select(".se_num").isNotEmpty()

        return if (isSeries) {
            val episodes = getEpisodes(doc)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year?.toIntOrNull()
                addScore(rating)
            }
        } else {
            // CORREZIONE 6: Estrazione link migliorata
            val iframeSrc = doc.select("#player1 iframe").attr("src")
            val videoLinks = if (iframeSrc.isNotBlank()) {
                if (iframeSrc.contains("mostraguarda")) {
                    try {
                        val iframeDoc = app.get(iframeSrc).document
                        iframeDoc.select("[data-link]").mapNotNull { 
                            it.attr("data-link").takeIf { l -> l.isNotBlank() }
                        }.filter { !it.contains("mostraguarda") }
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    listOf(iframeSrc)
                }
            } else {
                // Cerca link nei dati json o attributi
                doc.select(".mirrors a, .mr").mapNotNull { it.attr("data-link") }
            }

            newMovieLoadResponse(title, url, TvType.Movie, videoLinks.joinToString(",")) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year?.toIntOrNull()
                addScore(rating)
            }
        }
    }

    private fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seriesPoster = doc.select("img.lazyload").firstOrNull()?.attr("data-src")
        
        // CORREZIONE 7: Parsing episodi basato sull'HTML effettivo
        val episodeElements = doc.select("#dle-content .boxgrid.caption")
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEach { element ->
                val seasonSpan = element.select(".se_num").text()
                val (season, episode) = parseSeasonEpisode(seasonSpan)
                
                val title = element.select("h3 a, .cover h2 a").text().trim()
                val link = element.select("a[href]").attr("href")
                val poster = element.select("img.lazyload").attr("data-src")
                
                episodes.add(
                    newEpisode(link) {
                        this.season = season
                        this.episode = episode
                        this.name = title
                        this.posterUrl = fixUrlNull(if (poster.isNotBlank()) poster else seriesPoster)
                    }
                )
            }
        }
        
        // CORREZIONE 8: Parsing episodi dal formato alternativo
        val hiddenEpisodes = doc.select(".ml-item-hiden").mapNotNull { hidden ->
            val seasonEpisode = hidden.select(".se_num").text()
            val (season, episode) = parseSeasonEpisode(seasonEpisode)
            
            val title = hidden.select(".h4").text()
            val link = hidden.select("a.ml-watch").attr("href")
            
            newEpisode(link) {
                this.season = season
                this.episode = episode
                this.name = title
                this.posterUrl = fixUrlNull(seriesPoster)
            }
        }
        
        episodes.addAll(hiddenEpisodes)
        
        return episodes.distinctBy { "${it.season}-${it.episode}" }
    }

    private fun parseSeasonEpisode(text: String): Pair<Int?, Int?> {
        if (text.isBlank()) return null to null
        return when {
            text.contains("x") -> {
                val parts = text.split("x")
                parts.getOrNull(0)?.toIntOrNull() to parts.getOrNull(1)?.toIntOrNull()
            }
            text.startsWith("Stagione") -> {
                val season = text.replace("Stagione", "").trim().toIntOrNull()
                season to 1
            }
            else -> null to null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("Altadefinizione", "Loading links: $data")
        
        val links = if (data.startsWith("[")) {
            try {
                parseJson<List<String>>(data)
            } catch (e: Exception) {
                listOf(data)
            }
        } else {
            data.split(",").filter { it.isNotBlank() }
        }
        
        links.forEach { link ->
            when {
                link.contains("dropload.tv") -> {
                    DroploadExtractor().getUrl(link, null, subtitleCallback, callback)
                }
                else -> {
                    MySupervideoExtractor().getUrl(link, null, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
