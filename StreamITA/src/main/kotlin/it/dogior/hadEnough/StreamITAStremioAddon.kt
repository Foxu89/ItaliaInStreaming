package it.dogior.hadEnough

import android.content.SharedPreferences
import androidx.core.content.edit
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

enum class StreamITAAddonType { HTTPS, TORRENT, DEBRID, SUBTITLE }

data class StreamITAStremioAddon(
    val id: Long,
    val name: String,
    val url: String,
    var type: StreamITAAddonType = StreamITAAddonType.HTTPS
)

private fun String.fixSourceUrl(): String {
    return this.replace("/manifest.json", "").replace("stremio://", "https://")
}

private fun stremioAddonKey(name: String): String {
    val key = name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "addon" }
    return "stremio_$key"
}

object StreamITAStremioAddonSettings {
    private const val STREMIO_ADDONS_KEY = "streamita_stremio_addons"
    private const val STREMIO_DISABLED_KEY = "streamita_stremio_disabled"

    fun getStremioAddons(sharedPref: SharedPreferences?): List<StreamITAStremioAddon> {
        val json = sharedPref?.getString(STREMIO_ADDONS_KEY, null) ?: return emptyList()
        val list = mutableListOf<StreamITAStremioAddon>()
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val link = obj.optString("url", "").trim()
                if (link.isEmpty()) continue
                list.add(
                    StreamITAStremioAddon(
                        id = obj.optLong("id", System.currentTimeMillis()),
                        name = obj.optString("name", link).ifBlank { link },
                        url = link.fixSourceUrl().trimEnd('/'),
                        type = StreamITAAddonType.values().firstOrNull {
                            it.name.equals(obj.optString("type", "HTTPS"), ignoreCase = true)
                        } ?: StreamITAAddonType.HTTPS
                    )
                )
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    fun saveStremioAddons(sharedPref: SharedPreferences?, addons: List<StreamITAStremioAddon>) {
        val arr = JSONArray()
        for (item in addons) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("url", item.url)
                put("type", item.type.name)
            }
            arr.put(obj)
        }
        sharedPref?.edit { putString(STREMIO_ADDONS_KEY, arr.toString()) }
    }

    fun isEnabled(sharedPref: SharedPreferences?, name: String): Boolean {
        val disabled = sharedPref?.getStringSet(STREMIO_DISABLED_KEY, emptySet()) ?: emptySet()
        return stremioAddonKey(name) !in disabled
    }

    fun setEnabled(sharedPref: SharedPreferences?, name: String, enabled: Boolean) {
        val key = stremioAddonKey(name)
        val disabled = sharedPref?.getStringSet(STREMIO_DISABLED_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) disabled.remove(key) else disabled.add(key)
        sharedPref?.edit { putStringSet(STREMIO_DISABLED_KEY, disabled) }
    }

    fun getDynamicStremioMap(
        sharedPref: SharedPreferences?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Map<String, suspend () -> Unit> {
        return getStremioAddons(sharedPref)
            .filter { isEnabled(sharedPref, it.name) }
            .associate { addon ->
            val key = stremioAddonKey(addon.name)
            key to suspend {
                when (addon.type) {
                    StreamITAAddonType.SUBTITLE ->
                        invokeStremioSubtitles(addon.name, addon.url, imdbId, season, episode, subtitleCallback)
                    StreamITAAddonType.TORRENT ->
                        invokeStremioTorrents(addon.name, addon.url, imdbId, season, episode, callback)
                    StreamITAAddonType.HTTPS, StreamITAAddonType.DEBRID ->
                        invokeStremioStreams(addon.name, addon.url, imdbId, season, episode, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun invokeStremioStreams(
        sourceName: String,
        addonUrl: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = imdbId ?: return
        val path = if (season == null) "/stream/movie/$id.json"
        else "/stream/series/$id:$season:$episode.json"
        val url = "$addonUrl$path"

        try {
            val response = app.get(url, timeout = 15000)
            if (!response.isSuccessful) return
            val text = response.text
            val json = JSONObject(text)
            val streams = json.optJSONArray("streams") ?: return

            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                val streamUrl = stream.optString("url", "").trim()
                val externalUrl = stream.optString("externalUrl", "").trim()
                val ytId = stream.optString("ytId", "").trim()
                val title = stream.optString("title", sourceName).ifBlank { sourceName }
                val description = stream.optString("description", null)
                val name = stream.optString("name", null)

                val displayName = buildString {
                    append(name ?: sourceName)
                    if (!description.isNullOrBlank()) append(" - $description")
                    if (!title.isNullOrBlank() && title != name) append(" | $title")
                }

                // Build headers from behaviorHints
                val headers = mutableMapOf<String, String>()
                val hints = stream.optJSONObject("behaviorHints")
                hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { proxy ->
                    for (pk in proxy.keys()) headers[pk] = proxy.getString(pk)
                }
                hints?.optJSONObject("headers")?.let { h ->
                    for (hk in h.keys()) headers[hk] = h.getString(hk)
                }

                // Handle subtitles embedded in stream
                val subs = stream.optJSONArray("subtitles")
                if (subs != null) {
                    for (j in 0 until subs.length()) {
                        val sub = subs.getJSONObject(j)
                        val subUrl = sub.optString("url", "")
                        if (subUrl.isNotBlank()) {
                            val lang = sub.optString("lang", sub.optString("lang_code", "Unknown"))
                            subtitleCallback(SubtitleFile(lang, subUrl))
                        }
                    }
                }

                if (externalUrl.isNotBlank()) {
                    com.lagradost.cloudstream3.utils.loadExtractor(externalUrl, sourceName, subtitleCallback, callback)
                } else if (ytId.isNotBlank()) {
                    com.lagradost.cloudstream3.utils.loadExtractor("https://www.youtube.com/watch?v=$ytId", sourceName, subtitleCallback, callback)
                } else if (streamUrl.isNotBlank()) {
                    val quality = getIndexQuality(displayName)
                    val extType = when {
                        streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                        streamUrl.contains(".mpd") -> ExtractorLinkType.DASH
                        else -> INFER_TYPE
                    }
                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = displayName,
                            url = streamUrl,
                            type = extType,
                        ) {
                            this.quality = quality
                            this.headers = headers
                        }
                    )
                }
            }
        } catch (_: Exception) { }
    }

    private suspend fun invokeStremioTorrents(
        sourceName: String,
        addonUrl: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = imdbId ?: return
        val path = if (season == null) "/stream/movie/$id.json"
        else "/stream/series/$id:$season:$episode.json"
        val url = "$addonUrl$path"

        try {
            val response = app.get(url, timeout = 15000)
            if (!response.isSuccessful) return
            val text = response.text
            val json = JSONObject(text)
            val streams = json.optJSONArray("streams") ?: return

            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                val infoHash = stream.optString("infoHash", "").trim()
                if (infoHash.isBlank()) continue

                val title = stream.optString("title", sourceName).ifBlank { sourceName }
                val name = stream.optString("name", null)
                val fileIdx = stream.optInt("fileIdx", -1)
                val sources = mutableListOf<String>()
                val srcArr = stream.optJSONArray("sources")
                if (srcArr != null) {
                    for (j in 0 until srcArr.length()) {
                        sources.add(srcArr.getString(j))
                    }
                }

                val magnet = buildMagnetString(infoHash, title, fileIdx, sources)
                if (magnet.isBlank()) continue

                val quality = getIndexQuality(title)
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = name ?: sourceName,
                        url = magnet,
                        type = ExtractorLinkType.MAGNET,
                    ) {
                        this.quality = quality
                    }
                )
            }
        } catch (_: Exception) { }
    }

    private suspend fun invokeStremioSubtitles(
        sourceName: String,
        addonUrl: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val id = imdbId ?: return
        val path = if (season == null) "/subtitles/movie/$id.json"
        else "/subtitles/series/$id:$season:$episode.json"
        val url = "$addonUrl$path"

        try {
            val response = app.get(url, timeout = 15000)
            if (!response.isSuccessful) return
            val text = response.text
            val json = JSONObject(text)
            val subtitles = json.optJSONArray("subtitles") ?: return

            for (i in 0 until subtitles.length()) {
                val sub = subtitles.getJSONObject(i)
                val subUrl = sub.optString("url", "").trim()
                if (subUrl.isBlank()) continue
                val lang = sub.optString("lang", sub.optString("lang_code", "Unknown"))
                subtitleCallback(SubtitleFile(lang, subUrl))
            }
        } catch (_: Exception) { }
    }

    private fun buildMagnetString(
        infoHash: String,
        title: String,
        fileIdx: Int,
        sources: List<String>
    ): String {
        return buildString {
            append("magnet:?xt=urn:btih:").append(infoHash)
            append("&dn=").append(URLEncoder.encode(title, "UTF-8"))
            if (fileIdx >= 0) append("&so=").append(fileIdx)
            for (src in sources) {
                if (src.startsWith("tracker:")) {
                    val tracker = src.removePrefix("tracker:")
                    append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
                }
            }
        }
    }

    private fun getIndexQuality(name: String?): Int {
        if (name == null) return -1
        val upper = name.uppercase()
        return when {
            "8K" in upper || "4320" in upper -> 4320
            "4K" in upper || "2160" in upper -> 2160
            "1080" in upper -> 1080
            "720" in upper -> 720
            "480" in upper -> 480
            "360" in upper -> 360
            else -> -1
        }
    }
}
