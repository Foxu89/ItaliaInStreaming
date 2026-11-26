package it.dogior.hadEnough

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.UUID

/**
 * Plex Watch (contenuti gratuiti con pubblicità, watch.plex.tv).
 *
 * Catalogo: stessa API usata dal sito (vod.provider.plex.tv), con un token anonimo.
 * Video: playlist HLS in chiaro (nessun DRM) su vod.provider.plex.tv/library/parts/<id>-hls.m3u8.
 * Serve probabilmente un IP italiano (limiti geografici di Plex).
 */
class PlexWatch : MainAPI() {
    override var mainUrl = "https://watch.plex.tv"
    override var name = "Plex Watch"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    private val tag = "PlexWatch"
    private val api = "https://vod.provider.plex.tv"
    private val pageSize = 50

    override val mainPage = mainPageOf(
        "action" to "Azione",
        "animation" to "Animazione",
        "comedy" to "Commedia",
        "crime" to "Crime",
        "documentary" to "Documentario",
        "drama" to "Drammatico",
        "en-espanol" to "En Español",
        "horror" to "Horror",
        "musical" to "Musica",
        "romance" to "Romantico",
        "sci-fi" to "Fantascienza",
        "thriller" to "Thriller",
        "western" to "Western",
        "descriptive-audio" to "Audiodescrizione"
    )

    private val clientId = UUID.randomUUID().toString()
    private var cachedToken: String? = null
    private var tokenAt = 0L

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun plexHeaders(token: String?): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "X-Plex-Product" to "Plex Mediaverse",
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Language" to "it"
        )
        if (token != null) h["X-Plex-Token"] = token
        return h
    }

    /** Token anonimo: lo stesso che il sito richiede ai visitatori non registrati. */
    private suspend fun token(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now - tokenAt < 20 * 60_000L) return it }
        val res = app.post(
            "https://clients.plex.tv/api/v2/users/anonymous",
            headers = plexHeaders(null)
        ).text
        val t = mapper.readTree(res).get("authToken")?.asText()
        if (t.isNullOrBlank()) throw Exception("Token Plex non ottenuto: ${res.take(200)}")
        cachedToken = t
        tokenAt = now
        return t
    }

    private suspend fun getJson(url: String): JsonNode {
        val t = token()
        val sep = if (url.contains("?")) "&" else "?"
        val text = app.get("$url${sep}X-Plex-Token=$t", headers = plexHeaders(t)).text
        return mapper.readTree(text)
    }

    private fun poster(n: JsonNode): String? {
        val thumb = n.get("thumb")?.asText() ?: n.get("art")?.asText() ?: return null
        val full = if (thumb.startsWith("http")) thumb else return null
        return "https://images.plex.tv/photo?size=large-1280&url=${enc(full)}"
    }

    private fun items(root: JsonNode): List<JsonNode> =
        root.get("MediaContainer")?.get("Metadata")?.toList().orEmpty()

    // data dei risultati: "movie|<ratingKey>" oppure "show|<ratingKey>"
    private fun toSearch(n: JsonNode): SearchResponse? {
        val key = n.get("ratingKey")?.asText() ?: return null
        val title = n.get("title")?.asText() ?: return null
        val type = n.get("type")?.asText()
        val year = n.get("year")?.asInt()
        val data = "${if (type == "show") "show" else "movie"}|$key"
        return if (type == "show") {
            newTvSeriesSearchResponse(title, data, TvType.TvSeries) {
                this.posterUrl = poster(n); this.year = year
            }
        } else if (type == "movie") {
            newMovieSearchResponse(title, data, TvType.Movie) {
                this.posterUrl = poster(n); this.year = year
            }
        } else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val start = (page - 1) * pageSize
        val url = "$api/library/sections/movies/all?genre=${enc(request.data)}&type=99" +
                "&sort=${enc("ratingCount:desc")}&availabilityType=free&includePricing=1" +
                "&X-Plex-Container-Start=$start&X-Plex-Container-Size=$pageSize"
        val list = items(getJson(url)).mapNotNull { toSearch(it) }
        return newHomePageResponse(request.name, list, hasNext = list.size >= pageSize)
    }

    private fun parseSearch(root: JsonNode): List<SearchResponse> {
        val direct = items(root)
        // alcuni endpoint raggruppano i risultati in SearchResults[].SearchResult[].Metadata
        val grouped = root.get("MediaContainer")?.get("SearchResults")?.flatMap { g ->
            g.get("SearchResult")?.mapNotNull { it.get("Metadata") }.orEmpty()
        }.orEmpty()
        return (direct + grouped).mapNotNull { toSearch(it) }.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = enc(query)
        val urls = listOf(
            "https://discover.provider.plex.tv/library/search?query=$q&limit=30" +
                    "&searchTypes=movies,tv&searchProviders=discover,plexAVOD&includeMetadata=1",
            "$api/library/search?query=$q&searchTypes=movies,tv&includeMeta=1&limit=30",
            "$api/library/sections/movies/all?title=$q&type=99&availabilityType=free" +
                    "&X-Plex-Container-Start=0&X-Plex-Container-Size=30"
        )
        for (u in urls) {
            try {
                val res = parseSearch(getJson(u))
                Log.i(tag, "ricerca '$query' → ${res.size} risultati da ${u.substringBefore("?")}")
                if (res.isNotEmpty()) return res
            } catch (e: Exception) {
                Log.w(tag, "ricerca fallita su ${u.substringBefore("?")}: ${e.message}")
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val (kind, key) = url.split("|").let { it[0] to it.getOrElse(1) { "" } }
        if (key.isBlank()) return null
        val meta = items(getJson("$api/library/metadata/$key")).firstOrNull() ?: return null
        val title = meta.get("title")?.asText() ?: return null
        val year = meta.get("year")?.asInt()
        val plot = meta.get("summary")?.asText()
        val tags = meta.get("Genre")?.mapNotNull { it.get("tag")?.asText() }
        val actors = meta.get("Role")?.mapNotNull { it.get("tag")?.asText() }
        val poster = poster(meta)
        val minutes = meta.get("duration")?.asLong()?.let { (it / 60000).toInt() }

        if (kind == "show") {
            val episodes = mutableListOf<Episode>()
            val seasons = items(getJson("$api/library/metadata/$key/children"))
            for (s in seasons) {
                val sKey = s.get("ratingKey")?.asText() ?: continue
                val sNum = s.get("index")?.asInt() ?: 1
                for (e in items(getJson("$api/library/metadata/$sKey/children"))) {
                    val eKey = e.get("ratingKey")?.asText() ?: continue
                    episodes.add(newEpisode("ep|$eKey") {
                        this.name = e.get("title")?.asText()
                        this.season = sNum
                        this.episode = e.get("index")?.asInt()
                        this.description = e.get("summary")?.asText()
                        this.posterUrl = poster(e)
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addActors(actors)
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, "ep|$key") {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.duration = minutes
            addActors(actors)
        }
    }

    private suspend fun isPlaylist(url: String): Boolean = try {
        val text = app.get(url, headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")).text
        val ok = text.trimStart().startsWith("#EXTM3U")
        if (ok && (text.contains("skd://") || text.contains("streamingkeydelivery") ||
                    text.contains("#EXT-X-KEY") || text.contains("#EXT-X-SESSION-KEY"))) {
            Log.w(tag, "playlist protetta (FairPlay/DRM): ${text.lines().filter { it.contains("KEY") }.take(2)}")
            false
        } else ok
    } catch (e: Exception) {
        false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val key = data.substringAfter("|")
        val t = token()
        val meta = items(getJson("$api/library/metadata/$key")).firstOrNull()

        // id possibili della "parte" video: dal JSON dei metadati, poi la chiave stessa
        val candidates = linkedSetOf<String>()
        meta?.get("Media")?.forEach { m ->
            m.get("Part")?.forEach { p ->
                p.get("key")?.asText()?.let { k ->
                    Regex("/library/parts/([^/?]+?)(?:-hls)?(?:\\.m3u8)?(?:/|\\?|$)").find(k)
                        ?.groupValues?.get(1)?.let { candidates.add(it) }
                }
                p.get("id")?.asText()?.let { candidates.add(it) }
            }
        }
        candidates.add(key)

        for (id in candidates) {
            val m3u8 = "$api/library/parts/$id-hls.m3u8?includeAllStreams=1" +
                    "&x-plex-product=${enc("Plex Mediaverse")}&x-plex-token=$t"
            if (isPlaylist(m3u8)) {
                Log.i(tag, "playlist trovata con id $id")
                callback(
                    newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P720.value
                        this.headers = mapOf("Origin" to mainUrl)
                    }
                )
                return true
            }
        }
        // titoli protetti: versione DASH con Widevine
        for (id in candidates) {
            val mpd = "$api/library/parts/$id-dash.mpd?includeAllStreams=1" +
                    "&x-plex-product=${enc("Plex Mediaverse")}&x-plex-token=$t&x-plex-drm=widevine"
            val ok = try {
                app.get(mpd, headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/"))
                    .text.contains("<MPD")
            } catch (e: Exception) {
                false
            }
            if (ok) {
                Log.i(tag, "manifest DASH trovato con id $id")
                val license = "$api/library/parts/$id-dash/license?x-plex-token=$t&x-plex-drm=widevine"
                callback(
                    newDrmExtractorLink(name, name, mpd, ExtractorLinkType.DASH, WIDEVINE_UUID) {
                        this.licenseUrl = license
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P720.value
                        this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
                    }
                )
                return true
            }
        }
        Log.w(tag, "nessuna playlist valida per $key (provati: $candidates)")
        return false
    }
}
