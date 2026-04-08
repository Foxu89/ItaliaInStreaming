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
    override val mainUrl = "mixdrop.ps"
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
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to "https://mixdrop.ps/"
            )
            
            val response = app.get(url, headers = headers)
            val html = response.body.string()
            
            Log.d(TAG, "HTML length: ${html.length}")
            
            // Cerca l'URL del video direttamente nell'HTML
            val videoUrl = extractVideoUrl(html)
            
            if (videoUrl != null) {
                Log.d(TAG, "Found video URL: $videoUrl")
                
                val videoHeaders = mapOf(
                    "Referer" to "https://mixdrop.ps/",
                    "Origin" to "https://mixdrop.ps",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "MixDrop",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = videoHeaders
                        this.referer = "https://mixdrop.ps/"
                    }
                )
            } else {
                Log.e(TAG, "Failed to extract video URL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }
    
    private fun extractVideoUrl(html: String): String? {
        // Pattern per cercare l'URL del video
        val patterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.mp4[^"]*)""""),
            Regex(""""source"\s*:\s*"([^"]+\.mp4[^"]*)""""),
            Regex(""""url"\s*:\s*"([^"]+\.mp4[^"]*)""""),
            Regex("""https?://[^"'\s]+\.mxcontent\.net[^"'\s]+\.mp4[^"'\s]*""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        // Se non trova, prova a deoffuscare
        return deobfuscateAndGetVideoUrl(html)
    }
    
    private fun deobfuscateAndGetVideoUrl(html: String): String? {
        try {
            val evalPattern = Pattern.compile(
                """\}\('(.*?)',\d+,\d+,'(.*?)'\.split\('\|'\)""",
                Pattern.DOTALL
            )
            val matcher = evalPattern.matcher(html)
            
            if (matcher.find()) {
                val payload = matcher.group(1)
                val wordsStr = matcher.group(2)
                val words = wordsStr.split("|")
                
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
                
                val vserver = Regex("""vserver\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                val vfile = Regex("""vfile\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                val tokenS = Regex("""s=([^&"]+)""").find(unpacked)?.groupValues?.get(1)
                val tokenE = Regex("""e=([^&"]+)""").find(unpacked)?.groupValues?.get(1)
                val tokenT = Regex("""_t=([^&"]+)""").find(unpacked)?.groupValues?.get(1)
                
                if (!vserver.isNullOrEmpty() && !vfile.isNullOrEmpty()) {
                    var url = "https://${vserver}.mxcontent.net/v2/${vfile}.mp4"
                    if (!tokenS.isNullOrEmpty()) url += "?s=$tokenS"
                    if (!tokenE.isNullOrEmpty()) url += "&e=$tokenE"
                    if (!tokenT.isNullOrEmpty()) url += "&_t=$tokenT"
                    return url
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deobfuscation error: ${e.message}")
        }
        return null
    }
}
