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

    override val headers: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
    )

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

        // Sezione "Ultime Serie tv Aggiornate" (cerca il badge episodio)
        document.select("div.items").firstOrNull()?.let { section ->
            // Filtra gli articoli che hanno lo span con classe 'se_num' (indicatore di episodio)
            val seriesItems = section.select("article.item.movies:has(span.se_num)").mapNotNull { it.toSearchResponse(isSeries = true) }
            if (seriesItems.isNotEmpty()) homePages.add(HomePageList("Ultime Serie TV", seriesItems))
        }

        return newHomePageResponse(homePages)
    }

    // --- Funzione helper per convertire una card in SearchResponse ---
    private fun Element.toSearchResponse(isSeries: Boolean = false): SearchResponse? {
        // Cerca il link principale nella card (di solito dentro div.poster a)
        val linkElement = this.selectFirst("div.poster a") ?: return null
        val href = linkElement.attr("href")
        val title = this.selectFirst("div.data h3 a")?.text() ?: return null
        val poster = this.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val quality = this.selectFirst("span.quality")?.text()
        val year = this.selectFirst("div.data span")?.text()?.toIntOrNull()

        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            this.year = year
            quality?.let { this.quality = newQualityEnum(it) }
        }
    }

    // --- PAGINA DETTAGLIO ---
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Estrai titolo (pulito dalla parola "streaming")
        val title = document.selectFirst("div.data h1")?.text()?.replace(" streaming", "") ?: return LoadResponse.Error("Title not found")
        val poster = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val description = document.selectFirst("div.wp-content p")?.text()
        val year = document.selectFirst("span.date")?.text()?.substringAfterLast(" ")?.toIntOrNull()
        val rating = document.selectFirst("span.dt_rating_vgs")?.text()?.toFloatOrNull()
        val duration = document.selectFirst("span.runtime")?.text()?.replace(" Min.", "")?.toIntOrNull()
        val tags = document.select("div.sgeneros a").map { it.text() }

        // --- Decidi se è Film o Serie TV ---
        // Controlliamo se nella pagina c'è un selettore di episodi (es. un tag <select> per le stagioni)
        val isSeries = document.selectFirst("select#season-select, div.episode-list") != null

        return if (isSeries) {
            // --- LOGICA PER SERIE TV (da implementare con un esempio reale) ---
            newTvSeriesLoadResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.rating = rating?.toInt()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.rating = rating?.toInt()
                this.duration = duration
            }
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

        // --- 1. Estrai il player principale (iframe) ---
        val iframeSrc = document.selectFirst("div.player-container iframe")?.attr("src")
        if (!iframeSrc.isNullOrEmpty()) {
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

        // --- 2. Estrai i server alternativi dal menù a tendina ---
        // I link sono dentro <ul class="options-list">, con attributo 'data-link'
        document.select("ul.options-list a[data-link]").forEach { element ->
            val linkPath = element.attr("data-link")
            val serverName = element.text()
            if (linkPath.isNotEmpty()) {
                val fullUrl = fixUrl(linkPath).toString()
                // Usa l'estrattore generico. Se il link è un iframe o un video diretto, lo gestirà.
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // --- RICERCA (da implementare) ---
    override suspend fun search(query: String): List<SearchResponse> {
        // TODO: Implementare la ricerca quando avremo un esempio di URL di ricerca.
        // Per ora restituiamo lista vuota.
        return emptyList()
    }

    // --- Funzione helper per fissare gli URL (es. /path -> https://sito.com/path) ---
    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else mainUrl + url
    }

    // --- Helper per convertire stringhe qualità in SearchQuality ---
    private fun newQualityEnum(quality: String): SearchQuality {
        return when {
            quality.contains("4K", ignoreCase = true) -> SearchQuality.UHD_4K
            quality.contains("HD", ignoreCase = true) -> SearchQuality.HD
            quality.contains("SD", ignoreCase = true) -> SearchQuality.SD
            quality.contains("CAM", ignoreCase = true) -> SearchQuality.CAM
            quality.contains("TS", ignoreCase = true) -> SearchQuality.TS
            else -> SearchQuality.Unknown
        }
    }
}
