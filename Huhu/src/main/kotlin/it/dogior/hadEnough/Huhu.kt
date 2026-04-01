package it.dogior.hadEnough

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject

class Huhu(domain: String, private val countries: Map<String, Boolean>, language: String) : MainAPI() {
    override var mainUrl = "https://$domain"
    override var name = "Huhu"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = language
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "Connection" to "keep-alive"
    )

    private suspend fun getChannels(): List<Channel> {
        val enabledCountries = countries.filter { it.value }.keys.toList()
        val response = app.get("$mainUrl/channels").body.string()
        return parseJson<List<Channel>>(response).filter { it.country in enabledCountries }
    }

    companion object {
        var channels = emptyList<Channel>()
        const val posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/Huhu/tv.png"
    }

    fun Channel.toSearchResponse(): LiveSearchResponse {
        return newLiveSearchResponse(name, this.toJson(), TvType.Live) {
            this.posterUrl = Huhu.posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (channels.isEmpty()) channels = getChannels()
        val sections = channels.groupBy { it.country }.map {
            HomePageList(it.key, it.value.map { channel -> channel.toSearchResponse() }, false)
        }.sortedBy { it.name }
        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (channels.isEmpty()) channels = getChannels()
        val matches = channels.filter { channel ->
            query.lowercase().replace(" ", "") in channel.name.lowercase().replace(" ", "")
        }
        return matches.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = parseJson<Channel>(url)
        val originalUrl = "https://huhu.to/play/${channel.id}/index.m3u8"
        
        // 🔥 IL TRUCCO: risolvi l'URL in load(), non in loadLinks()!
        val resolvedUrl = resolveUrl(originalUrl)
        
        return newLiveStreamLoadResponse(channel.name, url, resolvedUrl) {
            this.posterUrl = Companion.posterUrl
            this.tags = listOf(channel.country)
        }
    }
    
    private suspend fun resolveUrl(vavooUrl: String): String {
        try {
            val payload = mapOf(
                "language" to "de",
                "region" to "AT",
                "url" to vavooUrl,
                "clientVersion" to "3.0.2"
            )
            
            val response = app.post(
                "https://vavoo.to/mediahubmx-resolve.json",
                headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                data = payload
            )
            
            val json = JSONObject(response.text)
            
            if (json.has("data") && json.getJSONObject("data").has("url")) {
                return json.getJSONObject("data").getString("url")
            }
            if (json.has("url")) {
                return json.getString("url")
            }
            
        } catch (e: Exception) {
            Log.e("Huhu", "Resolve error: ${e.message}")
        }
        return vavooUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            callback(
                newExtractorLink(
                    this.name,
                    this.name,
                    data,  // data è già l'URL risolto da load()
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = defaultHeaders
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        } catch (e: Exception) {
            Log.e("Huhu", "Error: ${e.message}")
            return false
        }
    }

    data class Channel(
        @JsonProperty("country") val country: String,
        @JsonProperty("id") val id: Long,
        @JsonProperty("name") val name: String,
        @JsonProperty("p") val p: Int
    )
}
