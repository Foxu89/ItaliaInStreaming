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
            
            // Estrai l'URL del video
            val videoUrl = extractVideoUrlFromHtml(html)
            
            if (videoUrl != null) {
                Log.d(TAG, "Final video URL: $videoUrl")
                
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
    
    private fun extractVideoUrlFromHtml(html: String): String? {
        try {
            // Metodo 1: Cerca lo script MDCore
            val mdCorePattern = Pattern.compile(
                """MDCore\.ref\s*=\s*"([^"]+)";[^>]*?vserver\s*=\s*"([^"]+)";[^>]*?vfile\s*=\s*"([^"]+)";""",
                Pattern.DOTALL
            )
            val mdMatcher = mdCorePattern.matcher(html)
            
            if (mdMatcher.find()) {
                val vserver = mdMatcher.group(2).trim()
                var vfile = mdMatcher.group(3).trim()
                
                Log.d(TAG, "Found via MDCore: vserver=$vserver, vfile=$vfile")
                
                // Rimuovi .mp4 da vfile se presente (per evitare doppio .mp4)
                vfile = vfile.replace(Regex("\\.mp4$"), "")
                
                // Cerca i token s, e, _t
                val tokenPattern = Pattern.compile("""s=([^&]+)&e=([^&]+)&_t=([^&"]+)""")
                val tokenMatcher = tokenPattern.matcher(html)
                
                if (tokenMatcher.find()) {
                    val tokenS = tokenMatcher.group(1).trim()
                    val tokenE = tokenMatcher.group(2).trim()
                    val tokenT = tokenMatcher.group(3).trim()
                    
                    Log.d(TAG, "Tokens: s=$tokenS, e=$tokenE, _t=$tokenT")
                    
                    return "https://${vserver}.mxcontent.net/v2/${vfile}.mp4?s=$tokenS&e=$tokenE&_t=$tokenT"
                }
            }
            
            // Metodo 2: Deoffusca l'eval
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
                val vserver = Regex("""vserver\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)?.trim()
                var vfile = Regex("""vfile\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)?.trim()
                val tokenS = Regex("""s\s*=\s*([^&\s"]+)""").find(unpacked)?.groupValues?.get(1)?.trim()
                val tokenE = Regex("""e\s*=\s*([^&\s"]+)""").find(unpacked)?.groupValues?.get(1)?.trim()
                val tokenT = Regex("""_t\s*=\s*([^&\s"]+)""").find(unpacked)?.groupValues?.get(1)?.trim()
                
                Log.d(TAG, "Extracted: vserver=$vserver, vfile=$vfile, s=$tokenS, e=$tokenE, _t=$tokenT")
                
                if (!vserver.isNullOrEmpty() && !vfile.isNullOrEmpty() && 
                    !tokenS.isNullOrEmpty() && !tokenE.isNullOrEmpty() && !tokenT.isNullOrEmpty()) {
                    
                    // Rimuovi .mp4 da vfile se presente (per evitare doppio .mp4)
                    vfile = vfile.replace(Regex("\\.mp4$"), "")
                    
                    return "https://${vserver}.mxcontent.net/v2/${vfile}.mp4?s=$tokenS&e=$tokenE&_t=$tokenT"
                }
            }
            
            // Metodo 3: Cerca direttamente i pattern nell'HTML non offuscato
            val directPattern = Pattern.compile("""(https?://[a-z0-9]+\.mxcontent\.net/v2/[a-f0-9]+(?:\.mp4)?\?s=[^&]+&e=[^&]+&_t=[^&"]+)""")
            val directMatcher = directPattern.matcher(html)
            if (directMatcher.find()) {
                var directUrl = directMatcher.group(1)
                // Pulisci l'URL: rimuovi spazi e fix doppio .mp4
                directUrl = directUrl.replace(" ", "")
                directUrl = directUrl.replace(".mp4.mp4", ".mp4")
                Log.d(TAG, "Found direct URL: $directUrl")
                return directUrl
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Extraction error: ${e.message}")
        }
        return null
    }
}
