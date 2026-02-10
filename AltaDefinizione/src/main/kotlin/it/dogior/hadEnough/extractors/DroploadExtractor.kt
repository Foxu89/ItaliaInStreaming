package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

class DroploadExtractor : ExtractorApi() {
    override var name = "Dropload"
    override var mainUrl = "https://dropload.pro"  // <-- AGGIORNATO
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i("DroploadExtractor", "🔎 Trying to extract: $url")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Referer" to referer ?: "https://altadefinizionez.sbs/"
        )

        try {
            val response = app.get(url, headers = headers)
            val body = response.text
            Log.i("DroploadExtractor", "✅ Page loaded, size: ${body.length}")
            
            // Cerca diversi pattern di script
            val scriptPatterns = listOf(
                Regex("""eval\(function\(p,a,c,k,e,(?:r|d).*?\n"""),
                Regex("""<script[^>]*>(eval\([^<]+)</script>"""),
                Regex("""<script[^>]*>(\s*eval\s*\([^<]+)</script>""")
            )
            
            var unpackedContent = ""
            var foundScript = false
            
            for (pattern in scriptPatterns) {
                val match = pattern.find(body)
                if (match != null) {
                    val script = match.groupValues[1]
                    Log.i("DroploadExtractor", "✅ Found script with pattern, size: ${script.length}")
                    
                    try {
                        unpackedContent = app.getAndUnpack(script)
                        foundScript = true
                        Log.i("DroploadExtractor", "✅ Script unpacked, size: ${unpackedContent.length}")
                        break
                    } catch (e: Exception) {
                        Log.e("DroploadExtractor", "❌ Failed to unpack script: ${e.message}")
                    }
                }
            }
            
            if (!foundScript) {
                // Prova a cercare direttamente nel body
                unpackedContent = body
            }
            
            // Cerca URL video
            val videoPatterns = listOf(
                Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)"""),
                Regex("""src\s*:\s*"([^"]+\.m3u8[^"]*)"""),
                Regex("""hls\s*:\s*"([^"]+\.m3u8[^"]*)"""),
                Regex("""sources\s*:\s*\[\s*\{\s*src\s*:\s*"([^"]+\.m3u8[^"]*)"""),
                Regex("""(https?://[^"\s]+\.m3u8[^\s"]*)""")
            )
            
            for (pattern in videoPatterns) {
                val match = pattern.find(unpackedContent)
                if (match != null) {
                    var videoUrl = match.groupValues[1]
                    Log.i("DroploadExtractor", "✅ Found video URL: $videoUrl")
                    
                    // Decodifica URL
                    videoUrl = if (videoUrl.contains("%")) {
                        URLDecoder.decode(videoUrl, "UTF-8")
                    } else {
                        videoUrl
                    }
                    
                    // Assicurati che sia un URL completo
                    if (!videoUrl.startsWith("http")) {
                        videoUrl = if (videoUrl.startsWith("//")) {
                            "https:$videoUrl"
                        } else {
                            "$mainUrl$videoUrl"
                        }
                    }
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Dropload",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ){
                            this.referer = referer ?: url
                            quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            }
            
            Log.e("DroploadExtractor", "❌ No video URL found after searching patterns")
            
        } catch (e: Exception) {
            Log.e("DroploadExtractor", "❌ Error during extraction: ${e.message}")
            e.printStackTrace()
        }
    }
}
