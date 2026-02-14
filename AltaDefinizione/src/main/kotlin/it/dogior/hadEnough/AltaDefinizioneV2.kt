package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import it.dogior.hadEnough.extractors.DroploadExtractor
import it.dogior.hadEnough.extractors.MySupervideoExtractor
import okhttp3.FormBody
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
        // CORREZIONE: Estrai titolo senza anno
        val linkElement = this.selectFirst("h3 a, .cover h2 a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        var title = linkElement.text().trim()
        
        // Rimuovi l'anno dal titolo se presente
        title = title.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
        
        val img = this.selectFirst("img.lazyload, img.wp-post-image")
        val poster = img?.attr("data-src") ?: img?.attr("src")
        
        val type = if (this.selectFirst(".se_num") != null || href.contains("/serie-tv/")) 
            TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val requestBody = FormBody.Builder()
            .addEncoded("story", query)
            .addEncoded("do", "search")
            .addEncoded("subaction", "search")
            .build()

        val doc = app.post(
            mainUrl,
            requestBody = requestBody,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )
        ).document
        
        return doc.select("#dle-content .boxgrid.caption").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // CORREZIONE: Titolo senza anno
        var title = doc.select("h1, h1 a, .single_head h1").text().trim()
            .replace("Streaming", "").replace("HD", "").replace("Gratis", "").trim()
        
        // Rimuovi l'anno dal titolo se presente (es: "Titolo (2025)")
        title = title.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
        
        if (title.isBlank()) {
            title = doc.select("title").text().split(" - ").firstOrNull() ?: "Sconosciuto"
        }
        
        // Poster
        val poster = doc.select("meta[itemprop=image]").attr("content").ifEmpty { 
            doc.select("img.lazyload, img.wp-post-image").attr("data-src").ifEmpty {
                doc.select("img.lazyload, img.wp-post-image").attr("src")
            }
        }
        
        // Trama
        val plot = doc.select(".entry-content p").text().ifEmpty {
            doc.select(".ml-item-hiden p").text()
        }
        
        // Anno (per i metadati)
        val year = doc.select(".meta_dd:contains(Anno), .ml-label").text()
            .let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        
        // Generi
        val genres = doc.select(".meta_dd:contains(Categorie) a, .meta_dd:contains(Genere) a, .ml-cat a").map { it.text() }
            .filter { !it.contains("Serie TV") && !it.contains("Cinema") }

        // Verifica se è serie TV
        val isSeries = url.contains("/serie-tv/") || 
                       doc.select("#tabs_holder, .tt_season, .se_num").isNotEmpty()

        return if (isSeries) {
            val episodes = getSeriesEpisodes(doc)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        } else {
            // CORREZIONE: Estrai link come nella V1
            val iframe = doc.selectFirst("iframe")
            val videoLinks = if (iframe != null) {
                val src = iframe.attr("src")
                if (src.contains("mostraguarda")) {
                    try {
                        val mostraGuarda = app.get(src).document
                        mostraGuarda.select("ul._player-mirrors > li, [data-link], .mirrors a").mapNotNull {
                            val link = it.attr("data-link").ifEmpty { it.attr("href") }
                            if (link.contains("mostraguarda")) null else fixUrlNull(link)
                        }.filterNotNull()
                    } catch (e: Exception) {
                        listOf(src)
                    }
                } else {
                    listOf(src)
                }
            } else {
                // Cerca link nei mirror direttamente nella pagina
                doc.select("[data-link], .mirrors a, .mr").mapNotNull {
                    val link = it.attr("data-link").ifEmpty { it.attr("href") }
                    link.takeIf { it.isNotBlank() && !it.contains("#") && !it.contains("javascript") }
                }
            }

            if (videoLinks.isEmpty()) return null

            newMovieLoadResponse(title, url, TvType.Movie, videoLinks) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }
    }

    private fun getSeriesEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seriesPoster = doc.select("img.lazyload").firstOrNull()?.attr("data-src")
        
        // Parsing episodi dal formato tabs_holder
        val seasons = doc.select("#tabs_holder .tab-pane")
        
        if (seasons.isNotEmpty()) {
            seasons.forEach { seasonPane ->
                val seasonNum = seasonPane.id().replace("season-", "").toIntOrNull() ?: 1
                
                seasonPane.select("li").forEach { episodeLi ->
                    val episodeLink = episodeLi.select("a[id^='serie-']").first()
                    
                    if (episodeLink != null) {
                        val episodeNum = episodeLink.attr("data-num")
                            .substringAfter("x").toIntOrNull() ?: (episodes.size + 1)
                        
                        val episodeTitle = episodeLink.attr("data-title")
                            .substringBefore(":").trim()
                        
                        // Raccogli tutti i mirror per questo episodio
                        val mirrors = episodeLi.select(".mirrors a.mr, .mirrors a.me").mapNotNull { mirror ->
                            mirror.attr("data-link").ifEmpty { mirror.attr("href") }
                        }.filter { it.isNotBlank() && !it.contains("#") }
                        
                        if (mirrors.isNotEmpty()) {
                            episodes.add(
                                newEpisode(mirrors) {
                                    this.season = seasonNum
                                    this.episode = episodeNum
                                    this.name = episodeTitle
                                    this.posterUrl = fixUrlNull(seriesPoster)
                                }
                            )
                        }
                    }
                }
            }
        }
        
        return episodes.distinctBy { "${it.season}-${it.episode}" }.sortedBy { it.episode }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("Altadefinizione", "Loading links from: $data")
        
        // CORREZIONE: Parsing come JSON array (come nella V1)
        val links = try {
            parseJson<List<String>>(data)
        } catch (e: Exception) {
            listOf(data)
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
                else -> {
                    MySupervideoExtractor().getUrl(cleanedLink, null, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
