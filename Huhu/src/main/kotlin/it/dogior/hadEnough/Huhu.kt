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

class Huhu(domain: String, private val countries: Map<String, Boolean>, language: String) : MainAPI() {
    override var mainUrl = "https://$domain"
    override var name = "Huhu"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = language
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "Connection" to "keep-alive"
    )

    private val workerUrl = "https://bitter-butterfly-1eec.appbeta870.workers.dev"

    private suspend fun getStreamFromWorker(vavooUrl: String): String? {
        try {
            val encodedUrl = java.net.URLEncoder.encode(vavooUrl, "UTF-8")
            val workerReqUrl = "$workerUrl?url=$encodedUrl"
            Log.d("Huhu", "Calling worker: $workerReqUrl")
            
            val response = app.get(workerReqUrl, timeout = 20)
            
            if (response.isSuccessful) {
                val result = response.text.trim()
                Log.d("Huhu", "Worker response: ${result.take(200)}")
                if (result.startsWith("http")) {
                    return result
                }
            } else {
                Log.d("Huhu", "Worker returned status: ${response.code}")
            }
        } catch (e: Exception) {
            Log.d("Huhu", "Worker error: ${e.message}")
        }
        return null
    }

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
        val streamUrl = "https://huhu.to/play/${channel.id}/index.m3u8"
        return newLiveStreamLoadResponse(channel.name, url, streamUrl) {
            this.posterUrl = Companion.posterUrl
            this.tags = listOf(channel.country)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            val originalUrl = data
            Log.d("Huhu", "Original URL: $originalUrl")
            
            var finalUrl: String? = null
            
            finalUrl = getStreamFromWorker(originalUrl)
            
            if (finalUrl == null) {
                Log.d("Huhu", "⚠️ Worker failed, using original URL")
                finalUrl = originalUrl
            } else {
                Log.d("Huhu", "✅ Worker returned URL: $finalUrl")
            }
            
            callback(
                newExtractorLink(
                    this.name,
                    this.name,
                    finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = defaultHeaders
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
            
        } catch (e: Exception) {
            Log.e("Huhu", "Error in loadLinks: ${e.message}")
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
