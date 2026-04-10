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
        // User-Agent moderno e standard per evitare sospetti
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Uniformiamo l'URL di base (gestisce sia mixdrop.co, .to, .top, ecc.)
        val normalizedUrl = if (url.startsWith("http")) url else "https://$url"
        val domain = normalizedUrl.substringAfter("https://").substringBefore("/")
        
        Log.d(TAG, "Inizio estrazione da: $normalizedUrl")
        
        try {
            val videoId = normalizedUrl.substringAfterLast("/")
            val pageUrl = "https://$domain/e/$videoId"
            
            val commonHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to "https://$domain/"
            )
            
            val pageResponse = app.get(pageUrl, headers = commonHeaders)
            val html = pageResponse.body.string()
            
            val videoUrl = extractVideoUrlFromHtml(html)
            
            if (videoUrl != null) {
                Log.d(TAG, "Link trovato: $videoUrl")
                
                // COSTRUZIONE HEADERS PER IL PLAYER (Fondamentale per evitare 403)
                val videoHeaders = mutableMapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "it-IT,it;q=0.9",
                    "Range" to "bytes=0-",
                    "Referer" to "https://$domain/", // Deve corrispondere al dominio della pagina
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
                Log.e(TAG, "Impossibile trovare l'URL del video nel sorgente")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante l'estrazione: ${e.message}")
        }
    }
    
    private fun extractVideoUrlFromHtml(html: String): String? {
        try {
            // 1. Prova estrazione diretta (URL già renderizzato)
            val urlPattern = Regex("""(https?://[a-z0-9]+\.mxcontent\.net/v2/[a-f0-9]+\.mp4\?s=[^&]+&e=[^&]+&_t=[^&"]+)""")
            val urlMatch = urlPattern.find(html)
            if (urlMatch != null) {
                return urlMatch.groupValues[1].replace(Regex("[\\s\\n\\r]"), "")
            }
            
            // 2. Prova estrazione tramite variabili MDCore
            val vserver = Regex("""vserver\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val vfile = Regex("""vfile\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val s = Regex("""s\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val e = Regex("""e\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val t = Regex("""_t\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            
            if (vserver != null && vfile != null && s != null) {
                val cleanFile = vfile.replace(".mp4", "")
                return "https://$vserver.mxcontent.net/v2/$cleanFile.mp4?s=$s&e=$e&_t=$t"
            }

            // 3. Fallback: Unpack se il codice è offuscato (eval)
            val evalPattern = Pattern.compile("""\}\('(.*?)',\s*\d+,\s*\d+,\s*'(.*?)'\.split\('\|'\)""", Pattern.DOTALL)
            val matcher = evalPattern.matcher(html)
            
            if (matcher.find()) {
                val payload = matcher.group(1)
                val words = matcher.group(2).split("|")
                
                // Semplice unpacking basato sugli indici
                val unpacked = StringBuilder()
                var temp = ""
                for (char in payload) {
                    if (char.isLetterOrDigit()) {
                        temp += char
                    } else {
                        if (temp.isNotEmpty()) {
                            val idx = temp.toIntOrNull(36) // MixDrop usa base 36 spesso
                            if (idx != null && idx < words.size && words[idx].isNotEmpty()) {
                                unpacked.append(words[idx])
                            } else {
                                unpacked.append(temp)
                            }
                            temp = ""
                        }
                        unpacked.append(char)
                    }
                }
                
                val res = unpacked.toString()
                val vserverE = Regex("""vserver="([^"]+)""").find(res)?.groupValues?.get(1)
                val vfileE = Regex("""vfile="([^"]+)""").find(res)?.groupValues?.get(1)
                val sE = Regex("""s="([^"]+)""").find(res)?.groupValues?.get(1)
                val eE = Regex("""e="([^"]+)""").find(res)?.groupValues?.get(1)
                val tE = Regex("""_t="([^"]+)""").find(res)?.groupValues?.get(1)
                
                if (vserverE != null && vfileE != null) {
                    return "https://$vserverE.mxcontent.net/v2/${vfileE.replace(".mp4", "")}.mp4?s=$sE&e=$eE&_t=$tE"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore nella regex: ${e.message}")
        }
        return null
    }
}                         
