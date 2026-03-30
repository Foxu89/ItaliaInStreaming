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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "Connection" to "keep-alive"
    )

    private suspend fun getVavooSignature(): String? {
        try {
            val uniqueId = (1..16).map { "%02x".format((0..255).random()) }.joinToString("")
            
            val pingBody = mapOf(
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
                "firstAppStart" to System.currentTimeMillis() - 86400000,
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
                "User-Agent" to "okhttp/4.11.0",
                "Accept" to "application/json",
                "Content-Type" to "application/json; charset=utf-8",
                "Accept-Encoding" to "gzip"
            )

            val response = app.post(
                "https://www.lokke.app/api/app/ping",
                headers = headers,
                data = pingBody
            )

            val json = JSONObject(response.text)
            return json.optString("addonSig", null)
            
        } catch (e: Exception) {
            Log.e("Huhu", "Error getting signature: ${e.message}")
            return null
        }
    }

    private suspend fun resolveVavooUrl(vavooUrl: String, signature: String): String? {
        try {
            val resolveBody = mapOf(
                "language" to "de",
                "region" to "AT",
                "url" to vavooUrl,
                "clientVersion" to "3.0.2"
            )

            val headers = mapOf(
                "User-Agent" to "MediaHubMX/2",
                "Accept" to "application/json",
                "Content-Type" to "application/json; charset=utf-8",
                "Accept-Encoding" to "gzip",
                "mediahubmx-signature" to signature
            )

            val response = app.post(
                "https://vavoo.to/mediahubmx-resolve.json",
                headers = headers,
                data = resolveBody
            )

            val json = JSONObject(response.text)
            
            if (json.has("data") && json.getJSONObject("data").has("url")) {
                return json.getJSONObject("data").getString("url")
            }
            if (json.has("url")) {
                return json.getString("url")
            }
            
            return null
            
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

    companion object {
        var channels = emptyList<Channel>()

        @Suppress("ConstPropertyName")
        const val posterUrl =
            "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/Huhu/tv.png"
    }

    fun Channel.toSearchResponse(): LiveSearchResponse {
        return newLiveSearchResponse(
            name,
            this.toJson(),
            TvType.Live
        ) {
            this.posterUrl = Huhu.posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (channels.isEmpty()) {
            channels = getChannels()
        }
        val sections =
            channels.groupBy { it.country }.map {
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
        val streamUrl = "https://huhu.to/play/${channel.id}/index.m3u8"
        
        return newLiveStreamLoadResponse(
            channel.name,
            url,
            streamUrl
        ) {
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
            
            val signature = getVavooSignature()
            
            if (signature != null) {
                val resolvedUrl = resolveVavooUrl(originalUrl, signature)
                
                if (resolvedUrl != null) {
                    Log.d("Huhu", "Resolved URL successfully")
                    callback(
                        newExtractorLink(
                            this.name,
                            this.name,
                            resolvedUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = defaultHeaders
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
            
            Log.d("Huhu", "Using original URL (no resolution)")
            callback(
                newExtractorLink(
                    this.name,
                    this.name,
                    originalUrl,
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
        @JsonProperty("country")
        val country: String,
        @JsonProperty("id")
        val id: Long,
        @JsonProperty("name")
        val name: String,
        @JsonProperty("p")
        val p: Int
    )
}
