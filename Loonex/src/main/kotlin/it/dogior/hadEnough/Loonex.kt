package it.dogior.hadEnough

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.LoonexExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Loonex : MainAPI() {
    override var mainUrl = "https://loonex.eu/cartoni/"
    override var name = "Loonex"
    override var lang = "it"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Cartoon)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        mainUrl to "Novità",
        "${mainUrl}index.php?cat=all" to "Tutti i Cartoni",
    )

    private suspend fun fetch(url: String): String {
        return app.get(url).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = fetch(request.data)
        val document = Jsoup.parse(html)

        if (request.data.contains("cat=all")) {
            val cards = document.select("a[href*=?cartone=]").mapNotNull { link ->
                val card = link.selectFirst(".cartoon-card-cinematic") ?: return@mapNotNull null
                parseCard(link, card)
            }
            return newHomePageResponse(
                listOf(HomePageList(request.name, cards, isHorizontalImages = true)),
                false
            )
        }

        val lists = mutableListOf<HomePageList>()
        val bands = document.select("div.seamless-band")

        for (band in bands) {
            if (band.id() == "trendingBand") continue

            val titleEl = band.selectFirst("h3.brand-font")
            val sectionName = titleEl?.text()?.trim() ?: continue
            if (sectionName == "Le Collezioni") continue

            if (sectionName.contains("Consigliato", ignoreCase = true)) {
                val link = band.selectFirst("a[href*=?cartone=]") ?: continue
                val href = link.attr("href")
                val fullUrl = if (href.startsWith("http")) href
                    else mainUrl.trimEnd('/') + "/" + href.removePrefix("/")

                val poster = band.selectFirst("img.img-fluid.rounded-4")?.attr("src")?.let {
                    if (it.startsWith("//")) "https:$it"
                    else if (it.startsWith("/")) "https://loonex.eu$it"
                    else if (!it.startsWith("http")) "${mainUrl.trimEnd('/')}/$it"
                    else it
                } ?: continue

                val title = band.selectFirst("img.cartoon-title-logo")?.attr("alt")?.trim()
                    ?: href.substringAfter("?cartone=").substringBeforeLast("-")
                        .split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

                val item = newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
                lists.add(HomePageList(sectionName, listOf(item), isHorizontalImages = true))
                continue
            }

            val scroller = band.selectFirst("div.horizontal-scroller")
            if (scroller == null || scroller.select("div.scroller-item").isEmpty()) continue

            val items = scroller.select("div.scroller-item").mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val card = item.selectFirst(".cartoon-card-cinematic") ?: return@mapNotNull null
                parseCard(link, card)
            }
            if (items.isNotEmpty()) {
                lists.add(HomePageList(sectionName, items, isHorizontalImages = true))
            }
        }

        val trendingBand = document.selectFirst("#trendingBand")
        if (trendingBand != null) {
            val scroller = trendingBand.selectFirst("div.horizontal-scroller")
            if (scroller != null) {
                val items = scroller.select("div.scroller-item").mapNotNull { item ->
                    val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                    val card = item.selectFirst(".cartoon-card-cinematic") ?: return@mapNotNull null
                    parseCard(link, card)
                }
                if (items.isNotEmpty()) {
                    lists.add(HomePageList("I più visti", items, isHorizontalImages = true))
                }
            }
        }

        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}index.php?search=${java.net.URLEncoder.encode(query, "UTF-8")}&cat=all"
        val html = fetch(url)
        val document = Jsoup.parse(html)

        val results = document.select("a[href*=?cartone=]").mapNotNull { link ->
            val card = link.selectFirst(".cartoon-card-cinematic") ?: return@mapNotNull null
            parseCard(link, card)
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val html = fetch(url)
        val document = Jsoup.parse(html)

        val title = document.selectFirst("h1.display-4")?.text()?.trim() ?: "Sconosciuto"
        val poster = document.selectFirst("img.detail-poster")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let {
                if (it.startsWith("/")) "https://loonex.eu$it" else it
            } ?: ""
        val plot = document.selectFirst("div.content-box-opaque div.text-secondary:not(.text-sm)")?.ownText()?.trim()
            ?: document.selectFirst("div.text-secondary:not(.text-sm)")?.ownText()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: ""
        val tags = document.select("span.badge.bg-secondary").mapNotNull { it.text().trim().ifBlank { null } }
        val trailerUrl = document.selectFirst("iframe.poster-trailer-iframe")?.attr("src")?.let {
            if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) "https://loonex.eu$it" else it
        } ?: ""

        val backdropEl = document.selectFirst("div.hero-backdrop")
        val backdropStyle = backdropEl?.attr("style") ?: ""
        val backgroundUrl = Regex("""url\('?(.*?)'?\)""").find(backdropStyle)?.groupValues?.getOrNull(1) ?: ""

        val seasonTabs = document.select("ul#season-tabs li.nav-item button.nav-link")

        if (seasonTabs.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            val tabContent = document.selectFirst("div.tab-content#season-tabsContent")
            val seasonsData = mutableListOf<SeasonData>()

            seasonTabs.forEachIndexed { index, tabButton ->
                val seasonText = tabButton.text().trim()
                val seasonNum = index + 1
                seasonsData.add(SeasonData(seasonNum, seasonText))
                val targetId = tabButton.attr("data-bs-target").removePrefix("#")
                val tabPane = tabContent?.selectFirst("div.tab-pane#$targetId") ?: return@forEachIndexed

                val rows = tabPane.select("div.episode-row")
                rows.forEachIndexed { epIndex, row ->
                    val epTitle = row.selectFirst(".episode-title")?.text()?.trim() ?: "Episodio ${epIndex + 1}"
                    val playUrl = row.selectFirst("a.btn-play-sm")?.attr("href") ?: return@forEachIndexed

                    val epNum = Regex("""^(\d+):""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""^(\d+) -""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""Episodio \d+x(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""Episodio (\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

                    episodes.add(newEpisode(playUrl) {
                        name = epTitle
                        season = seasonNum
                        episode = epNum ?: (epIndex + 1)
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.backgroundPosterUrl = backgroundUrl
                addSeasonNames(seasonsData)
                addTrailer(trailerUrl)
                this.year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()
            }
        }

        val playUrl = document.selectFirst("a.btn-brand.auto-watch-btn, a.btn-play-sm.auto-watch-btn")?.attr("href") ?: ""

        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl = playUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.backgroundPosterUrl = backgroundUrl
            addTrailer(trailerUrl)
            this.year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        return try {
            LoonexExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseCard(link: Element, card: Element): SearchResponse? {
        val href = link.attr("href")
        val fullUrl = if (href.startsWith("http")) href else mainUrl.trimEnd('/') + "/" + href.removePrefix("/")

        val title = card.selectFirst(".card-title-cine")?.text()?.trim() ?: return null
        val poster = card.selectFirst(".card-img-bg")?.attr("src")?.let {
            if (it.startsWith("//")) "https:$it"
            else if (it.startsWith("/")) "https://loonex.eu$it"
            else if (!it.startsWith("http")) "${mainUrl.trimEnd('/')}/$it"
            else it
        } ?: ""
        val isMovie = card.selectFirst(".badge-custom.movie-badge") != null
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, fullUrl, type) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fullUrl, type) { this.posterUrl = poster }
        }
    }
}
