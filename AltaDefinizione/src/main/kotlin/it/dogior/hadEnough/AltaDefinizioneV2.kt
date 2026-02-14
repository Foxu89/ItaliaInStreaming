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
        val items = doc.select("#dle-content .boxgrid.caption, .son_eklenen_kapsul .boxgrid.caption").mapNotNull {
            it.toSearchResponse()
        }
        
        val hasNext = doc.select(".page_nav a").any { it.text() == (page + 1).toString() }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        val linkElement = this.selectFirst("h3 a, .cover h2 a, .single_head h1 a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.text().trim()
        
        val img = this.selectFirst("img.lazyload, img.wp-post-image")
        val poster = img?.attr("data-src") ?: img?.attr("src")
        
        val rating = this.selectFirst(".imdb_bg, .imdb_r .a span, .ml-imdb b")?.text()?.trim()
        
        val type = if (this.selectFirst(".se_num, .ml-label:contains(Serie TV)") != null || href.contains("/serie-tv/")) 
            TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = fixUrlNull(poster)
            this.score = rating?.let { 
                Score.from(it.replace(",", "."), 10, 1)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
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
        
        // CORREZIONE 1: Titolo
        val title = doc.select("h1, h1 a, .single_head h1").text().trim()
            .replace("Streaming", "").replace("HD", "").replace("Gratis", "").trim()
            .let { if (it.isBlank()) doc.select("title").text().split(" - ").firstOrNull() ?: "Sconosciuto" else it }
        
        // CORREZIONE 2: Poster
        val poster = doc.select("meta[itemprop=image]").attr("content").ifEmpty { 
            doc.select("img.lazyload, img.wp-post-image").attr("data-src").ifEmpty {
                doc.select("img.lazyload, img.wp-post-image").attr("src")
            }
        }
        
        // CORREZIONE 3: Trama
        val plot = doc.select(".entry-content p, meta[name=description]").attr("content").ifEmpty {
            doc.select(".entry-content p").text().ifEmpty {
                doc.select(".ml-item-hiden p").text()
            }
        }
        
        // CORREZIONE 4: Rating IMDB
        val rating = doc.select(".imdb_r .a span, .imdb_bg, .ml-imdb b").text().trim()
        
        // CORREZIONE 5: Anno
        val year = doc.select(".meta_dd:contains(Anno), .ml-label").text()
            .let { Regex("\\d{4}").find(it)?.value }
        
        // CORREZIONE 6: Generi
        val genres = doc.select(".meta_dd:contains(Categorie) a, .meta_dd:contains(Genere) a, .ml-cat a").map { it.text() }
            .filter { !it.contains("Serie TV") && !it.contains("Cinema") && !it.contains("Sub-ITA") }
        
        // CORREZIONE 7: Durata
        val duration = doc.select(".meta_dd:contains(Durata)").text()
            .let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        // CORREZIONE 8: Titolo originale
        val originalTitle = doc.select(".titulo_o").text().takeIf { it.isNotBlank() }

        // CORREZIONE 9: Verifica se è serie TV
        val isSeries = url.contains("/serie-tv/") || 
                       doc.select("#tabs_holder, .tt_season, .se_num, .ml-label:contains(Serie TV)").isNotEmpty()

        return if (isSeries) {
            val episodes = getSeriesEpisodes(doc)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year?.toIntOrNull()
                addRating(rating)
                this.apiId = originalTitle
            }
        } else {
            val videoLinks = extractVideoLinks(doc)
            
            // Se non troviamo link, controlla se c'è un iframe con src
            val iframeLinks = doc.select("iframe").mapNotNull { iframe ->
                val src = iframe.attr("src")
                src.takeIf { it.isNotBlank() && !it.contains("google") && !it.contains("youtube") }
            }
            
            val allLinks = (videoLinks + iframeLinks).distinct()

            newMovieLoadResponse(title, url, TvType.Movie, allLinks.joinToString(",")) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year?.toIntOrNull()
                this.duration = duration
                addRating(rating)
                this.apiId = originalTitle
            }
        }
    }

    private fun addRating(builder: LoadResponse.Builder, rating: String) {
        if (rating.isNotBlank()) {
            builder.addScore(Score.from(rating.replace(",", "."), 10, 1))
        }
    }

    private fun extractVideoLinks(doc: Document): List<String> {
        val links = mutableListOf<String>()
        
        // Cerca iframe diretti
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("google") && !src.contains("youtube")) {
                links.add(src)
            }
        }
        
        // Cerca link nei mirror
        doc.select("[data-link], .mirrors a, .mr").forEach { element ->
            val link = element.attr("data-link").ifEmpty { element.attr("href") }
            if (link.isNotBlank() && !link.contains("#") && !link.contains("javascript")) {
                links.add(link)
            }
        }
        
        // Cerca link negli script JSON
        val jsonPattern = Regex("""['"](?:url|src|file|link)['"]\s*:\s*['"]([^'"]+(?:supervideo|dropload|vixcloud)[^'"]+)['"]""", RegexOption.IGNORE_CASE)
        jsonPattern.findAll(doc.html()).forEach {
            links.add(it.groupValues[1])
        }
        
        // Se ci sono link mostraguarda, carica la pagina per estrarre i mirror
        return links.flatMap { link ->
            if (link.contains("mostraguarda")) {
                try {
                    val iframeDoc = app.get(link).document
                    iframeDoc.select("[data-link], .mirrors a, .mr").mapNotNull { 
                        it.attr("data-link").ifEmpty { it.attr("href") }
                    }.filter { it.isNotBlank() && !it.contains("mostraguarda") }
                } catch (e: Exception) {
                    listOf(link)
                }
            } else {
                listOf(link)
            }
        }.distinct()
    }

    private fun getSeriesEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seriesPoster = doc.select("img.lazyload").firstOrNull()?.attr("data-src")
        
        // CORREZIONE 10: Parsing episodi dal formato tabs_holder (come nell'HTML della serie)
        val seasons = doc.select("#tabs_holder .tab-pane")
        
        if (seasons.isNotEmpty()) {
            seasons.forEach { seasonPane ->
                val seasonId = seasonPane.id()
                val seasonNum = seasonId.replace("season-", "").toIntOrNull() ?: 1
                
                seasonPane.select("li").forEach { episodeLi ->
                    val episodeLink = episodeLi.select("a[id^='serie-']").first()
                    
                    if (episodeLink != null) {
                        val episodeNum = episodeLink.attr("data-num")
                            .substringAfter("x").toIntOrNull()
                        
                        val episodeTitle = episodeLink.attr("data-title")
                            .substringBefore(":").trim()
                        
                        val episodePlot = episodeLink.attr("data-title")
                            .substringAfter(": ").takeIf { it != episodeTitle }
                        
                        // Raccogli tutti i mirror per questo episodio
                        val mirrors = episodeLi.select(".mirrors a.mr, .mirrors a.me").mapNotNull { mirror ->
                            mirror.attr("data-link").ifEmpty { mirror.attr("href") }
                        }.filter { it.isNotBlank() && !it.contains("#") }
                        
                        if (mirrors.isNotEmpty()) {
                            episodes.add(
                                newEpisode(mirrors.joinToString(",")) {
                                    this.season = seasonNum
                                    this.episode = episodeNum
                                    this.name = episodeTitle
                                    this.description = episodePlot
                                    this.posterUrl = fixUrlNull(seriesPoster)
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Cerca episodi nel formato della home page
            val episodeElements = doc.select("#dle-content .boxgrid.caption")
            episodeElements.forEach { element ->
                val seasonSpan = element.select(".se_num").text()
                val (season, episode) = parseSeasonEpisode(seasonSpan)
                
                val title = element.select("h3 a, .cover h2 a").text().trim()
                val link = element.select("a[href]").attr("href")
                val poster = element.select("img.lazyload").attr("data-src")
                
                if (season != null && episode != null && link.isNotBlank()) {
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
            
            // Cerca episodi nel formato hidden
            val hiddenEpisodes = doc.select(".ml-item-hiden").mapNotNull { hidden ->
                val seasonEpisode = hidden.select(".se_num").text()
                val (season, episode) = parseSeasonEpisode(seasonEpisode)
                
                val title = hidden.select(".h4").text()
                val link = hidden.select("a.ml-watch").attr("href")
                
                if (season != null && episode != null && link.isNotBlank()) {
                    newEpisode(link) {
                        this.season = season
                        this.episode = episode
                        this.name = title
                        this.posterUrl = fixUrlNull(seriesPoster)
                    }
                } else null
            }
            
            episodes.addAll(hiddenEpisodes)
        }
        
        return episodes.distinctBy { "${it.season}-${it.episode}" }.sortedBy { it.episode }
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
            text.matches(Regex("\\d+")) -> {
                text.toIntOrNull() to 1
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
        Log.d("Altadefinizione", "Loading links from: $data")
        
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
            val cleanedLink = link.trim()
            when {
                cleanedLink.contains("dropload.tv") || cleanedLink.contains("dropload.pro") -> {
                    DroploadExtractor().getUrl(cleanedLink, null, subtitleCallback, callback)
                }
                cleanedLink.contains("supervideo.cc") || cleanedLink.contains("vixcloud") -> {
                    MySupervideoExtractor().getUrl(cleanedLink, null, subtitleCallback, callback)
                }
                cleanedLink.contains("/filmgratis/") -> {
                    // Link al player 4K - potrebbe richiedere un extractore speciale
                    // Per ora lo passiamo a MySupervideo come fallback
                    MySupervideoExtractor().getUrl(cleanedLink, null, subtitleCallback, callback)
                }
                else -> {
                    // Prova entrambi gli extractor
                    try {
                        MySupervideoExtractor().getUrl(cleanedLink, null, subtitleCallback, callback)
                    } catch (e: Exception) {
                        DroploadExtractor().getUrl(cleanedLink, null, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }
}
