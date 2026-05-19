package it.dogior.hadEnough.extractors

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.StreamITALogger.log

class VidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = false

    companion object {
        // Headers per la richiesta HTML (pagina del player)
        private val HTML_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:150.0) Gecko/20100101 Firefox/150.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-GPC" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "DNT" to "1",
            "Referer" to "https://altadefinizione.you/",
            "Priority" to "u=0, i"
        )
        
        // Headers per la richiesta M3U8 (CDN)
        private val M3U8_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/148.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Origin" to "https://v.vidxgo.co",
            "Referer" to "https://v.vidxgo.co/",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Priority" to "u=1, i",
            "DNT" to "1"
        )
        
        private fun extractM3u8FromHtml(html: String): String? {
            try {
                val scriptRegex = Regex("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
                val scripts = scriptRegex.findAll(html).map { it.groupValues[1] }.toList()
                
                log("VidxGo", "🔍 Trovati ${scripts.size} script")
                
                val pattern = Regex("""var\s+(\w+)\s*=\s*'([^']*)'\s*,\s*(\w+)\s*=\s*atob\(\s*'([^']*)'""")
                
                for ((idx, script) in scripts.withIndex()) {
                    val match = pattern.find(script)
                    if (match != null) {
                        val key = match.groupValues[2]
                        val base64Str = match.groupValues[4]
                        
                        log("VidxGo", "✅ Trovato script #$idx")
                        log("VidxGo", "🔑 Key: $key")
                        
                        val decoded = Base64.decode(base64Str, Base64.DEFAULT)
                        
                        val decrypted = ByteArray(decoded.size)
                        for (i in decoded.indices) {
                            decrypted[i] = (decoded[i].toInt() xor key[i % key.length].code).toByte()
                        }
                        
                        val decryptedText = String(decrypted, Charsets.UTF_8)
                        
                        val urlPattern = Regex("""currentSrc[^"]*["'](https:[^"']+\.m3u8[^"']*)""")
                        val urlMatch = urlPattern.find(decryptedText)
                        
                        if (urlMatch != null) {
                            return urlMatch.groupValues[1].replace("\\", "")
                        }
                        
                        val altUrlPattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                        val altMatch = altUrlPattern.find(decryptedText)
                        if (altMatch != null) {
                            return altMatch.groupValues[1].replace("\\", "")
                        }
                    }
                }
                
                return null
            } catch (e: Exception) {
                log("VidxGo", "❌ Errore: ${e.message}")
                return null
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Costruisce l'URL target (supporta film e serie TV)
        val targetUrl = buildTargetUrl(url)
        log("VidxGo", "🎬 URL: $targetUrl")
        
        // Scarica l'HTML con gli headers corretti
        val response = app.get(targetUrl, headers = HTML_HEADERS)
        
        if (!response.isSuccessful) {
            log("VidxGo", "❌ HTTP ${response.code}")
            return
        }
        
        val html = response.text
        log("VidxGo", "📄 HTML: ${html.length} caratteri")
        
        // Estrae l'm3u8 dall'HTML
        val m3u8Url = extractM3u8FromHtml(html)
        
        if (m3u8Url != null) {
            log("VidxGo", "🎯 TROVATO: $m3u8Url")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "VidxGo",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    // HEADERS PER IL CDN (fondamentali per evitare 403)
                    this.headers = M3U8_HEADERS
                    this.referer = "https://v.vidxgo.co/"
                }
            )
        } else {
            log("VidxGo", "❌ Nessun M3U8 trovato")
        }
    }
    
    private fun buildTargetUrl(url: String): String {
        // Se è già un URL valido di vidxgo, lo restituisco
        if (url.startsWith(mainUrl)) {
            return url
        }
        
        // Estrae l'ID numerico (tt1375666 -> 1375666)
        val idRegex = Regex("""(\d+)""")
        val idMatch = idRegex.find(url)
        val rawId = idMatch?.value
        
        if (rawId == null) {
            log("VidxGo", "❌ Nessun ID trovato in: $url")
            return url
        }
        
        // Cerca se ci sono stagione/episodio nel formato "tt4574334-1-1" o "4574334-1-1"
        val seasonEpisodeRegex = Regex("""(?:tt)?\d+[-/](\d+)[-/](\d+)""")
        val seMatch = seasonEpisodeRegex.find(url)
        
        return if (seMatch != null) {
            val season = seMatch.groupValues[1]
            val episode = seMatch.groupValues[2]
            "$mainUrl/$rawId/$season/$episode"
        } else {
            "$mainUrl/$rawId"
        }
    }
}
