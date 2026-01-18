package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class MaxStreamExtractor : ExtractorApi() {
    override var name = "MaxStream"
    override var mainUrl = "https://maxstream.video"
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
            "Referer" to referer ?: "https://cb01uno.uno/",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        
        val response = app.get(url, headers = headers, timeout = 15_000)
        val html = response.text
        
        // Cerca iframe
        val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']+)["']""")
        val iframeMatch = iframeRegex.find(html)
        
        if (iframeMatch != null) {
            val iframeUrl = iframeMatch.groupValues[1]
            if (iframeUrl.contains("vidplay") || iframeUrl.contains("filemoon")) {
                throw ErrorLoadingException("MaxStream reindirizza a servizio esterno")
            }
        }
        
        // Cerca m3u8 diretto
        val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        val m3u8Match = m3u8Regex.find(html)
        
        if (m3u8Match != null) {
            val m3u8Url = m3u8Match.groupValues[1]
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return
        }
        
        // Cerca in script
        val scriptRegex = Regex("""sources\s*:\s*\[([^\]]+)\]""")
        val scriptMatch = scriptRegex.find(html)
        
        if (scriptMatch != null) {
            val sourcesText = scriptMatch.groupValues[1]
            val fileRegex = Regex("""file\s*:\s*"([^"]+)"""")
            val fileMatch = fileRegex.find(sourcesText)
            
            fileMatch?.let {
                val videoUrl = it.groupValues[1]
                if (videoUrl.endsWith(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        videoUrl,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    return
                }
            }
        }
        
        throw ErrorLoadingException("Video non trovato su MaxStream")
    }
}
