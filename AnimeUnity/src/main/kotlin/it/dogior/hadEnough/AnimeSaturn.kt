package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson  // <-- IMPORT MANCANTE
import it.dogior.hadEnough.extractors.AnimeSaturnExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeSaturn : MainAPI() {  // <-- AGGIUNTE PARENTESI ()
    override var mainUrl = "https://www.animesaturn.cx"
    override var name = "AnimeSaturn"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val timeout = 60L

    override val mainPage = mainPageOf(
        "$mainUrl/newest" to "Nuove Aggiunte",
        "$mainUrl/animeincorso" to "Anime in Corso",
        "$mainUrl/animelist" to "Archivio Anime",
        "$mainUrl/toplist" to "Top Anime",
        "$mainUrl/upcoming" to "Prossime Uscite"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url, timeout = timeout).document
        
        val items = when {
            request.data.contains("newest") -> extractNewestAnime(doc)
            request.data.contains("toplist") -> extractTopAnime(doc)
            else -> extractAnimeList(doc)
        }
        
        val hasNext = doc.select("a:contains(Successivo)").isNotEmpty() ||
                     doc.select(".pagination .next").isNotEmpty()
        
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    private fun extractNewestAnime(doc: Document): List<MovieSearchResponse> {
        return doc.select(".anime-card-newanime.main-anime-card").mapNotNull { card ->
            val linkElement = card.select("a").first() ?: return@mapNotNull null
            val title = card.select("span").text().ifEmpty { 
                card.select(".card-text span").text() 
            }
            val href = fixUrl(linkElement.attr("href"))
            val poster = card.select("img").attr("src")
            
            newMovieSearchResponse(title, href) {
                this.posterUrl = fixUrlNull(poster)
                this.type = TvType.Anime
            }
        }
    }

    private fun extractTopAnime(doc: Document): List<MovieSearchResponse> {
        return doc.select(".anime-card-newanime.main-anime-card").mapNotNull { card ->
            val linkElement = card.select("a").first() ?: return@mapNotNull null
            val title = card.select("span").text()
            val href = fixUrl(linkElement.attr("href"))
            val poster = card.select("img").attr("src")
            
            newMovieSearchResponse(title, href) {
                this.posterUrl = fixUrlNull(poster)
                this.type = TvType.Anime
            }
        }
    }

    private fun extractAnimeList(doc: Document): List<MovieSearchResponse> {
        return doc.select(".anime-card-newanime.main-anime-card").mapNotNull { card ->
            val linkElement = card.select("a").first() ?: return@mapNotNull null
            val title = card.select("span").text()
            val href = fixUrl(linkElement.attr("href"))
            val poster = card.select("img").attr("src")
            
            newMovieSearchResponse(title, href) {
                this.posterUrl = fixUrlNull(poster)
                this.type = TvType.Anime
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = "$mainUrl/index.php?search=1&key=${query}"
        
        try {
            val response = app.get(searchUrl, timeout = timeout).text
            val json = parseJson<List<Map<String, Any>>>(response)
            
            return json.mapNotNull { anime: Map<String, Any> ->  // <-- TIPO ESPLICITO
                val name = anime["name"] as? String ?: return@mapNotNull null
                val link = anime["link"] as? String ?: return@mapNotNull null
                val image = anime["image"] as? String ?: ""
                
                newMovieSearchResponse(name, "/anime/$link") {
                    this.posterUrl = fixUrlNull(image)
                    this.type = TvType.Anime
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = timeout).document
        
        val title = doc.select(".anime-title-as b").text().ifEmpty {
            doc.select("h1").text()
        }
        
        val poster = doc.select("img.cover-anime").attr("src").ifEmpty {
            doc.select(".container img[src*='locandine']").attr("src")
        }
        
        val plot = doc.select("#shown-trama").text().ifEmpty {
            doc.select("#trama div").text()
        }
        
        // Estrai informazioni
        val infoItems = doc.select(".bg-dark-as-box.mb-3.p-3.text-white").first()?.text() ?: ""
        
        val studio = Regex("Studio: (.*?)(?:\n|$)").find(infoItems)?.groupValues?.get(1) ?: ""
        val status = Regex("Stato: (.*?)(?:\n|$)").find(infoItems)?.groupValues?.get(1) ?: ""
        val episodesCount = Regex("Episodi: (\\d+)").find(infoItems)?.groupValues?.get(1)?.toIntOrNull()
        val duration = Regex("Durata episodi: (\\d+) min").find(infoItems)?.groupValues?.get(1)?.toIntOrNull()
        val ratingString = Regex("Voto: (\\d+\\.?\\d*)").find(infoItems)?.groupValues?.get(1)
        val rating = ratingString?.toFloatOrNull()?.times(2)?.toInt()
        
        val year = Regex("Data di uscita: .*?(\\d{4})").find(infoItems)?.groupValues?.get(1)?.toIntOrNull()
        
        val genres = doc.select(".badge.badge-light.generi-as").map { it.text() }
        
        val episodes = extractEpisodes(doc, poster)
        
        val isMovie = url.contains("/anime/") && episodes.isEmpty()
        
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                this.duration = duration
                addScore(rating?.toString())  // <-- CONVERTITO IN STRING
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                addScore(rating?.toString())  // <-- CONVERTITO IN STRING
            }
        }
    }

    private fun extractEpisodes(doc: Document, poster: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        doc.select(".btn-group.episodes-button a[href*='/ep/']").forEach { episodeLink ->
            val epUrl = fixUrl(episodeLink.attr("href"))
            val epText = episodeLink.text().trim()
            val epNum = Regex("\\d+").find(epText)?.value?.toIntOrNull() ?: 1
            
            episodes.add(
                newEpisode(epUrl) {
                    this.name = epText
                    this.episode = epNum
                    this.posterUrl = fixUrlNull(poster)
                }
            )
        }
        
        return episodes.distinctBy { it.data }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeSaturnExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
    }
}
