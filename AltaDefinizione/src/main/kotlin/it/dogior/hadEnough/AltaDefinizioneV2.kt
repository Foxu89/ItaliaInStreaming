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
        "$mainUrl/" to "🔥 I titoli del momento",
        "$mainUrl/cinema/" to "🎬 Al Cinema",
        "$mainUrl/serie-tv/" to "📺 Serie TV",
        "$mainUrl/netflix-streaming/" to "📀 Netflix",
        "$mainUrl/animazione/" to "🎨 Animazione",
        "$mainUrl/avventura/" to "🗺️ Avventura",
        "$mainUrl/azione/" to "💥 Azione",
        "$mainUrl/biografico/" to "📖 Biografico",
        "$mainUrl/commedia/" to "😂 Commedia",
        "$mainUrl/crime/" to "🔪 Crime",
        "$mainUrl/documentario/" to "🎥 Documentario",
        "$mainUrl/drammatico/" to "🎭 Drammatico",
        "$mainUrl/erotico/" to "❤️ Erotico",
        "$mainUrl/famiglia/" to "👨‍👩‍👧 Famiglia",
        "$mainUrl/fantascienza/" to "🚀 Fantascienza",
        "$mainUrl/fantasy/" to "🐉 Fantasy",
        "$mainUrl/giallo/" to "🕵️ Giallo",
        "$mainUrl/guerra/" to "⚔️ Guerra",
        "$mainUrl/horror/" to "👻 Horror",
        "$mainUrl/musical/" to "🎵 Musical",
        "$mainUrl/poliziesco/" to "👮 Poliziesco",
        "$mainUrl/romantico/" to "💕 Romantico",
        "$mainUrl/sportivo/" to "⚽ Sportivo",
        "$mainUrl/storico-streaming/" to "📜 Storico",
        "$mainUrl/thriller/" to "🔪 Thriller",
        "$mainUrl/western/" to "🤠 Western"
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

    // Parsing della home page e delle pagine di categoria
    private fun Element.toSearchResponse(): SearchResponse? {
        // Slider home page
        val sliderLink = this.select("a").first()?.attr("href")
        val sliderTitle = this.select("img").attr("alt")
        val sliderPoster = fixUrl(this.select("img").attr("src"))
        if (!sliderLink.isNullOrEmpty() && sliderTitle.isNotEmpty()) {
            return newMovieSearchResponse(sliderTitle, sliderLink, TvType.Movie) {
                this.posterUrl = sliderPoster
            }
        }

        // Box griglia (.boxgrid)
        val boxLink = this.select(".cover_kapsul a, .cover.boxcaption a").attr("href")
        val boxTitle = this.select(".cover.boxcaption h2 a, h2 a").text()
        val boxPoster = fixUrl(this.select(".cover_kapsul img, img.wp-post-image").attr("data-src"))
        if (boxLink.isNotEmpty() && boxTitle.isNotEmpty()) {
            val type = if (boxLink.contains("/serie-tv/")) TvType.TvSeries else TvType.Movie
            return newMovieSearchResponse(boxTitle, boxLink, type) {
                this.posterUrl = boxPoster
            }
        }

        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = mutableListOf<SearchResponse>()

        // Sezione home: slider + boxgrid
        if (request.data == "$mainUrl/") {
            doc.select(".slider-item, .boxgrid").forEach { element ->
                element.toSearchResponse()?.let { items.add(it) }
            }
        } else {
            // Altre pagine (categorie, etc.)
            doc.select(".boxgrid, .mlnew").forEach { element ->
                element.toSearchResponse()?.let { items.add(it) }
            }
        }

        val hasNext = doc.select(".wp-pagenavi a:contains(Next), .pagin a:contains(Next)").isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/index.php?do=search&story=${query.replace(" ", "+")}").document
        val items = mutableListOf<SearchResponse>()
        doc.select(".boxgrid, .mlnew").forEach { element ->
            element.toSearchResponse()?.let { items.add(it) }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val content = doc.selectFirst("#dle-content, .single_icerik") ?: return null

        // Titolo
        val title = content.select("h1, .single_head h1, .movie_head h1").text()
            .replace("Streaming HD", "")
            .replace("Streaming", "")
            .trim()
            .ifEmpty { "Sconosciuto" }

        // Poster
        val poster = fixUrl(content.select("img.wp-post-image, .imagen img, .fix img").attr("data-src"))

        // Trama
        val plot = content.select("#sfull, .entry-content p, .full-text").text()
            .substringAfter("Trama")
            .substringBefore("Fonte")
            .trim()

        // Rating
        val ratingText = content.select(".imdb_bg, .imdb_r .dato b, .entry-imdb").text()
            .replace("★", "")
            .replace("IMDB:", "")
            .trim()

        // Dettagli (anno, genere)
        var year: Int? = null
        val genres = mutableListOf<String>()
        content.select("#details > li, .data .meta_dd, .tv-info-list ul").forEach { detail ->
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

        // Se è una serie TV
        if (url.contains("/serie-tv/")) {
            val episodes = getEpisodes(doc)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                if (ratingText.isNotEmpty()) addScore(ratingText)
            }
        }

        // Altrimenti è un film: estrai i link dai player
        val mirrors = mutableListOf<String>()

        // 1) iframe diretto #mirrorFrame (embed)
        val embedSrc = doc.select("#mirrorFrame").attr("src")
        if (embedSrc.isNotEmpty()) {
            fixUrl(embedSrc)?.let { mirrors.add(it) }
        }

        // 2) iframe di mostraguarda
        val mostraGuardaSrc = doc.select("iframe[src*='mostraguarda']").attr("src")
        if (mostraGuardaSrc.isNotEmpty()) {
            try {
                val mostraGuardaDoc = app.get(mostraGuardaSrc).document
                // Cerca i mirror dentro mostraguarda
                mostraGuardaDoc.select("ul._player-mirrors li, .mirrors a.mr").forEach { mirror ->
                    val link = mirror.attr("data-link")
                    if (link.isNotEmpty() && !link.contains("mostraguarda")) {
                        fixUrl(link)?.let { mirrors.add(it) }
                    }
                }
                // Se non trova i data-link, cerca iframe
                if (mirrors.isEmpty()) {
                    mostraGuardaDoc.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src")
                        if (src.isNotEmpty() && (src.contains("supervideo") || src.contains("dropload"))) {
                            fixUrl(src)?.let { mirrors.add(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("Altadefinizione", "Error parsing mostraGuarda: ${e.message}")
            }
        }

        // 3) iframe generici nella pagina (fallback)
        if (mirrors.isEmpty()) {
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty() && (src.contains("supervideo") || src.contains("dropload"))) {
                    fixUrl(src)?.let { mirrors.add(it) }
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, mirrors.distinct()) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            if (ratingText.isNotEmpty()) addScore(ratingText)
        }
    }

    // Estrazione episodi per le serie TV
    private fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val poster = fixUrl(doc.selectFirst("img.wp-post-image, .imagen img, .fix img")?.attr("data-src"))

        val seasonTabs = doc.select(".tt_season ul li a, .tt-season ul li a")
        if (seasonTabs.isEmpty()) return emptyList()

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

                // Estrai i mirror (supervideo / dropload)
                val mirrors = mutableListOf<String>()
                episodeItem.select(".mirrors a.mr, .mirrors a").forEach { mirror ->
                    val link = mirror.attr("data-link")
                    if (link.isNotEmpty()) {
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
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Altadefinizione", "Loading links: $data")
        return try {
            val episodeData = parseJson<EpisodeData>(data)
            var success = false
            episodeData.mirrors.forEach { link ->
                Log.d("Altadefinizione", "Mirror: $link")
                success = true
                when {
                    link.contains("dropload.tv") -> DroploadExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    link.contains("supervideo.cc") -> MySupervideoExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    else -> loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            }
            success
        } catch (e: Exception) {
            Log.d("Altadefinizione", "Error: ${e.message}")
            false
        }
    }
}
