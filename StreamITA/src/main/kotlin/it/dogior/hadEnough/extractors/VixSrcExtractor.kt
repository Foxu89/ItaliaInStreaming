package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "vixsrc.to"
    override val name = "VixCloud"
    override val requiresReferer = false
    val TAG = "VixSrcExtractor"
    private var referer: String? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        this.referer = referer
        Log.d(TAG, "🔗 REFERER: $referer  URL: $url")
        val playlistUrl = getPlaylistLink(url)
        Log.w(TAG, "🎬 FINAL URL: $playlistUrl")

        callback.invoke(
            newExtractorLink(
                source = "VixSrc",
                name = "Streaming Community - VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer!!
            }
        )
    }

    private suspend fun getPlaylistLink(url: String): String {
        Log.d(TAG, "📥 Item url: $url")

        val script = getScript(url)
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
        Log.d(TAG, "🔧 masterPlaylistUrl: $masterPlaylistUrl")

        if (script.optBoolean("canPlayFHD", false)) {
            masterPlaylistUrl += "&h=1"
            Log.d(TAG, "📺 FHD enabled")
        }

        Log.d(TAG, "✅ Master Playlist URL: $masterPlaylistUrl")
        return masterPlaylistUrl
    }

    private suspend fun getScript(url: String): JSONObject {
        Log.d(TAG, "🌐 Fetching: $url")
        val headers = mutableMapOf(
            "Accept" to "*/*",
            "Alt-Used" to url.toHttpUrl().host,
            "Connection" to "keep-alive",
            "Host" to url.toHttpUrl().host,
            "Referer" to referer!!,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0",
        )

        val response = app.get(url, headers = headers)
        val html = response.text
        Log.d(TAG, "📄 Response length: ${html.length}")

        // ========== METODO 1: Cerca window.masterPlaylist DIRETTAMENTE ==========
        Log.d(TAG, "🔍 Trying DIRECT method...")
        val directPattern = Regex("""window\.masterPlaylist\s*=\s*(\{[^}]+\})""")
        directPattern.find(html)?.let { match ->
            val jsonStr = match.groupValues[1]
            Log.d(TAG, "✅ DIRECT method SUCCESS!")
            Log.d(TAG, "📋 masterPlaylist: $jsonStr")
            
            val canPlayFHD = html.contains("window.canPlayFHD = true")
            Log.d(TAG, "📺 canPlayFHD: $canPlayFHD")
            
            val json = JSONObject()
            json.put("masterPlaylist", JSONObject(jsonStr))
            json.put("canPlayFHD", canPlayFHD)
            return json
        }
        Log.d(TAG, "❌ DIRECT method failed")

        // ========== METODO 2: Cerca negli script tag (vecchio formato) ==========
        Log.d(TAG, "🔍 Trying SCRIPT TAG method...")
        val document = response.document
        val scripts = document.select("script")
        Log.d(TAG, "📜 Found ${scripts.size} script tags")
        
        val script = scripts.find { it.data().contains("masterPlaylist") }?.data()?.replace("\n", "\t")
        
        if (script != null) {
            Log.d(TAG, "✅ SCRIPT TAG method SUCCESS!")
            val scriptJson = getSanitisedScript(script)
            Log.d(TAG, "📋 Script Json: $scriptJson")
            return JSONObject(scriptJson)
        }
        Log.d(TAG, "❌ SCRIPT TAG method failed")

        throw Exception("❌❌❌ masterPlaylist not found in page!")
    }

    private fun getSanitisedScript(script: String): String {
        Log.d(TAG, "🧹 Sanitising script...")
        
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1)

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()
        
        Log.d(TAG, "🔑 Found keys: $keys")

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

        Log.d(TAG, "✨ Sanitised complete")
        return finalObject
    }
}
