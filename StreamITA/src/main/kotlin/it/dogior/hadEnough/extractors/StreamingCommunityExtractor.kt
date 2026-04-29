package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder

class StreamingCommunityExtractor : ExtractorApi() {
    override val name = "StreamingCommunity"
    override val mainUrl = "https://streamingunity.dog"
    override val requiresReferer = false

    private val TAG = "SCExtractor"
    private val baseUrl = "https://streamingunity.dog"
    private val lang = "it"
    private val apiUrl = "${baseUrl}$lang"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

    private var inertiaVersion = ""
    private val headers = mutableMapOf(
        "Host" to "streamingunity.dog",
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    suspend fun searchAndGetLinks(
        tmdbId: Int,
        title: String,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            ensureSession()
            val searchResult = searchTitle(tmdbId, title, type) ?: return false
            getStreamLinks(searchResult, season, episode, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Errore: ${e.message}")
            false
        }
    }

    private suspend fun ensureSession() {
        if (headers["Cookie"]?.isNotBlank() == true && inertiaVersion.isNotBlank()) return

        // Primo accesso per i cookie di sessione
        val response = app.get("$apiUrl/archive", headers = headers)
        val cookies = StringBuilder()
        response.cookies.forEach { (key, value) ->
            cookies.append("$key=$value; ")
        }

        // CSRF token
        val csrfResponse = app.get(
            "${baseUrl}sanctum/csrf-cookie",
            headers = mapOf(
                "Referer" to "$apiUrl/",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to userAgent,
            )
        )
        csrfResponse.cookies.forEach { (key, value) ->
            if (!cookies.contains(key)) cookies.append("$key=$value; ")
        }
        headers["Cookie"] = cookies.toString().trimEnd(';', ' ')

        // Inertia version
        val pageData = response.document.select("#app").attr("data-page")
        inertiaVersion = pageData.substringAfter("\"version\":\"").substringBefore("\"")
    }

    private suspend fun searchTitle(tmdbId: Int, title: String, type: String): JSONObject? {
        val encodedQuery = URLEncoder.encode(title, "UTF-8")
        val url = "$apiUrl/search?q=$encodedQuery"
        val response = app.get(url).text
        val jsonText = extractInertiaJson(response)
        val json = JSONObject(jsonText)
        
        val props = json.optJSONObject("props") ?: return null
        val titles = props.optJSONArray("titles") ?: return null

        // Cerca match per TMDB ID
        for (i in 0 until titles.length()) {
            val item = titles.getJSONObject(i)
            if (item.optInt("tmdb_id") == tmdbId) return item
        }

        // Fallback: cerca per nome
        val normalizedSearch = title.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()

        for (i in 0 until titles.length()) {
            val item = titles.getJSONObject(i)
            val itemType = item.optString("type")
            if (itemType != type) continue
            
            val itemName = item.optString("name").lowercase()
                .replace(Regex("[^a-z0-9 ]"), "")
                .trim()
            
            if (itemName.contains(normalizedSearch) || normalizedSearch.contains(itemName)) {
                return item
            }
        }

        return null
    }

    private suspend fun getStreamLinks(
        searchResult: JSONObject,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val titleId = searchResult.optInt("id")
        val slug = searchResult.optString("slug")
        val titleType = searchResult.optString("type")

        val iframeUrl = if (titleType == "movie" || season == null || episode == null) {
            "$apiUrl/iframe/$titleId&canPlayFHD=1"
        } else {
            // Per serie TV, dobbiamo trovare l'episode_id
            val episodeId = getEpisodeId(titleId, slug, season, episode) ?: return false
            "$apiUrl/iframe/$titleId?episode_id=$episodeId&canPlayFHD=1"
        }

        return extractVixCloudLinks(iframeUrl, subtitleCallback, callback)
    }

    private suspend fun getEpisodeId(
        titleId: Int,
        slug: String,
        season: Int,
        episode: Int
    ): Int? {
        val detailUrl = "$apiUrl/titles/$titleId-$slug/season-$season"
        val response = app.get(detailUrl).text
        val jsonText = extractInertiaJson(response)
        val json = JSONObject(jsonText)
        
        val loadedSeason = json.optJSONObject("props")?.optJSONObject("loadedSeason") ?: return null
        val episodes = loadedSeason.optJSONArray("episodes") ?: return null

        for (i in 0 until episodes.length()) {
            val ep = episodes.getJSONObject(i)
            if (ep.optInt("number") == episode) {
                return ep.optInt("id")
            }
        }
        return null
    }

    private suspend fun extractVixCloudLinks(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val iframeResponse = app.get(iframeUrl).text
            val iframeDoc = Jsoup.parse(iframeResponse)
            val iframeSrc = iframeDoc.selectFirst("iframe")?.attr("src") ?: return false

            // VixCloud script extraction
            val vixcloudHeaders = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "*/*",
                "Referer" to apiUrl,
            )

            val vixcloudResponse = app.get(iframeSrc, headers = vixcloudHeaders).text
            val vixcloudDoc = Jsoup.parse(vixcloudResponse)
            
            val script = vixcloudDoc.select("script")
                .firstOrNull { it.data().contains("masterPlaylist") }
                ?.data()
                ?: return false

            val masterPlaylist = extractPlaylistUrl(script)
            if (masterPlaylist == null) {
                Log.e(TAG, "Master playlist non trovata")
                return false
            }

            callback(
                newExtractorLink(
                    source = "StreamingCommunity",
                    name = "StreamingCommunity",
                    url = masterPlaylist,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = vixcloudHeaders
                    this.referer = baseUrl
                }
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Errore estrazione VixCloud: ${e.message}")
            return false
        }
    }

    private fun extractPlaylistUrl(script: String): String? {
        return try {
            // Cerca masterPlaylist nel JSON
            val match = Regex(""""masterPlaylist"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)""").find(script)
                ?: Regex(""""url"\s*:\s*"([^"]+)""").find(script)
            
            val playlistUrl = match?.groupValues?.get(1)?.replace("\\/", "/") ?: return null

            // Estrai token ed expires
            val token = Regex(""""token"\s*:\s*"([^"]+)""").find(script)?.groupValues?.get(1)
            val expires = Regex(""""expires"\s*:\s*"([^"]+)""").find(script)?.groupValues?.get(1)

            if (token != null && expires != null) {
                val separator = if (playlistUrl.contains("?")) "&" else "?"
                "$playlistUrl${separator}token=$token&expires=$expires"
            } else {
                playlistUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore parsing playlist: ${e.message}")
            null
        }
    }

    private fun extractInertiaJson(html: String): String {
        val trimmed = html.trimStart()
        if (!trimmed.startsWith("<")) return trimmed

        return Jsoup.parse(html)
            .selectFirst("#app")
            ?.attr("data-page")
            ?.let { Parser.unescapeEntities(it, true) }
            ?: html
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Non usato direttamente
    }
}
