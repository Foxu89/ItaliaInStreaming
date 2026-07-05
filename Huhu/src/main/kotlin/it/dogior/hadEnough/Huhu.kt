package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLEncoder
import java.util.UUID

class Huhu(domain: String, private val countries: Map<String, Boolean>, language: String) : MainAPI() {
    override var mainUrl = "https://$domain"
    override var name = "Huhu"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = language
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val isVavoo = domain == "vavoo.to"

    private suspend fun getVypnSignature(): String? {
        val body = mutableMapOf<String, Any?>(
            "token" to "",
            "reason" to "app-focus",
            "locale" to "de",
            "theme" to "dark",
            "appFocusTime" to 0,
            "playerActive" to false,
            "playDuration" to 0,
            "devMode" to false,
            "hasAddon" to true,
            "castConnected" to false,
            "package" to "net.vypn.app",
            "version" to "1.4.1",
            "process" to "app",
            "firstAppStart" to System.currentTimeMillis() - 86400000,
            "lastAppStart" to System.currentTimeMillis(),
            "ipLocation" to null,
            "adblockEnabled" to true,
            "migrationApplied" to false,
            "migrationTargetInstalled" to false,
            "iap" to mapOf("supported" to false, "error" to "")
        )
        body["metadata"] = mapOf(
            "device" to mapOf("type" to "phone", "uniqueId" to UUID.randomUUID().toString().replace("-", "").substring(0, 16)),
            "os" to mapOf("name" to "android", "version" to "14", "abis" to listOf("arm64-v8a"), "host" to "android"),
            "app" to mapOf("platform" to "android"),
            "version" to mapOf("package" to "net.vypn.app", "binary" to "1.4.1", "js" to "1.4.1")
        )
        body["proxy"] = mapOf("supported" to listOf("ss"), "engine" to "Mu", "ssVersion" to "2022", "enabled" to false, "autoServer" to true, "id" to "")
        val headers = mapOf(
            "User-Agent" to "electron-fetch/1.0 electron (+https://github.com/arantes555/electron-fetch)",
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
            "Accept-Language" to "de"
        )
        return try {
            val response = app.post("https://www.vypn.net/api/app/ping", headers = headers, json = body)
            val json = parseJson<Map<String, Any?>>(response.body.string())
            json["addonSig"] as? String
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTsSignature(): String? {
        val vec = "9frjpxPjxSNilxJPCJ0XGYs6scej3dW/h/VWlnKUiLSG8IP7mfyDU7NirOlld+VtCKGj03XjetfliDMhIev7wcARo+YTU8KPFuVQP9E2DVXzY2BFo1NhE6qEmPfNDnm74eyl/7iFJ0EETm6XbYyz8IKBkAqPN/Spp3PZ2ulKg3QBSDxcVN4R5zRn7OsgLJ2CNTuWkd/h451lDCp+TtTuvnAEhcQckdsydFhTZCK5IiWrrTIC/d4qDXEd+GtOP4hPdoIuCaNzYfX3lLCwFENC6RZoTBYLrcKVVgbqyQZ7DnLqfLqvf3z0FVUWx9H21liGFpByzdnoxyFkue3NzrFtkRL37xkx9ITucepSYKzUVEfyBh+/3mtzKY26VIRkJFkpf8KVcCRNrTRQn47Wuq4gC7sSwT7eHCAydKSACcUMMdpPSvbvfOmIqeBNA83osX8FPFYUMZsjvYNEE3arbFiGsQlggBKgg1V3oN+5ni3Vjc5InHg/xv476LHDFnNdAJx448ph3DoAiJjr2g4ZTNynfSxdzA68qSuJY8UjyzgDjG0RIMv2h7DlQNjkAXv4k1BrPpfOiOqH67yIarNmkPIwrIV+W9TTV/yRyE1LEgOr4DK8uW2AUtHOPA2gn6P5sgFyi68w55MZBPepddfYTQ+E1N6R/hWnMYPt/i0xSUeMPekX47iucfpFBEv9Uh9zdGiEB+0P3LVMP+q+pbBU4o1NkKyY1V8wH1Wilr0a+q87kEnQ1LWYMMBhaP9yFseGSbYwdeLsX9uR1uPaN+u4woO2g8sw9Y5ze5XMgOVpFCZaut02I5k0U4WPyN5adQjG8sAzxsI3KsV04DEVymj224iqg2Lzz53Xz9yEy+7/85ILQpJ6llCyqpHLFyHq/kJxYPhDUF755WaHJEaFRPxUqbparNX+mCE9Xzy7Q/KTgAPiRS41FHXXv+7XSPp4cy9jli0BVnYf13Xsp28OGs/D8Nl3NgEn3/eUcMN80JRdsOrV62fnBVMBNf36+LbISdvsFAFr0xyuPGmlIETcFyxJkrGZnhHAxwzsvZ+Uwf8lffBfZFPRrNv+tgeeLpatVcHLHZGeTgWWml6tIHwWUqv2TVJeMkAEL5PPS4Gtbscau5HM+FEjtGS+KClfX1CNKvgYJl7mLDEf5ZYQv5kHaoQ6RcPaR6vUNn02zpq5/X3EPIgUKF0r/0ctmoT84B2J1BKfCbctdFY9br7JSJ6DvUxyde68jB+Il6qNcQwTFj4cNErk4x719Y42NoAnnQYC2/qfL/gAhJl8TKMvBt3Bno+va8ve8E0z8yEuMLUqe8OXLce6nCa+L5LYK1aBdb60BYbMeWk1qmG6Nk9OnYLhzDyrd9iHDd7X95OM6X5wiMVZRn5ebw4askTTc50xmrg4eic2U1w1JpSEjdH/u/hXrWKSMWAxaj34uQnMuWxPZEXoVxzGyuUbroXRfkhzpqmqqqOcypjsWPdq5BOUGL/Riwjm6yMI0x9kbO8+VoQ6RYfjAbxNriZ1cQ+AW1fqEgnRWXmjt4Z1M0ygUBi8w71bDML1YG6UHeC2cJ2CCCxSrfycKQhpSdI1QIuwd2eyIpd4LgwrMiY3xNWreAF+qobNxvE7ypKTISNrz0iYIhU0aKNlcGwYd0FXIRfKVBzSBe4MRK2pGLDNO6ytoHxvJweZ8h1XG8RWc4aB5gTnB7Tjiqym4b64lRdj1DPHJnzD4aqRixpXhzYzWVDN2kONCR5i2quYbnVFN4sSfLiKeOwKX4JdmzpYixNZXjLkG14seS6KR0lW8Itp5IMIWFpnNokjRH76RYRZAcx0jP0V5/GfNNTi5QsEU98en0SiXHQGXnROiHpRUDXTl8FmJORjwXc0AjrEMuQ2FDJDmAIlKUSLhjbIiKw3iaqp5TVyXuz0ZMYBhnqhcwqULqtFSuIKpaW8FgF8QJfP2frADf4kKZG1bQ99MrRrb2A="
        return try {
            val headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
            val response = app.post("https://www.vavoo.tv/api/box/ping2", headers = headers, data = mapOf("vec" to vec))
            val json = parseJson<Map<String, Any?>>(response.body.string())
            val resp = json["response"] as? Map<String, Any?>
            resp?.get("signed") as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun buildTsFallbackUrl(playUrl: String, tsSig: String): String? {
        if (!playUrl.contains("vavoo-iptv")) return null
        val base = playUrl
            .replace("vavoo-iptv", "live2", ignoreCase = true)
            .replace(Regex("/index\\.m3u8(\\?.*)?$"), "")
            .trimEnd('/')
        return "$base.ts?n=1&b=5&vavoo_auth=${URLEncoder.encode(tsSig, "UTF-8")}"
    }

    private suspend fun getChannels(): List<Channel> {
        val enabledCountries = countries.filter { it.value }.keys.toList()
        val allChannels = mutableListOf<Channel>()
        val reqHeaders = mutableMapOf("Content-Type" to "application/json")
        if (isVavoo) {
            reqHeaders["User-Agent"] = "okhttp/4.11.0"
            val sig = getVypnSignature()
            if (sig != null) reqHeaders["mediahubmx-signature"] = sig
        }
        val endpoint = if (isVavoo) "mediahubmx-catalog.json" else "mediaurl-catalog.json"

        for (country in enabledCountries) {
            var cursor: Long? = if (isVavoo) 0L else null
            do {
                val body: Map<String, Any?> = if (isVavoo) {
                    mapOf(
                        "language" to "de",
                        "region" to "AT",
                        "catalogId" to "iptv",
                        "id" to "iptv",
                        "adult" to false,
                        "search" to "",
                        "sort" to "trending",
                        "filter" to mapOf("group" to country),
                        "cursor" to cursor,
                        "clientVersion" to "3.1.0"
                    )
                } else {
                    mapOf(
                        "catalogId" to "iptv",
                        "search" to "",
                        "sort" to "trending",
                        "filter" to mapOf("group" to country),
                        "cursor" to cursor
                    )
                }
                val response = app.post("$mainUrl/$endpoint", headers = reqHeaders, json = body)
                val catalog = parseJson<CatalogResponse>(response.body.string())
                allChannels.addAll(catalog.items.map { item ->
                    Channel(
                        id = item.ids.id,
                        name = item.name,
                        url = item.url,
                        group = item.group
                    )
                })
                cursor = catalog.nextCursor
            } while (cursor != null)
        }

        return allChannels
    }

    companion object {
        var channels = emptyList<Channel>()

        @Suppress("ConstPropertyName")
        const val posterUrl =
            "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/Huhu/tv.png"
    }

    fun Channel.toSearchResponse(): LiveSearchResponse {
        return newLiveSearchResponse(
            name,
            this.toJson(),
            TvType.Live
        ) {
            posterUrl = Huhu.posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (channels.isEmpty()) {
            channels = getChannels()
        }
        val sections =
            channels.groupBy { it.group ?: "Unknown" }.map {
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
        val resolvedUrl = if (isVavoo) {
            try {
                val sig = getVypnSignature()
                if (sig != null) {
                    val reqHeaders = mapOf(
                        "Content-Type" to "application/json",
                        "User-Agent" to "MediaHubMX/2",
                        "mediahubmx-signature" to sig
                    )
                    val resolveBody = mapOf("language" to "de", "region" to "AT", "url" to channel.url, "clientVersion" to "3.1.0")
                    val response = app.post("$mainUrl/mediahubmx-resolve.json", headers = reqHeaders, json = resolveBody)
                    parseJson<List<ResolveResponse>>(response.body.string()).first().url
                } else null
            } catch (e: Exception) {
                null
            }
        } else {
            try {
                val resolveBody = mapOf("url" to channel.url)
                val response = app.post("$mainUrl/mediaurl-resolve.json", headers = mapOf("Content-Type" to "application/json"), json = resolveBody)
                parseJson<List<ResolveResponse>>(response.body.string()).first().url
            } catch (e: Exception) {
                null
            }
        }

        return newLiveStreamLoadResponse(
            channel.name,
            url,
            resolvedUrl ?: ""
        ) {
            posterUrl = Companion.posterUrl
            tags = listOf(channel.group ?: "")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channel = parseJson<Channel>(data)

        if (isVavoo) {
            // Try signed m3u8 resolve
            try {
                val sig = getVypnSignature()
                if (sig != null) {
                    val reqHeaders = mapOf(
                        "Content-Type" to "application/json",
                        "User-Agent" to "MediaHubMX/2",
                        "mediahubmx-signature" to sig
                    )
                    val resolveBody = mapOf("language" to "de", "region" to "AT", "url" to channel.url, "clientVersion" to "3.1.0")
                    val response = app.post("$mainUrl/mediahubmx-resolve.json", headers = reqHeaders, json = resolveBody)
                    val streamUrl = parseJson<List<ResolveResponse>>(response.body.string()).first().url
                    callback(
                        newExtractorLink(
                            this.name,
                            this.name,
                            streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                "Referer" to mainUrl
                            )
                        }
                    )
                }
            } catch (_: Exception) { }

            // TS fallback
            try {
                val tsSig = getTsSignature()
                if (tsSig != null) {
                    val tsUrl = buildTsFallbackUrl(channel.url, tsSig)
                    if (tsUrl != null) {
                        callback(
                            newExtractorLink(
                                this.name,
                                "${this.name} (TS)",
                                tsUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://vavoo.tv/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "User-Agent" to "VAVOO/2.6",
                                    "Referer" to "https://vavoo.tv/"
                                )
                            }
                        )
                    }
                }
            } catch (_: Exception) { }
        } else {
            // Non-vavoo domains: mediaurl-resolve.json
            try {
                val resolveBody = mapOf("url" to channel.url)
                val response = app.post("$mainUrl/mediaurl-resolve.json", headers = mapOf("Content-Type" to "application/json"), json = resolveBody)
                val streamUrl = parseJson<List<ResolveResponse>>(response.body.string()).first().url
                callback(
                    newExtractorLink(
                        this.name,
                        this.name,
                        streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Referer" to mainUrl
                        )
                    }
                )
            } catch (_: Exception) { }
        }

        return true
    }

    // --- API response models ---

    data class CatalogItem(
        val type: String,
        val ids: Ids,
        val url: String,
        val name: String,
        @JsonProperty("group") val group: String?
    )

    data class Ids(val id: String)

    data class CatalogResponse(
        val items: List<CatalogItem>,
        val nextCursor: Long?,
        val features: Features?
    )

    data class Features(val filter: List<Filter>)

    data class Filter(
        val id: String,
        val name: String,
        val values: List<String>
    )

    data class ResolveResponse(
        val id: String,
        val name: String,
        val url: String
    )

    data class Channel(
        val id: String,
        val name: String,
        val url: String,
        @JsonProperty("group") val group: String?
    )
}
