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
            val videoId = url.substringAfterLast("/")
            Log.d(TAG, "Video ID: $videoId")
            
            val pageUrl = "https://mixdrop.top/e/$videoId"
            Log.d(TAG, "Fetching page: $pageUrl")
            
            val pageHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
                "Referer" to "https://m1xdrop.net/"
            )
            
            val pageResponse = app.get(pageUrl, headers = pageHeaders)
            val html = pageResponse.body.string()
            
            val videoUrl = extractVideoUrlFromHtml(html)
            
            if (videoUrl != null) {
                Log.d(TAG, "Video URL extracted: $videoUrl")
                
                val videoHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
                    "Referer" to "https://m1xdrop.net/"
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
                Log.e(TAG, "Failed to extract video URL from HTML")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun extractVideoUrlFromHtml(html: String): String? {
        try {
            // 1. Prima cerca il pattern MDCore (il più affidabile)
            val mdcoreRegex = Regex("""MDCore\.[a-zA-Z0-9]+\s*=\s*["'](//[^"']+\.mp4[^"']*)["']""")
            val mdcoreMatch = mdcoreRegex.find(html)
            if (mdcoreMatch != null) {
                var videoUrl = mdcoreMatch.groupValues[1]
                if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
                Log.d(TAG, "Found MDCore URL: $videoUrl")
                return videoUrl
            }
            
            // 2. Pattern standard con URL completo
            val urlPattern = Regex("""(https?://[a-z0-9]+\.mxcontent\.net/v2/[a-f0-9]+\.mp4\?s=[^&]+&e=[^&]+&_t=[^&"]+)""")
            val urlMatch = urlPattern.find(html)
            if (urlMatch != null) {
                var videoUrl = urlMatch.groupValues[1]
                videoUrl = videoUrl.replace(Regex("[\\s\\n\\r]"), "")
                Log.d(TAG, "Found direct URL: $videoUrl")
                return videoUrl
            }
            
            // 3. Cerca il pattern packer (eval) e de-offusca
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
                
                // Cerca MDCore anche nel codice de-offuscato
                val mdcoreInUnpacked = Regex("""MDCore\.[a-zA-Z0-9]+\s*=\s*["'](//[^"']+\.mp4[^"']*)["']""").find(unpacked)
                if (mdcoreInUnpacked != null) {
                    var videoUrl = mdcoreInUnpacked.groupValues[1]
                    if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
                    Log.d(TAG, "Found MDCore URL in unpacked: $videoUrl")
                    return videoUrl
                }
                
                // Pattern standard nel codice de-offuscato
                val vserverEval = Regex("""vserver\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)?.trim()
                var vfileEval = Regex("""vfile\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)?.trim()
                val tokenSEval = Regex("""s\s*=\s*([^&\s"]+)""").find(unpacked)?.groupValues?.get(1)?.trim()
                val tokenEEval = Regex("""e\s*=\s*([^&\s"]+)""").find(unpacked)?.groupValues?.get(1)?.trim()
                val tokenTEval = Regex("""_t\s*=\s*([^&\s"]+)""").find(unpacked)?.groupValues?.get(1)?.trim()
                
                if (!vserverEval.isNullOrEmpty() && !vfileEval.isNullOrEmpty() && 
                    !tokenSEval.isNullOrEmpty() && !tokenEEval.isNullOrEmpty() && !tokenTEval.isNullOrEmpty()) {
                    
                    vfileEval = vfileEval.replace(Regex("\\.mp4$"), "")
                    
                    return "https://${vserverEval}.mxcontent.net/v2/${vfileEval}.mp4?s=$tokenSEval&e=$tokenEEval&_t=$tokenTEval"
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Extraction error: ${e.message}")
        }
        return null
    }
}
                                      
