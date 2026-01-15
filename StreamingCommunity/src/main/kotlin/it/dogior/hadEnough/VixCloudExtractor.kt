package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class VixCloudExtractor : ExtractorApi() {
    override val mainUrl = "vixcloud.co"
    override val name = "VixCloud"
    override val requiresReferer = false
    val TAG = "VixCloudExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "REFERER: $referer  URL: $url")
        val playlistUrl = getPlaylistLink(url, referer)
        Log.w(TAG, "FINAL URL: $playlistUrl")

        // ⚡ HEADERS COMPLETI PER IL DOWNLOAD
        val headers = mutableMapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Cache-Control" to "no-cache",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
            "Referer" to referer ?: "https://vixcloud.co/",  // ← IMPORTANTE!
            "Origin" to "https://vixcloud.co"                 // ← IMPORTANTE!
        )

        callback.invoke(
            newExtractorLink(
                source = "VixCloud",
                name = "Streaming Community - VixCloud",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8  // ← PROVA PRIMA M3U8
            ) {
                this.headers = headers
                this.referer = referer ?: ""  // ← AGGIUNGI REFERER
                this.quality = Qualities.P720.value  // ← AGGIUNGI QUALITY!
            }
        )
    }

    private suspend fun getPlaylistLink(url: String, referer: String?): String {
        Log.d(TAG, "Item url: $url")

        val script = getScript(url, referer)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val masterPlaylistParams = masterPlaylist.getJSONObject("params")
        val token = masterPlaylistParams.getString("token")
        val expires = masterPlaylistParams.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")

        var masterPlaylistUrl: String
        val params = "token=${token}&expires=${expires}"
        masterPlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&$params"
        } else {
            "${playlistUrl}?$params"
        }
        Log.d(TAG, "masterPlaylistUrl: $masterPlaylistUrl")

        if (script.getBoolean("canPlayFHD")) {
            masterPlaylistUrl += "&h=1"
        }

        Log.d(TAG, "Master Playlist URL: $masterPlaylistUrl")
        return masterPlaylistUrl
    }

    private suspend fun getScript(url: String, referer: String?): JSONObject {
        Log.d(TAG, "Item url: $url")

        val headers = mutableMapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Cache-Control" to "no-cache",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
            "Referer" to referer ?: "https://vixcloud.co/"
        )
        
        val iframe = app.get(url, headers = headers, interceptor = CloudflareKiller()).document
        Log.d(TAG, iframe.toString())

        val scripts = iframe.select("script")
        val script =
            scripts.find { it.data().contains("masterPlaylist") }!!.data().replace("\n", "\t")

        val scriptJson = getSanitisedScript(script)
        Log.d(TAG, "Script Json: $scriptJson")
        return JSONObject(scriptJson)
    }

    private fun getSanitisedScript(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1)

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            val cleaned = value
                .replace(";", "")
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }
        val finalObject =
            "{\n${jsonObjects.joinToString(",\n")}\n}"
                .replace("'", "\"")

        return finalObject
    }
}
