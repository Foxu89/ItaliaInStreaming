package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.AnimeSaturnExtractor
import it.dogior.hadEnough.AnimeSaturnAltExtractor
import it.dogior.hadEnough.AnimeSaturnEpisodeData
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

const val TAG = "AnimeSaturn"

class AnimeSaturn : MainAPI() {
    override var mainUrl = "https://www.animesaturn.cx"
    override var name = "AnimeSaturn"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val timeout = 60L

    override val mainPage = mainPageOf(
        "$mainUrl/toplist" to "Top Anime",
        "$mainUrl/animeincorso" to "Anime in Corso",
        "$mainUrl/newest" to "Nuove Aggiunte",
        "$mainUrl/upcoming" to "In Arrivo...",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url, timeout = timeout).document
        
        val items = when {
            request.data.contains("newest") || request.data.contains("upcoming") -> 
                extractNewestAnime(doc).filterNotNull()
            
            request.data.contains("animeincorso") -> 
                extractAnimeInCorso(doc).filterNotNull()
            
            request.data.contains("toplist") -> 
                extractTopAnime(doc).filterNotNull()
            
            else -> 
                extractNewestAnime(doc).filterNotNull()
        }
        
        val hasNext = doc.select("a:contains(Successivo)").isNotEmpty() ||
                     doc.select(".pagination .next").isNotEmpty() ||
                     doc.select(".pagination li.active + li").isNotEmpty()
        
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    private fun extractNewestAnime(doc: Document): List<SearchResponse?> {
        return doc.select(".anime-card-newanime.main-anime-card").mapNotNull { card ->
            val linkElement = card.select("a").first() ?: return@mapNotNull null
            val rawTitle = card.select("span").text().ifEmpty { 
                card.select(".card-text span").text() 
            }
            val title = cleanTitle(rawTitle)
            val href = fixUrl(linkElement.attr("href"))
            val poster = card.select("img").attr("src")
            
            val isDub = rawTitle.contains("(ITA)") || href.contains("-ITA")
            
            newAnimeSearchResponse(title, href) {
                this.posterUrl = fixUrlNull(poster)
                this.type = TvType.Anime
                addDubStatus(isDub)
            }
        }
    }

    private fun extractAnimeInCorso(doc: Document): List<SearchResponse?> {
        return doc.select(".sebox").mapNotNull { sebox ->
            val linkElement = sebox.select(".headsebox h2 a").first() ?: return@mapNotNull null
            val rawTitle = linkElement.text().trim()
            val title = cleanTitle(rawTitle)
            val href = fixUrl(linkElement.attr("href"))
            
            val poster = sebox.select(".bigsebox .l img").attr("src").ifEmpty {
                sebox.select(".bigsebox .l img").attr("data-src")
            }
            
            val isDub = rawTitle.contains("(ITA)") || href.contains("-ITA")
            
            newAnimeSearchResponse(title, href) {
                this.posterUrl = fixUrlNull(poster)
                this.type = TvType.Anime
                addDubStatus(isDub)
            }
        }
    }

    private fun extractTopAnime(doc: Document): List<SearchResponse?> {
        val items = mutableListOf<SearchResponse?>()
        
        doc.select(".w-100").forEach { container ->
            val linkElement = container.select("a[href*='/anime/']").first() ?: return@forEach
            val href = fixUrl(linkElement.attr("href"))
            
            val titleElement = container.select(".badge.badge-light").first()
            val rawTitle = titleElement?.ownText()?.trim() ?: linkElement.attr("title") ?: return@forEach
            val title = cleanTitle(rawTitle)
            
            val poster = container.select("img").attr("src").ifEmpty {
                container.select("img").attr("data-src")
            }
            
            val isDub = rawTitle.contains("(ITA)") || href.contains("-ITA")
            
            items.add(
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = fixUrlNull(poster)
                    this.type = TvType.Anime
                    addDubStatus(isDub)
                }
            )
        }
        
        return items
    }

    private fun cleanTitle(rawTitle: String): String {
        return rawTitle
            .replace(" Sub ITA", "")
            .replace(" (ITA)", "")
            .replace(" ITA", "")
            .replace(" Sub", "")
            .trim()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = "$mainUrl/index.php?search=1&key=${query}"
        
        try {
            val response = app.get(searchUrl, timeout = timeout).text
            val json = parseJson<List<Map<String, Any>>>(response)
            
            return json.mapNotNull { anime: Map<String, Any> ->
                val rawName = anime["name"] as? String ?: return@mapNotNull null
                val name = cleanTitle(rawName)
                val link = anime["link"] as? String ?: return@mapNotNull null
                val image = anime["image"] as? String ?: ""
                
                val isDub = rawName.contains("(ITA)") || link.contains("-ITA")
                
                newAnimeSearchResponse(name, "/anime/$link") {
                    this.posterUrl = fixUrlNull(image)
                    this.type = TvType.Anime
                    addDubStatus(isDub)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = timeout).document
        
        val rawTitle = doc.select(".anime-title-as b").text().ifEmpty {
            doc.select("h1").text()
        }
        val title = cleanTitle(rawTitle)
        
        var poster = doc.select("img.cover-anime").attr("src").ifEmpty {
            doc.select(".container img[src*='locandine']").attr("src")
        }
        
        if (poster.isNullOrBlank()) {
            poster = doc.select("#modal-cover-anime .modal-body img").attr("src").ifEmpty {
                doc.select("img[src*='copertine']").attr("src")
            }
        }
        
        val plot = doc.select("#shown-trama").text().ifEmpty {
            doc.select("#trama div").text()
        }
        
        val infoItems = doc.select(".bg-dark-as-box.mb-3.p-3.text-white").first()?.text() ?: ""
        
        val durationString = Regex("Durata episodi: ([^<]+)").find(infoItems)?.groupValues?.get(1)
        
        val duration = when {
            durationString?.contains("h") == true || durationString?.contains("e") == true -> {
                val hours = Regex("(\\d+)\\s?h").find(durationString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val minutes = Regex("(\\d+)\\s?min").find(durationString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                (hours * 60) + minutes
            }
            durationString != null -> {
                Regex("(\\d+)").find(durationString)?.groupValues?.get(1)?.toIntOrNull()
            }
            else -> null
        }
        
        val ratingString = Regex("Voto: ([\\d\\.]+)/5").find(infoItems)?.groupValues?.get(1)
        val rating = ratingString?.toFloatOrNull()?.times(2)?.toInt()
        
        val year = Regex("Data di uscita: .*?(\\d{4})").find(infoItems)?.groupValues?.get(1)?.toIntOrNull()
        
        val genres = doc.select(".badge.badge-light.generi-as").map { it.text() }
        
        val isDub = rawTitle.contains("(ITA)") || url.contains("-ITA")
        val dubStatus = if (isDub) DubStatus.Dubbed else DubStatus.Subbed
        
        val episodes = extractEpisodes(doc, poster)
        
        val episodeCount = Regex("Episodi: (\\d+)").find(infoItems)?.groupValues?.get(1)?.toIntOrNull() ?: episodes.size
        
        val isMovie = episodeCount == 1 && (duration != null && duration > 40)
        
        return if (isMovie) {
            val episodeUrl = doc.select(".btn-group.episodes-button a[href*='/ep/']").attr("href")
            
            newAnimeLoadResponse(title, url, TvType.AnimeMovie) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres.map { genre ->
                    genre.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                    }
                }
                this.year = year
                this.duration = duration
                addScore(rating?.toString())
                addEpisodes(dubStatus, listOf(
                    newEpisode(fixUrl(episodeUrl)) {
                        this.name = "Film"
                        this.episode = 1
                    }
                ))
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres.map { genre ->
                    genre.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                    }
                }
                this.year = year
                addScore(rating?.toString())
                addEpisodes(dubStatus, episodes)
            }
        }
    }

    private fun extractEpisodes(doc: Document, poster: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        doc.select(".btn-group.episodes-button a[href*='/ep/']").forEach { episodeLink ->
            val epUrl = fixUrl(episodeLink.attr("href"))
            val epText = episodeLink.text().trim()
            val epNum = Regex("\\d+").find(epText)?.value?.toIntOrNull() ?: 1
            
            try {
                val episodePage = app.get(epUrl, timeout = timeout).document
                
                val primaryLink = episodePage.select("a[href*='/watch?file=']:not([href*='&s=alt'])").attr("href")
                val altLink = episodePage.select("a[href*='&s=alt']").attr("href")
                
                val episodeData = AnimeSaturnEpisodeData(
                    primaryUrl = primaryLink,
                    altUrl = altLink.ifBlank { null }
                ).toJson()
                
                episodes.add(
                    newEpisode(episodeData) {
                        this.name = epText
                        this.episode = epNum
                        this.posterUrl = fixUrlNull(poster)
                    }
                )
                
            } catch (e: Exception) {
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epText
                        this.episode = epNum
                        this.posterUrl = fixUrlNull(poster)
                    }
                )
            }
        }
        
        return episodes.distinctBy { it.data }.sortedBy { it.episode }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val episodeData = parseJson<AnimeSaturnEpisodeData>(data)
            
            if (episodeData.primaryUrl.isNotBlank()) {
                AnimeSaturnExtractor().getUrl(episodeData.primaryUrl, mainUrl, subtitleCallback, callback)
            }
            
            if (!episodeData.altUrl.isNullOrBlank()) {
                AnimeSaturnAltExtractor().getUrl(episodeData.altUrl, mainUrl, subtitleCallback, callback)
            }
            
            true
        } catch (e: Exception) {
            AnimeSaturnExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
            AnimeSaturnAltExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
            true
        }
    }
}
