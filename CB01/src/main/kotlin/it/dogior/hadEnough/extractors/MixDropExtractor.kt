package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.util.regex.Pattern

class MixDropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "mixdrop.top"
    override val requiresReferer = false

    companion object {
        private const val TAG = "MixDropExtractor"
        
        // Headers necessari per il video
        private val VIDEO_HEADERS = mapOf(
            "Referer" to "https://m1xdrop.net/",
            "Origin" to "https://m1xdrop.net",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive"
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
            // 1. Ottieni la pagina HTML
            val pageDoc = app.get(url).document
            val html = pageDoc.html()
            
            // 2. Deoffusca lo script per ottenere i parametri
            val videoUrl = deobfuscateAndGetVideoUrl(html)
            
            if (videoUrl != null) {
                Log.d(TAG, "Final video URL: $videoUrl")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "MixDrop",
                        url = videoUrl,
                        type = ExtractorLinkType.MP4,
                        quality = Qualities.P720.value
                    ) {
                        this.headers = VIDEO_HEADERS
                        this.referer = "https://m1xdrop.net/"
                    }
                )
            } else {
                Log.e(TAG, "Failed to extract video URL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }
    
    private fun deobfuscateAndGetVideoUrl(html: String): String? {
        try {
            // Cerca il pattern eval(function(p,a,c,k,e,d)
            val evalPattern = Pattern.compile(
                """\}\('(.*?)',\d+,\d+,'(.*?)'\.split\('\|'\)""",
                Pattern.DOTALL
            )
            val matcher = evalPattern.matcher(html)
            
            if (matcher.find()) {
                val payload = matcher.group(1)
                val wordsStr = matcher.group(2)
                val words = wordsStr.split("|")
                
                // Deoffusca il payload
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
                Log.d(TAG, "Unpacked script: $unpacked")
                
                // Estrai i parametri
                val vserver = Regex("""vserver\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                val vfile = Regex("""vfile\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                val tokenS = Regex("""s=([^&"]+)""").find(unpacked)?.groupValues?.get(1)
                val tokenE = Regex("""e=([^&"]+)""").find(unpacked)?.groupValues?.get(1)
                val tokenT = Regex("""_t=([^&"]+)""").find(unpacked)?.groupValues?.get(1)
                
                Log.d(TAG, "vserver=$vserver, vfile=$vfile, s=$tokenS, e=$tokenE, _t=$tokenT")
                
                if (vserver != null && vfile != null && tokenS != null && tokenE != null && tokenT != null) {
                    return "https://${vserver}.mxcontent.net/v2/${vfile}.mp4?s=$tokenS&e=$tokenE&_t=$tokenT"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deobfuscation error: ${e.message}")
        }
        return null
    }
}
