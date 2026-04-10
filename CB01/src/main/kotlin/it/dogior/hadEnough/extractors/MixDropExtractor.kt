package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.regex.Pattern

class MixDropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "mixdrop.top"
    override val requiresReferer = false

    companion object {
        private const val TAG = "MixDropExtractor"
        // Usiamo uno User-Agent fisso e moderno per evitare discrepanze
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val videoId = url.substringAfterLast("/")
            // Usiamo il dominio dell'URL passato per essere dinamici (.top, .co, .ch, ecc)
            val domain = url.substringAfter("://").substringBefore("/")
            val pageUrl = "https://$domain/e/$videoId"
            
            val pageHeaders = mapOf(
                "User-Agent" to UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9"
            )
            
            val pageResponse = app.get(pageUrl, headers = pageHeaders)
            val html = pageResponse.body.string()
            
            val videoUrl = extractVideoUrlFromHtml(html)
            
            if (videoUrl != null) {
                Log.d(TAG, "Video URL extracted: $videoUrl")
                
                // Questi headers devono essere IDENTICI a quelli usati dal browser
                // Il segreto del 403 di MixDrop è spesso nel Referer della pagina /e/
                val videoHeaders = mutableMapOf(
                    "User-Agent" to UA,
                    "Accept" to "*/*",
                    "Accept-Language" to "it-IT,it;q=0.9",
                    "Range" to "bytes=0-",
                    "Referer" to pageUrl, // IL REFERER DEVE ESSERE IL LINK DEL PLAYER
                    "Origin" to "https://$domain",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "video",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site"
                )
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "MixDrop",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.headers = videoHeaders
                    }
                )
            } else {
                Log.e(TAG, "Failed to extract video URL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }
    
    private fun extractVideoUrlFromHtml(html: String): String? {
        try {
            // Ripristiniamo la tua logica originale che funzionava nell'estrazione
            val urlPattern = Regex("""(https?://[a-z0-9]+\.mxcontent\.net/v2/[a-f0-9]+\.mp4\?s=[^&]+&e=[^&]+&_t=[^&"]+)""")
            val urlMatch = urlPattern.find(html)
            if (urlMatch != null) {
                return urlMatch.groupValues[1].replace(Regex("[\\s\\n\\r]"), "")
            }
            
            // Logica fallback variabili MDCore
            val vserver = Regex("""vserver\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val vfile = Regex("""vfile\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val s = Regex("""s\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val e = Regex("""e\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val t = Regex("""_t\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            
            if (vserver != null && vfile != null && s != null) {
                val cleanFile = vfile.replace(".mp4", "")
                return "https://$vserver.mxcontent.net/v2/$cleanFile.mp4?s=$s&e=$e&_t=$t"
            }
            
            // Se arriviamo qui, proviamo il tuo unpacker originale
            val evalPattern = Pattern.compile("""\}\('(.*?)',\s*\d+,\s*\d+,\s*'(.*?)'\.split\('\|'\)""", Pattern.DOTALL)
            val matcher = evalPattern.matcher(html)
            if (matcher.find()) {
                val payload = matcher.group(1)
                val words = matcher.group(2).split("|")
                val unpacked = StringBuilder()
                var i = 0
                while (i < payload.length) {
                    if (payload[i].isLetterOrDigit()) {
                        var numStr = ""
                        while (i < payload.length && payload[i].isLetterOrDigit()) {
                            numStr += payload[i]
                            i++
                        }
                        val idx = numStr.toIntOrNull(36)
                        if (idx != null && idx < words.size && words[idx].isNotEmpty()) {
                            unpacked.append(words[idx])
                        } else {
                            unpacked.append(numStr)
                        }
                    } else {
                        unpacked.append(payload[i])
                        i++
                    }
                }
                
                val res = unpacked.toString()
                val vserverE = Regex("""vserver\s*=\s*"([^"]+)"""").find(res)?.groupValues?.get(1)
                val vfileE = Regex("""vfile\s*=\s*"([^"]+)"""").find(res)?.groupValues?.get(1)
                val sE = Regex("""s\s*=\s*"([^"]+)"""").find(res)?.groupValues?.get(1)
                val eE = Regex("""e\s*=\s*"([^"]+)"""").find(res)?.groupValues?.get(1)
                val tE = Regex("""_t\s*=\s*"([^"]+)"""").find(res)?.groupValues?.get(1)
                
                if (vserverE != null && vfileE != null) {
                    return "https://$vserverE.mxcontent.net/v2/${vfileE.replace(".mp4", "")}.mp4?s=$sE&e=$eE&_t=$tE"
                }
            }
        } catch (e: Exception) { }
        return null
    }
}
                               
