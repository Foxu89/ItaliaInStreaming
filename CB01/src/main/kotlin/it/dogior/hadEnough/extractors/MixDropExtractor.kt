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
    override val mainUrl = "mixdrop.ag"
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
            // Estrai l'ID del video dall'URL
            val videoId = url.substringAfterLast("/")
            Log.d(TAG, "Video ID: $videoId")
            
            // STEP 1: Visita la pagina per ottenere il cookie di sessione e il dominio
            val pageUrl = "https://mixdrop.ag/e/$videoId"
            Log.d(TAG, "Fetching page for cookie: $pageUrl")
            
            val pageHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
            )
            
            val pageResponse = app.get(pageUrl, headers = pageHeaders)
            val html = pageResponse.body.string()
            
            // Estrai i cookie dalla risposta
            val cookieHeader = pageResponse.headers["set-cookie"]
            var cookie = ""
            if (cookieHeader != null) {
                cookie = cookieHeader.split(";").firstOrNull() ?: ""
                Log.d(TAG, "Cookie obtained: $cookie")
            }
            
            // STEP 2: Estrai il dominio dalla pagina HTML (per Referer e Origin)
            val domain = extractDomainFromHtml(html)
            Log.d(TAG, "Extracted domain: $domain")
            
            val finalReferer = "https://$domain/"
            val finalOrigin = "https://$domain"
            Log.d(TAG, "Using Referer: $finalReferer")
            Log.d(TAG, "Using Origin: $finalOrigin")
            
            // STEP 3: Estrai i parametri del video dall'HTML
            val videoUrl = extractVideoUrlFromHtml(html)
            
            if (videoUrl != null) {
                Log.d(TAG, "Video URL extracted: $videoUrl")
                
                // STEP 4: Richiedi il video con gli headers e il cookie
                val videoHeaders = mutableMapOf(
                    "Referer" to finalReferer,
                    "Origin" to finalOrigin,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Connection" to "keep-alive"
                )
                
                // Aggiungi il cookie se presente
                if (cookie.isNotEmpty()) {
                    videoHeaders["Cookie"] = cookie
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "MixDrop",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.headers = videoHeaders
                        this.referer = finalReferer
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
    
    private fun extractDomainFromHtml(html: String): String {
        try {
            // Pattern per cercare il dominio nella pagina
            val domainPatterns = listOf(
                // Cerca nel JavaScript
                Regex("""window\.location\.hostname\s*=\s*"([^"]+)""""),
                Regex("""MDCore\.domain\s*=\s*"([^"]+)""""),
                Regex("""this\.domain\s*=\s*"([^"]+)""""),
                // Cerca negli URL della pagina
                Regex("""https?://([a-z0-9.-]+\.(?:ag|top|net|bz|ps))/"""),
                // Cerca nei meta tag
                Regex("""<meta\s+property="og:url"\s+content="https?://([^"/]+)""""),
                Regex("""<link\s+rel="canonical"\s+href="https?://([^"/]+)""""),
                // Cerca in qualsiasi URL
                Regex("""https?://([a-z0-9.-]+\.(?:ag|top|net|bz|ps))""")
            )
            
            for (pattern in domainPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val domain = match.groupValues[1].trim()
                    if (domain.isNotEmpty() && (domain.contains("mixdrop") || domain.contains("m1xdrop"))) {
                        Log.d(TAG, "Found domain via pattern: $domain")
                        return domain
                    }
                }
            }
            
            // Se non trova nulla, prova a cercare il dominio base nell'URL della pagina
            val urlPattern = Regex("""https?://([a-z0-9.-]+\.(?:ag|top|net|bz|ps))""")
            val urlMatch = urlPattern.find(html)
            if (urlMatch != null) {
                val domain = urlMatch.groupValues[1]
                Log.d(TAG, "Found domain via URL: $domain")
                return domain
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Domain extraction error: ${e.message}")
        }
        
        // Fallback
        Log.d(TAG, "Using fallback domain: mixdrop.ag")
        return "mixdrop.ag"
    }
    
    private fun extractVideoUrlFromHtml(html: String): String? {
        try {
            // Pattern per l'URL completo del video
            val urlPattern = Regex("""(https?://[a-z0-9]+\.mxcontent\.net/v2/[a-f0-9]+\.mp4\?s=[^&]+&e=[^&]+&_t=[^&"]+)""")
            val urlMatch = urlPattern.find(html)
            if (urlMatch != null) {
                var videoUrl = urlMatch.groupValues[1]
                // Pulisci l'URL
                videoUrl = videoUrl.replace(Regex("[\\s\\n\\r]"), "")
                Log.d(TAG, "Found direct URL: $videoUrl")
                return videoUrl
            }
            
            // Pattern per i token separati
            val vserver = Regex("""vserver\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.trim()
            var vfile = Regex("""vfile\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.trim()
            val tokenS = Regex("""s\s*=\s*([^&\s"]+)""").find(html)?.groupValues?.get(1)?.trim()
            val tokenE = Regex("""e\s*=\s*([^&\s"]+)""").find(html)?.groupValues?.get(1)?.trim()
            val tokenT = Regex("""_t\s*=\s*([^&\s"]+)""").find(html)?.groupValues?.get(1)?.trim()
            
            if (!vserver.isNullOrEmpty() && !vfile.isNullOrEmpty() && 
                !tokenS.isNullOrEmpty() && !tokenE.isNullOrEmpty() && !tokenT.isNullOrEmpty()) {
                
                // Rimuovi .mp4 da vfile se presente (evita doppio .mp4)
                vfile = vfile.replace(Regex("\\.mp4$"), "")
                
                val constructedUrl = "https://${vserver}.mxcontent.net/v2/${vfile}.mp4?s=$tokenS&e=$tokenE&_t=$tokenT"
                Log.d(TAG, "Constructed URL from tokens: $constructedUrl")
                return constructedUrl
            }
            
            // Metodo alternativo: deoffusca l'eval
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
                
                val vserverEval = Regex("""vserver\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)?.trim()
                var vfileEval = Regex("""vfile\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)?.trim()
                val tokenSEval = Regex("""s\s*=\s*([^&\s"]+)""").find(unpacked)?.groupValues?.get(1)?.trim()
                val tokenEEval = Regex("""e\s*=\s*([^&\s"]+)""").find(unpacked)?.groupValues?.get(1)?.trim()
                val tokenTEval = Regex("""_t\s*=\s*([^&\s"]+)""").find(unpacked)?.groupValues?.get(1)?.trim()
                
                if (!vserverEval.isNullOrEmpty() && !vfileEval.isNullOrEmpty() && 
                    !tokenSEval.isNullOrEmpty() && !tokenEEval.isNullOrEmpty() && !tokenTEval.isNullOrEmpty()) {
                    
                    vfileEval = vfileEval.replace(Regex("\\.mp4$"), "")
                    
                    val evalUrl = "https://${vserverEval}.mxcontent.net/v2/${vfileEval}.mp4?s=$tokenSEval&e=$tokenEEval&_t=$tokenTEval"
                    Log.d(TAG, "Constructed URL from eval: $evalUrl")
                    return evalUrl
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Extraction error: ${e.message}")
        }
        return null
    }
}
