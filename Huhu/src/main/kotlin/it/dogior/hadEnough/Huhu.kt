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
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import java.security.SecureRandom

class Huhu(domain: String, private val countries: Map<String, Boolean>, language: String) : MainAPI() {
    override var mainUrl = "https://$domain"
    override var name = "Huhu"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = language
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    companion object {
        var channels = emptyList<Channel>()
        const val posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/Huhu/tv.png"
        val resolveUA = "MediaHubMX/2"
        val authUA = "okhttp/4.11.0"
        var sign: AuthSign? = null
        val uniqueId = ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }

    data class AuthSign(
        val sign: String,
        val expires: Long
    )

    data class ChannelData(
        val id: String,
        val name: String,
        val url: String
    )

    private suspend fun getAuthSign(): AuthSign? {
        if (sign != null && sign!!.expires > System.currentTimeMillis()) return sign
        
        try {
            val payload = mapOf(
                "token" to "ldCvE092e7gER0rVIajfsXIvRhwlrAzP6_1oEJ4q6HH89QHt24v6NNL_jQJO219hiLOXF2hqEfsUuEWitEIGN4EaHHEHb7Cd7gojc5SQYRFzU3XWo_kMeryAUbcwWnQrnf0-",
                "reason" to "app-blur",
                "locale" to "de",
                "theme" to "dark",
                "metadata" to mapOf(
                    "device" to mapOf(
                        "type" to "Handset",
                        "brand" to "google",
                        "model" to "Nexus",
                        "name" to "21081111RG",
                        "uniqueId" to uniqueId
                    ),
                    "os" to mapOf(
                        "name" to "android",
                        "version" to "7.1.2",
                        "abis" to listOf("arm64-v8a"),
                        "host" to "android"
                    ),
                    "app" to mapOf(
                        "platform" to "android",
                        "version" to "1.1.0",
                        "buildId" to "97215000",
                        "engine" to "hbc85",
                        "signatures" to listOf("6e8a975e3cbf07d5de823a760d4c2547f86c1403105020adee5de67ac510999e"),
                        "installer" to "com.android.vending"
                    ),
                    "version" to mapOf(
                        "package" to "app.lokke.main",
                        "binary" to "1.1.0",
                        "js" to "1.1.0"
                    )
                ),
                "appFocusTime" to 0,
                "playerActive" to false,
                "playDuration" to 0,
                "devMode" to true,
                "hasAddon" to true,
                "castConnected" to false,
                "package" to "app.lokke.main",
                "version" to "1.1.0",
                "process" to "app",
                "firstAppStart" to (System.currentTimeMillis() - 86400000),
                "lastAppStart" to System.currentTimeMillis(),
                "ipLocation" to null,
                "adblockEnabled" to false,
                "proxy" to mapOf(
                    "supported" to listOf("ss", "openvpn"),
                    "engine" to "openvpn",
                    "ssVersion" to 1,
                    "enabled" to false,
                    "autoServer" to true,
                    "id" to "fi-hel"
                ),
                "iap" to mapOf("supported" to true)
            )
            
            val headers = mapOf(
                "accept" to "application/json",
                "user-agent" to authUA,
                "content-type" to "application/json; charset=utf-8"
            )
            
            val response = app.post(
                "https://www.lokke.app/api/app/ping",
                headers = headers,
                json = payload
            ).body.string()
            
            val signature = JSONObject(response).getString("addonSig")
            sign = AuthSign(signature, System.currentTimeMillis() + 3600000) // 1 ora
            return sign
        } catch (e: Exception) {
            Log.e("Huhu", "Error getting auth sign: ${e.message}")
            return null
        }
    }

    private suspend fun resolveUrl(vavooUrl: String): String? {
        try {
            val sign = getAuthSign()?.sign ?: return null
            
            val payload = mapOf(
                "language" to "de",
                "region" to "AT",
                "url" to vavooUrl,
                "clientVersion" to "3.0.2"
            )
            
            val headers = mapOf(
                "user-agent" to resolveUA,
                "accept" to "application/json",
                "content-type" to "application/json; charset=utf-8",
                "mediahubmx-signature" to sign
            )
            
            val response = app.post(
                "https://vavoo.to/mediahubmx-resolve.json",
                headers = headers,
                json = payload
            ).body.string()
            
            val channel = parseJson<List<ChannelData>>(response).firstOrNull()
            return channel?.url
            
        } catch (e: Exception) {
            Log.e("Huhu", "Error resolving URL: ${e.message}")
            return null
        }
    }

    private suspend fun getChannels(): List<Channel> {
        val enabledCountries = countries.filter { it.value }.keys.toList()
        val response = app.get("$mainUrl/channels").body.string()
        return parseJson<List<Channel>>(response).filter { it.country in enabledCountries }
    }

    fun Channel.toSearchResponse(): LiveSearchResponse {
        return newLiveSearchResponse(name, this.toJson(), TvType.Live) {
            this.posterUrl = Huhu.posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (channels.isEmpty()) {
            channels = getChannels()
        }
        val sections = channels.groupBy { it.country }.map {
            HomePageList(
                it.key,
                it.value.map { channel -> channel.toSearchResponse() },
                false
            )
        }.sortedBy { it.name }

        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (channels.isEmpty()) {
            channels = getChannels()
        }
        val matches = channels.filter { channel ->
            query.lowercase().replace(" ", "") in
                    channel.name.lowercase().replace(" ", "")
        }
        return matches.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = parseJson<Channel>(url)
        val originalUrl = "https://huhu.to/play/${channel.id}/index.m3u8"
        
        val resolvedUrl = resolveUrl(originalUrl) ?: originalUrl
        
        return newLiveStreamLoadResponse(channel.name, url, resolvedUrl) {
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
        callback(
            newExtractorLink(
                this.name,
                this.name,
                data,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.headers = mapOf("user-agent" to resolveUA)
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                Log.d("Huhu Interceptor", response.peekBody(1024).string())
                return response
            }
        }
    }

    data class Channel(
        @JsonProperty("country") val country: String,
        @JsonProperty("id") val id: Long,
        @JsonProperty("name") val name: String,
        @JsonProperty("p") val p: Int
    )
}
