package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class MixDropExtractor : ExtractorApi() {
    override var name = "MixDrop"
    override var mainUrl = "https://mixdrop.ps"  
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
            "Referer" to "https://cb01uno.uno/",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        
        val response = app.get(url, headers = headers, timeout = 15_000)
        val html = response.text
        
        // MixDrop usa spesso questo pattern
        val wurlRegex = Regex("""wurl\s*=\s*"([^"]+)"""")
        val wurlMatch = wurlRegex.find(html)
        
        if (wurlMatch != null) {
            val videoUrl = wurlMatch.groupValues[1]
            M3u8Helper.generateM3u8(
                name = name,
                url = videoUrl,
                referer = url,
                quality = null,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return
        }
        
        // Altro pattern comune
        val evalRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?}\)\)""")
        val evalMatch = evalRegex.find(html)
        
        if (evalMatch != null) {
            try {
                // Prova a decodificare il codice offuscato
                val unpacked = com.lagradost.cloudstream3.utils.getAndUnpack(evalMatch.value)
                val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8)""")
                val m3u8Match = m3u8Regex.find(unpacked)
                
                m3u8Match?.let {
                    M3u8Helper.generateM3u8(
                        name = name,
                        url = it.groupValues[1],
                        referer = url,
                        quality = null,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    return
                }
            } catch (e: Exception) {
                // Ignora errore unpack
            }
        }
        
        // Ultimo tentativo: cerca qualsiasi m3u8
        val anyM3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        val anyM3u8Match = anyM3u8Regex.find(html)
        
        anyM3u8Match?.let {
            M3u8Helper.generateM3u8(
                name = name,
                url = it.groupValues[1],
                referer = url,
                quality = null,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return
        }
        
        throw ErrorLoadingException("Video non trovato su MixDrop")
    }
}
