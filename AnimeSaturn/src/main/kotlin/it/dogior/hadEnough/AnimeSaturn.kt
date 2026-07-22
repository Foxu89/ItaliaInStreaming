package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import it.dogior.hadEnough.AnimeSaturnExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

const val TAG = "AnimeSaturn"

class AnimeSaturn : MainAPI() {
    override var mainUrl = "https://animesaturn.ro"
    override var name = "AnimeSaturn"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val timeout = 60L

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Aggiunti",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, timeout = timeout).document

        val items = doc.select(".listupd.normal .excstf article.bs, .listupd article.bs").mapNotNull { card ->
            extractSearchResponse(card)
        }

        val hasNext = doc.select("a.r, .hpage a.r, a:contains(Successivo)").isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val url = "$mainUrl/?s=$query"
        val doc = app.get(url, timeout = timeout).document

        return doc.select(".listupd article.bs").mapNotNull { card ->
            extractSearchResponse(card)
        }
    }

    private fun extractSearchResponse(card: Element): SearchResponse? {
        val linkElement = card.select("a.tip").first() ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.select(".tt h2").text().ifEmpty {
            linkElement.attr("title")
        }
        val poster = card.select("img.thumb").attr("src").ifEmpty {
            card.select("img").attr("src")
        }
        val isDub = title.contains("(ITA)") || href.contains("-ITA")

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = fixUrlNull(poster)
            this.type = TvType.Anime
            addDubStatus(isDub)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = timeout).document

        val title = doc.select("h1.entry-title").text().ifEmpty {
            doc.select("h1").text()
        }

        val poster = doc.select("div.thumb img").attr("src").ifEmpty {
            doc.select("img.thumb.wp-post-image").attr("src").ifEmpty {
                doc.select(".bigcover .ime img").attr("src")
            }
        }

        val plot = doc.select("div.desc p").text().ifEmpty {
            doc.select("#trama div").text()
        }

        val infoText = doc.select("div.spe").text()

        val duration = Regex("""Durata[:\s]+(\d+)\s*min""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()

        val ratingString = Regex("""Valutazione\s+([\d.]+)""").find(doc.select("div.rating strong").text())?.groupValues?.get(1)
        val rating = ratingString?.toFloatOrNull()?.times(1)?.toInt()

        val year = Regex("""Uscita[:\s]+.*?(\d{4})""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()

        val genres = doc.select("div.genxed a").map { it.text() }

        val isDub = title.contains("(ITA)") || url.contains("-ITA")
        val dubStatus = if (isDub) DubStatus.Dubbed else DubStatus.Subbed

        val episodes = extractEpisodes(doc, url)

        val episodeCountStr = Regex("""Episodi[:\s]+(\d+)""").find(infoText)?.groupValues?.get(1)
        val episodeCount = episodeCountStr?.toIntOrNull() ?: episodes.size

        val isMovie = episodeCount == 1 && (duration != null && duration > 40)

        return if (isMovie) {
            newAnimeLoadResponse(title, url, TvType.AnimeMovie) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                this.duration = duration
                addScore(rating?.toString())
                addEpisodes(dubStatus, episodes)
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                addScore(rating?.toString())
                addEpisodes(dubStatus, episodes)
            }
        }
    }

    private suspend fun extractEpisodes(doc: Document, refererUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val seriesId = doc.select("#episodes").attr("data-series")
        if (seriesId.isNotBlank()) {
            val pageHtml = doc.html()
            val nonce = Regex("""nonce\s*=\s*'([^']+)'""").find(pageHtml)?.groupValues?.get(1)
            if (nonce != null) {
                var page = 0
                while (true) {
                    try {
                        val response = app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "X-Requested-With" to "XMLHttpRequest"
                            ),
                            data = mapOf(
                                "action" to "miruro_load_eplchunk",
                                "nonce" to nonce,
                                "series" to seriesId,
                                "page" to page.toString()
                            ),
                            timeout = timeout
                        )

                        val json = response.text.trim()
                        val jsonMatch = Regex(""""html"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
                        if (jsonMatch == null) break

                        val epHtml = jsonMatch.groupValues[1]
                            .replace("\\/", "/")
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                            .replace("\\t", "\t")

                        val items = org.jsoup.Jsoup.parse(epHtml).select("a")
                        if (items.isEmpty()) break

                        items.forEach { link ->
                            val epUrl = fixUrl(link.attr("href"))
                            val epNum = link.select(".epl-num").text().let {
                                Regex("\\d+").find(it)?.value?.toIntOrNull()
                            } ?: Regex("""/ep(?:isodio)?[-/](\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                            episodes.add(newEpisode(epUrl) {
                                this.name = "Episodio $epNum"
                                this.episode = epNum
                            })
                        }

                        page++
                    } catch (e: Exception) {
                        Log.e(TAG, "AJAX episode load failed (page $page): ${e.message}")
                        break
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            doc.select("a[href*='/ep-'], a[href*='/episodio-']").forEach { link ->
                val epUrl = fixUrl(link.attr("href"))
                val epNum = Regex("""/ep(?:isodio)?[-/](\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                episodes.add(newEpisode(epUrl) {
                    this.name = "Episodio $epNum"
                    this.episode = epNum
                })
            }
        }

        return episodes.distinctBy { it.data }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "🎬 loadLinks chiamato → data=$data")
        AnimeSaturnExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
        Log.i(TAG, "🔚 loadLinks completato")
        return true
    }
}