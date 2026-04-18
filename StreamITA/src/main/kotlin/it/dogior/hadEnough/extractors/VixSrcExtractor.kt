package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "https://vixsrc.to"
    override val name = "VixCloud"
    override val requiresReferer = true
    val TAG = "VixSrcExtractor"
    private var referer: String? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        this.referer = referer ?: mainUrl
        Log.d(TAG, "URL: $url")
        
        val playlistUrl = getPlaylistLink(url)
        Log.i(TAG, "FINAL M3U8: $playlistUrl")

        callback.invoke(
            newExtractorLink(
                source = "VixSrc",
                name = "Streaming Community - VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to mainUrl
                )
            }
        )
    }

    private suspend fun getPlaylistLink(url: String): String {
        val html = fetchHtml(url)
        val json = extractMasterPlaylist(html)
        
        val masterPlaylist = json.getJSONObject("masterPlaylist")
        val params = masterPlaylist.getJSONObject("params")
        val token = params.getString("token")
        val expires = params.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")
        
        var finalUrl = if (playlistUrl.contains("?b")) {
            playlistUrl.replace("?b:1", "?b=1") + "&token=$token&expires=$expires"
        } else {
            "$playlistUrl?token=$token&expires=$expires"
        }
        
        if (json.optBoolean("canPlayFHD", false)) {
            finalUrl += "&h=1"
        }
        
        return finalUrl
    }

    private suspend fun fetchHtml(url: String): String {
        val headers = mapOf(
            "Accept" to "*/*",
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0"
        )
        return app.get(url, headers = headers).text
    }

    private suspend fun extractMasterPlaylist(html: String): JSONObject {
        val pattern = Regex("""window\.masterPlaylist\s*=\s*(\{[^}]+\})""")
        val match = pattern.find(html)
        
        if (match != null) {
            var jsonStr = match.groupValues[1]
            jsonStr = jsonStr.replace(Regex(""",(\s*[}\]])"""), "$1")
            jsonStr = jsonStr.replace("'", "\"")
            
            val json = JSONObject(jsonStr)
            val result = JSONObject()
            result.put("masterPlaylist", json)
            result.put("canPlayFHD", html.contains("window.canPlayFHD = true"))
            return result
        }
        
        val streamsPattern = Regex("""window\.streams\s*=\s*\[(.*?)\]""")
        val streamsMatch = streamsPattern.find(html)
        
        if (streamsMatch != null) {
            val urlPattern = Regex(""""url":"([^"]+)"""")
            val urlMatch = urlPattern.find(streamsMatch.groupValues[1])
            
            if (urlMatch != null) {
                val streamUrl = urlMatch.groupValues[1].replace("\\/", "/")
                val embedHtml = fetchHtml(streamUrl)
                return extractMasterPlaylist(embedHtml)
            }
        }
        
        throw Exception("masterPlaylist not found")
    }
}
