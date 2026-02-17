package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import it.dogior.hadEnough.AnimeSaturnExtractor
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
            request.data.contains("newest") || request.data.contains("upcoming") -> 
                extractNewestAnime(doc).filterNotNull()
            
            request.data.contains("animeincorso") -> 
                extractAnimeInCorso(doc).filterNotNull()
            
            request.data.contains("toplist") -> 
                extractTopAnime(doc).filterNotNull()
            
            else -> 
                extractAnimeList(doc).filterNotNull()
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
            val title = card.select("span").text().ifEmpty { 
                card.select(".card-text span").text() 
            }
            val href = fixUrl(linkElement.attr("href"))
            val poster = card.select("img").attr("src")
            
            val isDub = title.contains("(ITA)") || href.contains("-ITA")
            
            newAnimeSearchResponse(title.replace(" (ITA)", ""), href) {
                this.posterUrl = fixUrlNull(poster)
                this.type = TvType.Anime
                addDubStatus(isDub)
            }
        }
    }

    private fun extractAnimeInCorso(doc: Document): List<SearchResponse?> {
        return doc.select(".sebox").mapNotNull { sebox ->
            val linkElement = sebox.select(".headsebox h2 a").first() ?: return@mapNotNull null
            val title = linkElement.text().trim()
            val href = fixUrl(linkElement.attr("href"))
            
            val poster = sebox.select(".bigsebox .l img").attr("src").ifEmpty {
                sebox.select(".bigsebox .l img").attr("data-src")
            }
            
            val isDub = title.contains("(ITA)") || href.contains("-ITA")
            
            newAnimeSearchResponse(title.replace(" (ITA)", ""), href) {
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
            val title = titleElement?.ownText()?.trim() ?: linkElement.attr("title") ?: return@forEach
            
            val poster = container.select("img").attr("src").ifEmpty {
                container.select("img").attr("data-src")
            }
            
            val isDub = title.contains("(ITA)") || href.contains("-ITA")
            
            items.add(
                newAnimeSearchResponse(title.replace(" (ITA)", ""), href) {
                    this.posterUrl = fixUrlNull(poster)
                    this.type = TvType.Anime
                    addDubStatus(isDub)
                }
            )
        }
        
        return items
    }

    private fun extractAnimeList(doc: Document): List<SearchResponse?> {
        val cards = when {
            doc.select(".anime-card-newanime.main-anime-card").isNotEmpty() -> 
                doc.select(".anime-card-newanime.main-anime-card")
            doc.select(".sebox").isNotEmpty() -> 
                doc.select(".sebox")
            doc.select(".w-100").size > 5 -> 
                doc.select(".w-100").take(50)
            else -> doc.select("div[class*='anime'], div[class*='movie']")
        }
        
        return cards.mapNotNull { card ->
            val linkElement = card.select("a[href*='/anime/']").first() 
                ?: card.select("a").first() 
                ?: return@mapNotNull null
                
            val title = card.select("h2, h3, h4, .title, .badge").first()?.text() 
                ?: linkElement.text() 
                ?: return@mapNotNull null
                
            val href = fixUrl(linkElement.attr("href"))
            val poster = card.select("img").attr("src").ifEmpty {
                card.select("img[data-src]").attr("data-src")
            }
            
            val isDub = title.contains("(ITA)") || href.contains("-ITA")
            
            newAnimeSearchResponse(title.replace(" (ITA)", ""), href) {
                this.posterUrl = fixUrlNull(poster.takeIf { it.isNotBlank() })
                this.type = TvType.Anime
                addDubStatus(isDub)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = "$mainUrl/index.php?search=1&key=${query}"
        
        try {
            val response = app.get(searchUrl, timeout = timeout).text
            val json = parseJson<List<Map<String, Any>>>(response)
            
            return json.mapNotNull { anime: Map<String, Any> ->
                val name = anime["name"] as? String ?: return@mapNotNull null
                val link = anime["link"] as? String ?: return@mapNotNull null
                val image = anime["image"] as? String ?: ""
                
                val isDub = name.contains("(ITA)") || link.contains("-ITA")
                
                newAnimeSearchResponse(name.replace(" (ITA)", ""), "/anime/$link") {
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
        
        val title = doc.select(".anime-title-as b").text().ifEmpty {
            doc.select("h1").text()
        }
        
        val poster = doc.select("img.cover-anime").attr("src").ifEmpty {
            doc.select(".container img[src*='locandine']").attr("src")
        }
        
        val plot = doc.select("#shown-trama").text().ifEmpty {
            doc.select("#trama div").text()
        }
        
        val infoItems = doc.select(".bg-dark-as-box.mb-3.p-3.text-white").first()?.text() ?: ""
        
        val duration = Regex("Durata episodi: (\\d+) min").find(infoItems)?.groupValues?.get(1)?.toIntOrNull()
        val ratingString = Regex("Voto: (\\d+\\.?\\d*)").find(infoItems)?.groupValues?.get(1)
        val rating = ratingString?.toFloatOrNull()?.times(2)?.toInt()
        val year = Regex("Data di uscita: .*?(\\d{4})").find(infoItems)?.groupValues?.get(1)?.toIntOrNull()
        
        val genres = doc.select(".badge.badge-light.generi-as").map { it.text() }
        
        val isDub = title.contains("(ITA)") || url.contains("-ITA")
        val dubStatus = if (isDub) DubStatus.Dubbed else DubStatus.Subbed
        
        val episodes = extractEpisodes(doc, poster)
        
        val isMovie = url.contains("/anime/") && episodes.isEmpty()
        
        return if (isMovie) {
            newAnimeLoadResponse(title.replace(" (ITA)", ""), url, TvType.AnimeMovie) {
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
                    newEpisode(url) {
                        this.name = "Film"
                    }
                ))
            }
        } else {
            newAnimeLoadResponse(title.replace(" (ITA)", ""), url, TvType.Anime) {
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
        AnimeSaturnExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
