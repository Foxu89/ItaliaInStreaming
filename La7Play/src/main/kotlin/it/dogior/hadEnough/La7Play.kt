package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import org.jsoup.nodes.Document

/**
 * La7 Play: solo la sezione /film.
 *
 * Il video è protetto con DRM (Widevine, Conax) ma si riproduce con il flusso ufficiale del sito:
 *  1. dalla pagina del film si leggono drmContentId e cf_remote_url;
 *  2. tk.php restituisce un token temporaneo (preAuthToken);
 *  3. il manifest DASH è su CloudFront, la licenza Widevine su conax.cloud con l'header PreAuthorization.
 * Serve un IP italiano (il sito ha il geoblocco).
 */
class La7Play : MainAPI() {
    override var mainUrl = "https://www.la7play.it"
    override var name = "La7 Play"
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf("$mainUrl/film" to "Film")

    private val tag = "La7Play"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
    private val headers get() = mapOf("User-Agent" to ua, "Accept-Language" to "it-IT,it;q=0.9")

    // valori di riserva se nella pagina non si trovano
    private val defaultCdn = "https://d3iki3eydrtvsa.cloudfront.net"
    private val defaultTokenEndpoint = "https://svc-01.la7play.it/DRM/tk.php"
    private val defaultLicense = "https://la7.prod.conax.cloud/widevine/license"

    // ---------- elenco ----------

    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("a[href^=/film/]")
            .mapNotNull { a ->
                val img = a.selectFirst("img") ?: return@mapNotNull null
                val title = img.attr("alt").trim().ifBlank { return@mapNotNull null }
                val raw = a.attr("href")
                val href = if (raw.startsWith("http")) raw else mainUrl + raw
                val poster = img.attr("src").ifBlank { null }
                newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers).document
        val items = parseCards(doc)
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext = false)
    }

    // il sito mostra tutto il catalogo film in un'unica pagina: la ricerca filtra quella
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/film", headers = headers).document
        return parseCards(doc).filter { it.name.lowercase().contains(q) }
    }

    // ---------- dettaglio ----------

    private fun isoToMinutes(iso: String?): Int? {
        if (iso.isNullOrBlank()) return null
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(iso) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val s = m.groupValues[3].toIntOrNull() ?: 0
        val total = h * 60 + min + if (s >= 30) 1 else 0
        return total.takeIf { it > 0 }
    }

    /** Nodi del JSON-LD (anche dentro @graph). */
    private fun ldNodes(doc: Document): List<JsonNode> {
        val out = ArrayList<JsonNode>()
        doc.select("script[type=application/ld+json]").forEach { s ->
            try {
                val root = mapper.readTree(s.data())
                val graph = root.get("@graph")
                if (graph != null && graph.isArray) graph.forEach { out.add(it) } else out.add(root)
            } catch (e: Exception) {
                Log.w(tag, "json-ld non valido: ${e.message}")
            }
        }
        return out
    }

    private fun JsonNode.hasType(t: String): Boolean {
        val ty = get("@type") ?: return false
        return if (ty.isArray) ty.any { it.asText() == t } else ty.asText() == t
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = headers)
        val doc = res.document
        val nodes = ldNodes(doc)
        val movie = nodes.firstOrNull { it.hasType("Movie") }
        val video = nodes.firstOrNull { it.hasType("VideoObject") }

        val title = movie?.get("name")?.asText()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: return null
        val desc = movie?.get("description")?.asText()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = movie?.get("dateCreated")?.asText()?.take(4)?.toIntOrNull()
        val genres = movie?.get("genre")?.map { it.asText() }
            ?.filter { it.isNotBlank() && !it.equals("Film", true) }
        val duration = isoToMinutes(movie?.get("duration")?.asText() ?: video?.get("duration")?.asText())
        val director = movie?.get("director")?.get("name")?.asText()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val backdrop = video?.get("thumbnailUrl")?.let { if (it.isArray) it.firstOrNull()?.asText() else it.asText() }

        // cast: "Nome , Ruolo;Nome , Ruolo;..." dentro il JSON della pagina
        val unescaped = res.text.replace("\\\"", "\"")
        val actors = Regex(""""cast":"([^"]*)"""").find(unescaped)?.groupValues?.get(1)
            ?.split(";")
            ?.map { it.substringBefore(",").trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()

        val plot = buildString {
            append(desc.orEmpty())
            if (!director.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Regia: ").append(director)
            }
        }.ifBlank { null }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop ?: poster
            this.plot = plot
            this.year = year
            this.tags = genres
            this.duration = duration
            addActors(actors)
        }
    }

    // ---------- video ----------

    data class TokenRes(@JsonProperty("preAuthToken") val preAuthToken: String? = null)

    private fun String.firstGroup(regex: String): String? =
        Regex(regex).find(this)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = headers).text
        val t = html.replace("\\\"", "\"").replace("\\u0026", "&")

        val contentId = t.firstGroup(""""drmContentId":"(\d+)"""")
        val remote = t.firstGroup(""""cf_remote_url":"(/[^"]+)"""")
        if (contentId == null || remote == null) {
            Log.w(tag, "drmContentId/cf_remote_url non trovati (film senza DRM o pagina cambiata?)")
            return false
        }
        val cdn = t.firstGroup(""""hostnameCdnDrm":"([^"]+)"""") ?: defaultCdn
        val tokenEndpoint = t.firstGroup(""""drmTokenEndpoint":"([^"]+)"""") ?: defaultTokenEndpoint
        val license = t.firstGroup("""com\.widevine\.alpha\|([^;"]+)""") ?: defaultLicense

        // token temporaneo (~1 ora) per la licenza
        val token = try {
            app.get(
                "$tokenEndpoint?id=$contentId&contentType=VOD&v=${System.currentTimeMillis()}",
                headers = headers + mapOf("Origin" to mainUrl),
                referer = "$mainUrl/"
            ).parsedSafe<TokenRes>()?.preAuthToken
        } catch (e: Exception) {
            Log.w(tag, "token errore: ${e.message}")
            null
        }
        if (token.isNullOrBlank()) {
            Log.w(tag, "token non ottenuto (serve un IP italiano?)")
            return false
        }

        val mpd = "$cdn$remote/DASH$remote.mpd"
        Log.i(tag, "mpd=$mpd id=$contentId")

        callback(
            newDrmExtractorLink(name, name, mpd, ExtractorLinkType.DASH, WIDEVINE_UUID) {
                this.licenseUrl = license
                this.referer = "$mainUrl/"
                this.quality = Qualities.P720.value
                // gli header valgono anche per la richiesta della licenza
                this.headers = mapOf(
                    "PreAuthorization" to token,
                    "Origin" to mainUrl,
                    "User-Agent" to ua
                )
            }
        )
        return true
    }
}
