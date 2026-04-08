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
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "Getting video from: $url")
        
        try {
            // Headers per la pagina HTML
            val pageHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
            )
            
            val pageDoc = app.get(url, headers = pageHeaders).document
            val html = pageDoc.html()
            
            // Deoffusca e ottieni l'URL del video
            val rawUrl = deobfuscateAndGetVideoUrl(html)
            
            if (rawUrl != null) {
                // PULISCI L'URL: rimuovi spazi e doppie estensioni
                var videoUrl = rawUrl
                    .replace(" ", "")                           // Rimuovi tutti gli spazi
                    .replace(".mp4.mp4", ".mp4")                // Fix doppia estensione
                    .replace("/v2/", "/v2/")                    // Assicura che non ci siano spazi
                    .trim()
                
                Log.d(TAG, "Cleaned video URL: $videoUrl")
                
                // Headers OBBLIGATORI per il video
                val videoHeaders = mapOf(
                    "Referer" to "https://m1xdrop.net/",
                    "Origin" to "https://m1xdrop.net",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "MixDrop",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.headers = videoHeaders
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
            // Pattern per l'eval offuscato
            val evalPattern = Pattern.compile(
                """\}\('(.*?)',\d+,\d+,'(.*?)'\.split\('\|'\)""",
                Pattern.DOTALL
            )
            val matcher = evalPattern.matcher(html)
            
            if (matcher.find()) {
                val payload = matcher.group(1)
                val wordsStr = matcher.group(2)
                val words = wordsStr.split("|")
                
                // Deoffusca
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
                
                // Estrai parametri
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deobfuscation error: ${e.message}")
        }
        return null
    }
}
