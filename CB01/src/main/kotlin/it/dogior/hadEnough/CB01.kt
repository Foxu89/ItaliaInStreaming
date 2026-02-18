package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class IlGenioDelloStreaming : MainAPI() {
    override var name = "Il Genio dello Streaming"
    override var mainUrl = "https://il-geniodellostreaming.cyou"
    override var lang = "it"
    override val hasMainPage = true
    override val hasSearchSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // IMPORTANTE: NON mettere 'override' qui se non richiesto
    var hasSearchSupport = true
    var headers: Map<String, String>? = null

    // --- PAGINA PRINCIPALE ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Sezione "Film del Momento" (ID 'dt-insala')
        document.selectFirst("div#dt-insala.items")?.let { section ->
            val items = section.select("article.item.movies").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) homePages.add(HomePageList("Film del Momento", items))
        }

        // Sezione "Ultimi inseriti/aggiornati" (secondo div.items)
        document.select("div.items").getOrNull(1)?.let { section ->
            val items = section.select("article.item.movies").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) homePages.add(HomePageList("Ultimi inseriti", items))
        }

        // Sezione "Ultime Serie tv Aggiornate"
        document.select("div.items").firstOrNull()?.let { section ->
            val seriesItems = section.select("article.item.movies:has(span.se_num)").mapNotNull { it.toSearchResponse(isSeries = true) }
            if (seriesItems.isNotEmpty()) homePages.add(HomePageList("Ultime Serie TV", seriesItems))
        }

        return HomePageResponse(homePages)
    }

    // --- Funzione helper per convertire una card in SearchResponse ---
    private fun Element.toSearchResponse(isSeries: Boolean = false): SearchResponse? {
        val linkElement = this.selectFirst("div.poster a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = this.selectFirst("div.data h3 a")?.text() ?: return null
        val poster = this.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val quality = this.selectFirst("span.quality")?.text()
        val year = this.selectFirst("div.data span")?.text()?.toIntOrNull()

        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return MovieSearchResponse(
            name = title,
            url = href,
            apiName = this@IlGenioDelloStreaming.name,
            type = tvType,
            posterUrl = poster,
            year = year,
            quality = quality?.let { Qualities.fromString(it) }
        )
    }

    // --- PAGINA DETTAGLIO ---
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.data h1")?.text()?.replace(" streaming", "") 
            ?: return LoadResponse.Error("Title not found")
        val poster = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val description = document.selectFirst("div.wp-content p")?.text()
        val year = document.selectFirst("span.date")?.text()?.substringAfterLast(" ")?.toIntOrNull()
        val rating = document.selectFirst("span.dt_rating_vgs")?.text()?.toFloatOrNull()?.toInt()
        val duration = document.selectFirst("span.runtime")?.text()?.replace(" Min.", "")?.toIntOrNull()
        val tags = document.select("div.sgeneros a").map { it.text() }

        // Verifica se è una serie TV
        val isSeries = document.selectFirst("select#season-select, div.episode-list") != null

        return if (isSeries) {
            TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = emptyList(), // TODO: Implementare episodi
                posterUrl = poster,
                plot = description,
                tags = tags,
                year = year,
                rating = rating
            )
        } else {
            MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = url,
                posterUrl = poster,
                plot = description,
                tags = tags,
                year = year,
                rating = rating,
                duration = duration
            )
        }
    }

    // --- LINK DEL PLAYER ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Player principale
        val iframeSrc = document.selectFirst("div.player-container iframe")?.attr("src")
        if (!iframeSrc.isNullOrEmpty()) {
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

        // Server alternativi
        document.select("ul.options-list a[data-link]").forEach { element ->
            val linkPath = element.attr("data-link")
            if (linkPath.isNotEmpty()) {
                val fullUrl = if (linkPath.startsWith("http")) linkPath else mainUrl + linkPath
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // --- RICERCA ---
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=${query.urlEncoded()}"
        val document = app.get(searchUrl).document
        
        return document.select("article.item.movies").mapNotNull { element ->
            val link = element.selectFirst("div.poster a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst("div.data h3 a")?.text() ?: return@mapNotNull null
            val poster = element.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
            val quality = element.selectFirst("span.quality")?.text()
            val year = element.selectFirst("div.data span")?.text()?.toIntOrNull()
            val isSeries = element.selectFirst("span.se_num") != null
            
            MovieSearchResponse(
                name = title,
                url = fixUrl(link),
                apiName = this.name,
                type = if (isSeries) TvType.TvSeries else TvType.Movie,
                posterUrl = poster,
                year = year,
                quality = quality?.let { Qualities.fromString(it) }
            )
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "https://il-geniodellostreaming.cyou$url"
    }
}
