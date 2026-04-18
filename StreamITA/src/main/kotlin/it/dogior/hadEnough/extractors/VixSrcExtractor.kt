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
    override val name = "VixSrc"
    override val requiresReferer = true
    val TAG = "VixSrcExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🎬 START - URL: $url")
        Log.d(TAG, "========================================")
        
        try {
            val playlistUrl = getPlaylistLink(url)
            Log.i(TAG, "✅ SUCCESS - Final M3U8: $playlistUrl")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "VixSrc",
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
            Log.d(TAG, "✅ Callback invoked successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ FAILED: ${e.message}")
            e.printStackTrace()
        }
        Log.d(TAG, "========================================")
    }

    private suspend fun getPlaylistLink(url: String): String {
        Log.d(TAG, "📥 STEP 1: Fetching HTML from: $url")
        
        val html = fetchHtml(url)
        Log.d(TAG, "📄 HTML length: ${html.length} characters")
        
        Log.d(TAG, "🔍 STEP 2: Extracting masterPlaylist from HTML...")
        val json = extractMasterPlaylist(html)
        Log.d(TAG, "✅ masterPlaylist extracted successfully")
        
        Log.d(TAG, "📊 STEP 3: Parsing JSON data...")
        val masterPlaylist = json.getJSONObject("masterPlaylist")
        val params = masterPlaylist.getJSONObject("params")
        val token = params.getString("token")
        val expires = params.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")
        
        Log.d(TAG, "   🎯 Playlist URL: $playlistUrl")
        Log.d(TAG, "   🔑 Token: ${token.take(20)}...")
        Log.d(TAG, "   ⏰ Expires: $expires")
        
        Log.d(TAG, "🔧 STEP 4: Building final URL...")
        var finalUrl = if (playlistUrl.contains("?b")) {
            Log.d(TAG, "   📍 Detected '?b' format, replacing...")
            playlistUrl.replace("?b:1", "?b=1") + "&token=$token&expires=$expires"
        } else {
            Log.d(TAG, "   📍 Standard format")
            "$playlistUrl?token=$token&expires=$expires"
        }
        
        val canPlayFHD = json.optBoolean("canPlayFHD", false)
        if (canPlayFHD) {
            finalUrl += "&h=1"
            Log.d(TAG, "   📺 FHD enabled (+&h=1)")
        }
        
        Log.d(TAG, "🔗 Final URL: $finalUrl")
        return finalUrl
    }

    private suspend fun fetchHtml(url: String): String {
        Log.d(TAG, "🌐 Making HTTP GET request...")
        val headers = mapOf(
            "Accept" to "*/*",
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0"
        )
        
        val response = app.get(url, headers = headers)
        Log.d(TAG, "   📡 Response code: ${response.code}")
        Log.d(TAG, "   📏 Content length: ${response.text.length}")
        
        return response.text
    }

    private suspend fun extractMasterPlaylist(html: String): JSONObject {
        Log.d(TAG, "🔎 Searching for window.masterPlaylist...")
        val pattern = Regex("""window\.masterPlaylist\s*=\s*(\{[^}]+\})""")
        val match = pattern.find(html)
        
        if (match != null) {
            Log.d(TAG, "   ✅ Found window.masterPlaylist!")
            var jsonStr = match.groupValues[1]
            Log.d(TAG, "   📋 Raw JSON: ${jsonStr.take(100)}...")
            
            Log.d(TAG, "   🧹 Cleaning JSON...")
            jsonStr = jsonStr.replace(Regex(""",(\s*[}\]])"""), "$1")
            jsonStr = jsonStr.replace("'", "\"")
            Log.d(TAG, "   📋 Cleaned JSON: ${jsonStr.take(100)}...")
            
            val json = JSONObject(jsonStr)
            val result = JSONObject()
            result.put("masterPlaylist", json)
            
            val canPlayFHD = html.contains("window.canPlayFHD = true")
            result.put("canPlayFHD", canPlayFHD)
            Log.d(TAG, "   📺 canPlayFHD: $canPlayFHD")
            
            return result
        }
        
        Log.w(TAG, "   ⚠️ window.masterPlaylist NOT found!")
        Log.d(TAG, "   🔄 Trying fallback: window.streams...")
        
        val streamsPattern = Regex("""window\.streams\s*=\s*\[(.*?)\]""")
        val streamsMatch = streamsPattern.find(html)
        
        if (streamsMatch != null) {
            Log.d(TAG, "   ✅ Found window.streams!")
            val urlPattern = Regex(""""url":"([^"]+)"""")
            val urlMatch = urlPattern.find(streamsMatch.groupValues[1])
            
            if (urlMatch != null) {
                val streamUrl = urlMatch.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "   🔗 Stream URL: $streamUrl")
                Log.d(TAG, "   📥 Fetching embed page...")
                
                val embedHtml = fetchHtml(streamUrl)
                Log.d(TAG, "   🔄 Recursively extracting masterPlaylist...")
                return extractMasterPlaylist(embedHtml)
            }
            Log.w(TAG, "   ⚠️ URL not found in streams")
        } else {
            Log.w(TAG, "   ⚠️ window.streams NOT found!")
        }
        
        Log.e(TAG, "❌ All extraction methods failed!")
        Log.d(TAG, "📄 HTML preview: ${html.take(500)}")
        throw Exception("masterPlaylist not found")
    }
}
