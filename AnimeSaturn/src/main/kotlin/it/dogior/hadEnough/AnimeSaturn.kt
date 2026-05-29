package it.dogior.hadEnough

import com.lagradost.api.Log
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
        "$mainUrl/toplist" to "Top Anime",
        "$mainUrl/animeincorso" to "Anime in Corso",
        "$mainUrl/newest" to "Nuove Aggiunte",
        "$mainUrl/upcoming" to "In Arrivo...",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "🏠 getMainPage() → sezione: '${request.name}', page: $page")

        val url = if (page == 1) request.data else "${request.data}?page=$page"
        Log.d(TAG, "🌐 getMainPage() → URL richiesta: $url")

        val doc = app.get(url, timeout = timeout).document
        Log.d(TAG, "📄 getMainPage() → documento ottenuto, titolo pagina: '${doc.title()}'")

        val items = when {
            request.data.contains("newest") || request.data.contains("upcoming") -> {
                Log.d(TAG, "🆕 getMainPage() → estrazione con extractNewestAnime()")
                extractNewestAnime(doc).filterNotNull()
            }

            request.data.contains("animeincorso") -> {
                Log.d(TAG, "📺 getMainPage() → estrazione con extractAnimeInCorso()")
                extractAnimeInCorso(doc).filterNotNull()
            }

            request.data.contains("toplist") -> {
                Log.d(TAG, "🏆 getMainPage() → estrazione con extractTopAnime()")
                extractTopAnime(doc).filterNotNull()
            }

            else -> {
                Log.d(TAG, "❓ getMainPage() → sezione non riconosciuta, fallback a extractNewestAnime()")
                extractNewestAnime(doc).filterNotNull()
            }
        }

        Log.d(TAG, "✅ getMainPage() → items trovati: ${items.size}")

        val hasNext = doc.select("a:contains(Successivo)").isNotEmpty() ||
                     doc.select(".pagination .next").isNotEmpty() ||
                     doc.select(".pagination li.active + li").isNotEmpty()

        Log.d(TAG, "⏭️ getMainPage() → hasNext: $hasNext")

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    private fun extractNewestAnime(doc: Document): List<SearchResponse?> {
        val cards = doc.select(".anime-card-newanime.main-anime-card")
        Log.d(TAG, "🆕 extractNewestAnime() → card trovate: ${cards.size}")

        return cards.mapNotNull { card ->
            val linkElement = card.select("a").first() ?: run {
                Log.d(TAG, "⚠️ extractNewestAnime() → nessun <a> trovato nella card, skip")
                return@mapNotNull null
            }
            val rawTitle = card.select("span").text().ifEmpty {
                card.select(".card-text span").text()
            }
            val title = cleanTitle(rawTitle)
            val href = fixUrl(linkElement.attr("href"))
            val poster = card.select("img").attr("src")

            val isDub = rawTitle.contains("(ITA)") || href.contains("-ITA")

            Log.d(TAG, "🎌 extractNewestAnime() → titolo: '$title', href: '$href', isDub: $isDub, poster: '$poster'")

            newAnimeSearchResponse(title, href) {
                this.posterUrl = fixUrlNull(poster)
                this.type = TvType.Anime
                addDubStatus(isDub)
            }
        }
    }

    private fun extractAnimeInCorso(doc: Document): List<SearchResponse?> {
        val seboxes = doc.select(".sebox")
        Log.d(TAG, "📺 extractAnimeInCorso() → sebox trovati: ${seboxes.size}")

        return seboxes.mapNotNull { sebox ->
            val linkElement = sebox.select(".headsebox h2 a").first() ?: run {
                Log.d(TAG, "⚠️ extractAnimeInCorso() → nessun link trovato nel sebox, skip")
                return@mapNotNull null
            }
            val rawTitle = linkElement.text().trim()
            val title = cleanTitle(rawTitle)
            val href = fixUrl(linkElement.attr("href"))

            val poster = sebox.select(".bigsebox .l img").attr("src").ifEmpty {
                sebox.select(".bigsebox .l img").attr("data-src")
            }

            val isDub = rawTitle.contains("(ITA)") || href.contains("-ITA")

            Log.d(TAG, "🎌 extractAnimeInCorso() → titolo: '$title', href: '$href', isDub: $isDub, poster: '$poster'")

            newAnimeSearchResponse(title, href) {
                this.posterUrl = fixUrlNull(poster)
                this.type = TvType.Anime
                addDubStatus(isDub)
            }
        }
    }

    private fun extractTopAnime(doc: Document): List<SearchResponse?> {
        val items = mutableListOf<SearchResponse?>()
        val containers = doc.select(".w-100")
        Log.d(TAG, "🏆 extractTopAnime() → container trovati: ${containers.size}")

        containers.forEach { container ->
            val linkElement = container.select("a[href*='/anime/']").first() ?: run {
                Log.d(TAG, "⚠️ extractTopAnime() → nessun link anime trovato nel container, skip")
                return@forEach
            }
            val href = fixUrl(linkElement.attr("href"))

            val titleElement = container.select(".badge.badge-light").first()
            val rawTitle = titleElement?.ownText()?.trim() ?: linkElement.attr("title") ?: run {
                Log.d(TAG, "⚠️ extractTopAnime() → titolo non trovato, skip")
                return@forEach
            }
            val title = cleanTitle(rawTitle)

            val poster = container.select("img").attr("src").ifEmpty {
                container.select("img").attr("data-src")
            }

            val isDub = rawTitle.contains("(ITA)") || href.contains("-ITA")

            Log.d(TAG, "🎌 extractTopAnime() → titolo: '$title', href: '$href', isDub: $isDub, poster: '$poster'")

            items.add(
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = fixUrlNull(poster)
                    this.type = TvType.Anime
                    addDubStatus(isDub)
                }
            )
        }

        Log.d(TAG, "✅ extractTopAnime() → items totali: ${items.size}")
        return items
    }

    private fun cleanTitle(rawTitle: String): String {
        val cleaned = rawTitle
            .replace(" Sub ITA", "")
            .replace(" (ITA)", "")
            .replace(" ITA", "")
            .replace(" Sub", "")
            .trim()
        Log.d(TAG, "🧹 cleanTitle() → '$rawTitle' → '$cleaned'")
        return cleaned
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "🔍 search() → query: '$query'")

        if (query.isBlank()) {
            Log.d(TAG, "⚠️ search() → query vuota, ritorno lista vuota")
            return emptyList()
        }

        val searchUrl = "$mainUrl/index.php?search=1&key=${query}"
        Log.d(TAG, "🌐 search() → URL ricerca: $searchUrl")

        try {
            val response = app.get(searchUrl, timeout = timeout).text
            Log.d(TAG, "📄 search() → risposta ricevuta, lunghezza: ${response.length} chars")

            val json = parseJson<List<Map<String, Any>>>(response)
            Log.d(TAG, "📋 search() → risultati JSON: ${json.size}")

            return json.mapNotNull { anime: Map<String, Any> ->
                val rawName = anime["name"] as? String ?: run {
                    Log.d(TAG, "⚠️ search() → 'name' assente o non String, skip")
                    return@mapNotNull null
                }
                val name = cleanTitle(rawName)
                val link = anime["link"] as? String ?: run {
                    Log.d(TAG, "⚠️ search() → 'link' assente o non String per '$name', skip")
                    return@mapNotNull null
                }
                val image = anime["image"] as? String ?: ""

                val isDub = rawName.contains("(ITA)") || link.contains("-ITA")

                Log.d(TAG, "🎌 search() → anime: '$name', link: '$link', isDub: $isDub, image: '$image'")

                newAnimeSearchResponse(name, "/anime/$link") {
                    this.posterUrl = fixUrlNull(image)
                    this.type = TvType.Anime
                    addDubStatus(isDub)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "❌ search() → eccezione: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "📂 load() → url: $url")

        val doc = app.get(url, timeout = timeout).document
        Log.d(TAG, "📄 load() → documento ottenuto, titolo pagina: '${doc.title()}'")

        val rawTitle = doc.select(".anime-title-as b").text().ifEmpty {
            doc.select("h1").text()
        }
        val title = cleanTitle(rawTitle)
        Log.d(TAG, "📛 load() → titolo grezzo: '$rawTitle', titolo pulito: '$title'")

        var poster = doc.select("img.cover-anime").attr("src").ifEmpty {
            doc.select(".container img[src*='locandine']").attr("src")
        }

        if (poster.isNullOrBlank()) {
            poster = doc.select("#modal-cover-anime .modal-body img").attr("src").ifEmpty {
                doc.select("img[src*='copertine']").attr("src")
            }
        }
        Log.d(TAG, "🖼️ load() → poster: '$poster'")

        val plot = doc.select("#shown-trama").text().ifEmpty {
            doc.select("#trama div").text()
        }
        Log.d(TAG, "📝 load() → plot (primi 80 char): '${plot.take(80)}'")

        val infoItems = doc.select(".bg-dark-as-box.mb-3.p-3.text-white").first()?.text() ?: ""
        Log.d(TAG, "ℹ️ load() → infoItems: '$infoItems'")

        val durationString = Regex("Durata episodi: ([^<]+)").find(infoItems)?.groupValues?.get(1)
        Log.d(TAG, "⏱️ load() → durationString: '$durationString'")

        val duration = when {
            durationString?.contains("h") == true || durationString?.contains("e") == true -> {
                val hours = Regex("(\\d+)\\s?h").find(durationString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val minutes = Regex("(\\d+)\\s?min").find(durationString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val total = (hours * 60) + minutes
                Log.d(TAG, "⏱️ load() → durata calcolata (ore+min): ${hours}h ${minutes}min → $total min")
                total
            }
            durationString != null -> {
                val total = Regex("(\\d+)").find(durationString)?.groupValues?.get(1)?.toIntOrNull()
                Log.d(TAG, "⏱️ load() → durata calcolata (solo numero): $total min")
                total
            }
            else -> {
                Log.d(TAG, "⏱️ load() → durata non trovata")
                null
            }
        }

        val ratingString = Regex("Voto: ([\\d\\.]+)/5").find(infoItems)?.groupValues?.get(1)
        val rating = ratingString?.toFloatOrNull()?.times(2)?.toInt()
        Log.d(TAG, "⭐ load() → ratingString: '$ratingString', rating (su 10): $rating")

        val year = Regex("Data di uscita: .*?(\\d{4})").find(infoItems)?.groupValues?.get(1)?.toIntOrNull()
        Log.d(TAG, "📅 load() → anno: $year")

        val genres = doc.select(".badge.badge-light.generi-as").map { it.text() }
        Log.d(TAG, "🏷️ load() → generi: $genres")

        val isDub = rawTitle.contains("(ITA)") || url.contains("-ITA")
        val dubStatus = if (isDub) DubStatus.Dubbed else DubStatus.Subbed
        Log.d(TAG, "🎙️ load() → isDub: $isDub, dubStatus: $dubStatus")

        val episodes = extractEpisodes(doc, poster)
        Log.d(TAG, "🎬 load() → episodi estratti: ${episodes.size}")

        val episodeCount = Regex("Episodi: (\\d+)").find(infoItems)?.groupValues?.get(1)?.toIntOrNull() ?: episodes.size
        Log.d(TAG, "🔢 load() → episodeCount (da info): $episodeCount")

        val isMovie = episodeCount == 1 && (duration != null && duration > 40)
        Log.d(TAG, "🎥 load() → isMovie: $isMovie (episodeCount=$episodeCount, duration=$duration)")

        return if (isMovie) {
            val episodeUrl = doc.select(".btn-group.episodes-button a[href*='/ep/']").attr("href")
            Log.d(TAG, "🎥 load() → carico come AnimeMovie, episodeUrl: '$episodeUrl'")

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
            Log.d(TAG, "📺 load() → carico come Anime (serie)")

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
        val episodeLinks = doc.select(".btn-group.episodes-button a[href*='/ep/']")
        Log.d(TAG, "🎬 extractEpisodes() → link episodi trovati: ${episodeLinks.size}")

        episodeLinks.forEach { episodeLink ->
            val epUrl = fixUrl(episodeLink.attr("href"))
            val epText = episodeLink.text().trim()
            val epNum = Regex("\\d+").find(epText)?.value?.toIntOrNull() ?: 1

            Log.d(TAG, "▶️ extractEpisodes() → episodio: '$epText', num: $epNum, url: '$epUrl'")

            episodes.add(
                newEpisode(epUrl) {
                    this.name = epText
                    this.episode = epNum
                    this.posterUrl = fixUrlNull(poster)
                }
            )
        }

        val distinct = episodes.distinctBy { it.data }
        Log.d(TAG, "✅ extractEpisodes() → episodi dopo distinctBy: ${distinct.size}")
        return distinct
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "🔗 loadLinks() → data: '$data', isCasting: $isCasting")
        AnimeSaturnExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
        Log.d(TAG, "✅ loadLinks() → getUrl() chiamato, ritorno true")
        return true
    }
}
