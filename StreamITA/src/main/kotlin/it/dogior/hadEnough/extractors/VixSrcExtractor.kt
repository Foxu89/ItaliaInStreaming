package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        Log.d(TAG, "🎬 URL: $url")
        
        val playlistUrl = getPlaylistLink(url)
        Log.i(TAG, "✅ FINAL M3U8: $playlistUrl")

        callback.invoke(
            newExtractorLink(
                source = "VixSrc",
                name = "Streaming Community - VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to mainUrl
                )
            }
        )
    }

    private suspend fun getPlaylistLink(url: String): String {
        val script = getScript(url)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val params = masterPlaylist.getJSONObject("params")
        val token = params.getString("token")
        val expires = params.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")

        var finalUrl = if ("?b" in playlistUrl) {
            playlistUrl.replace("?b:1", "?b=1") + "&token=$token&expires=$expires"
        } else {
            "$playlistUrl?token=$token&expires=$expires"
        }

        if (script.optBoolean("canPlayFHD", false)) {
            finalUrl += "&h=1"
        }

        return finalUrl
    }

    private suspend fun getScript(url: String): JSONObject {
        Log.d(TAG, "📥 Fetching: $url")
        
        val host = url.toHttpUrl().host
        val headers = mapOf(
            "Accept" to "*/*",
            "Alt-Used" to host,
            "Connection" to "keep-alive",
            "Host" to host,
            "Referer" to referer!!,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
        )

        val response = app.get(url, headers = headers)
        val html = response.text

        // METODO 1: Cerca window.masterPlaylist DIRETTAMENTE (nuovo formato)
        val directPattern = Regex("""window\.masterPlaylist\s*=\s*(\{[^}]+\})""")
        directPattern.find(html)?.let { match ->
            val jsonStr = match.groupValues[1]
            Log.d(TAG, "✅ Found direct masterPlaylist")
            
            // Cerca anche canPlayFHD
            val canPlayFHD = html.contains("window.canPlayFHD = true")
            
            // Costruisci JSON completo
            val json = JSONObject()
            json.put("masterPlaylist", JSONObject(jsonStr))
            json.put("canPlayFHD", canPlayFHD)
            return json
        }

        // METODO 2: Estrai da window.streams e window.canPlayFHD
        val streamsPattern = Regex("""window\.streams\s*=\s*\[(.*?)\]""")
        val urlPattern = Regex(""""url":"([^"]+)"""")
        val tokenPattern = Regex("""token['"]?\s*:\s*['"]([^'"]+)['"]""")
        val expiresPattern = Regex("""expires['"]?\s*:\s*['"]([^'"]+)['"]""")
        
        val streamsMatch = streamsPattern.find(html)
        if (streamsMatch != null) {
            val streamsContent = streamsMatch.groupValues[1]
            val playlistUrl = urlPattern.find(streamsContent)?.groupValues?.get(1)
            val token = tokenPattern.find(html)?.groupValues?.get(1)
            val expires = expiresPattern.find(html)?.groupValues?.get(1)
            val canPlayFHD = html.contains("window.canPlayFHD = true")
            
            if (playlistUrl != null && token != null && expires != null) {
                Log.d(TAG, "✅ Built from window.streams")
                val json = JSONObject()
                json.put("masterPlaylist", JSONObject().apply {
                    put("url", playlistUrl.replace("\\", ""))
                    put("params", JSONObject().apply {
                        put("token", token)
                        put("expires", expires)
                    })
                })
                json.put("canPlayFHD", canPlayFHD)
                return json
            }
        }

        // METODO 3: Fallback al vecchio metodo (script tag con window.xxx = {...})
        val document = response.document
        val script = document.select("script")
            .find { it.data().contains("masterPlaylist") }
            ?.data()?.replace("\n", "\t")
        
        if (script != null) {
            Log.d(TAG, "✅ Found in script tag (old format)")
            return JSONObject(getSanitisedScript(script))
        }

        throw Exception("❌ masterPlaylist not found in page")
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
        
        return "{\n${jsonObjects.joinToString(",\n")}\n}".replace("'", "\"")
    }
}
