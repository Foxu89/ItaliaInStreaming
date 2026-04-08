package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Headers
import java.util.regex.Pattern

class MixDropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "mixdrop.top"
    override val requiresReferer = false

    companion object {
        private const val TAG = "MixDropExtractor"
        
        // Headers completi per simulare un browser reale
        private fun getVideoHeaders(): Map<String, String> = mapOf(
            "Referer" to "https://m1xdrop.net/",
            "Origin" to "https://m1xdrop.net",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "Getting video from: $url")
        
        try {
            // 1. Ottieni la pagina HTML con headers appropriati
            val pageHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
            )
            
            val pageDoc = app.get(url, headers = pageHeaders).document
            val html = pageDoc.html()
            
            // 2. Deoffusca lo script e ottieni l'URL del video
            val videoUrl = deobfuscateAndGetVideoUrl(html)
            
            if (videoUrl != null) {
                Log.d(TAG, "Final video URL: $videoUrl")
                
                // 3. Invia il link con gli headers corretti
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "MixDrop",
                        url = videoUrl,
                        type = ExtractorLinkType.MP4
                    ) {
                        this.headers = getVideoHeaders()
                        this.referer = "https://m1xdrop.net/"
                    }
                )
            } else {
                Log.e(TAG, "Failed to extract video URL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun deobfuscateAndGetVideoUrl(html: String): String? {
        try {
            // Pattern per catturare l'eval
            val evalPattern = Pattern.compile(
                """\}\('(.*?)',\d+,\d+,'(.*?)'\.split\('\|'\)""",
                Pattern.DOTALL
            )
            val matcher = evalPattern.matcher(html)
            
            if (matcher.find()) {
                val payload = matcher.group(1)
                val wordsStr = matcher.group(2)
                val words = wordsStr.split("|")
                
                Log.d(TAG, "Words count: ${words.size}")
                
                // Deoffusca il payload sostituendo i numeri con le parole
                val resolved = StringBuilder()
                var i = 0
                while (i < payload.length) {
                    if (payload[i].isDigit()) {
                        var numStr = ""
                        while (i < payload.length && payload[i].isDigit()) {
                            numStr += payload[i]
                            i++
                        }
                        val idx = numStr.toIntOrNull()
                        if (idx != null && idx < words.size) {
                            resolved.append(words[idx])
                        } else {
                            resolved.append(numStr)
                        }
                    } else {
                        resolved.append(payload[i])
                        i++
                    }
                }
                
                val unpacked = resolved.toString()
                Log.d(TAG, "Unpacked: $unpacked")
                
                // Estrai i parametri con regex
                val vserver = Regex("""vserver\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                val vfile = Regex("""vfile\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                val tokenS = Regex("""s=([^&"]+)""").find(unpacked)?.groupValues?.get(1)
                val tokenE = Regex("""e=([^&"]+)""").find(unpacked)?.groupValues?.get(1)
                val tokenT = Regex("""_t=([^&"]+)""").find(unpacked)?.groupValues?.get(1)
                
                Log.d(TAG, "vserver=$vserver, vfile=$vfile, s=$tokenS, e=$tokenE, _t=$tokenT")
                
                if (!vserver.isNullOrEmpty() && !vfile.isNullOrEmpty() && 
                    !tokenS.isNullOrEmpty() && !tokenE.isNullOrEmpty() && !tokenT.isNullOrEmpty()) {
                    
                    return "https://${vserver}.mxcontent.net/v2/${vfile}.mp4?s=$tokenS&e=$tokenE&_t=$tokenT"
                }
            } else {
                Log.d(TAG, "Eval pattern not found, trying alternative method...")
                
                // Metodo alternativo: cerca direttamente i valori
                val refMatch = Regex("""MDCore\.ref\s*=\s*"([^"]+)";""").find(html)
                if (refMatch != null) {
                    val videoId = refMatch.groupValues[1]
                    Log.d(TAG, "Found video ID: $videoId")
                    
                    // Cerca i token nello script
                    val scriptMatch = Regex("""s=([^&]+)&e=([^&]+)&_t=([^&"]+)""").find(html)
                    if (scriptMatch != null) {
                        val tokenS = scriptMatch.groupValues[1]
                        val tokenE = scriptMatch.groupValues[2]
                        val tokenT = scriptMatch.groupValues[3]
                        val serverMatch = Regex("""(?:https?:)?//([^.]+)\.mxcontent\.net""").find(html)
                        val vserver = serverMatch?.groupValues?.get(1) ?: "usx2f826m"
                        
                        return "https://${vserver}.mxcontent.net/v2/${videoId}.mp4?s=$tokenS&e=$tokenE&_t=$tokenT"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deobfuscation error: ${e.message}")
        }
        return null
    }
}
