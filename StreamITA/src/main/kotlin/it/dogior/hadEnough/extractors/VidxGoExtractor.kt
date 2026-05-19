package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.StreamITALogger.log
import kotlinx.coroutines.withTimeoutOrNull

class VidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = false

    companion object {
        private const val REFERER = "https://altadefinizione.you/"
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:150.0) Gecko/20100101 Firefox/150.0"
        
        private val CUSTOM_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-GPC" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "DNT" to "1",
            "Referer" to REFERER,
            "Priority" to "u=0, i"
        )
        
        private fun extractM3u8FromHtml(html: String): String? {
            try {
                // ============================================================
                // 1. Trova TUTTI gli script nella pagina
                // ============================================================
                val scriptRegex = Regex("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
                val scripts = scriptRegex.findAll(html).map { it.groupValues[1] }.toList()
                
                log("VidxGo", "🔍 Trovati ${scripts.size} script")
                
                if (scripts.size <= 5) {
                    log("VidxGo", "❌ Script insufficienti: ${scripts.size}")
                    return null
                }
                
                // ============================================================
                // 2. Prendi il 6° script (index 5) - come nel codice TypeScript
                // ============================================================
                val targetScript = scripts[5]
                log("VidxGo", "📜 Script target (index 5): ${targetScript.take(200)}...")
                
                // ============================================================
                // 3. Cerca var NOME='KEY', d=atob('BASE64')
                // ============================================================
                val keyRegex = Regex("""var\s+\w+\s*=\s*'([^']*)'\s*,\s*d\s*=\s*atob\(\s*'([^']*)'""")
                val match = keyRegex.find(targetScript)
                
                if (match == null) {
                    log("VidxGo", "❌ Pattern key/base64 non trovato")
                    return null
                }
                
                val key = match.groupValues[1]
                val base64 = match.groupValues[2]
                
                log("VidxGo", "🔑 Key: $key")
                log("VidxGo", "📦 Base64 length: ${base64.length}")
                
                // ============================================================
                // 4. Decodifica base64
                // ============================================================
                val decoded = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                log("VidxGo", "🔓 Decoded length: ${decoded.size}")
                
                // ============================================================
                // 5. XOR con la key
                // ============================================================
                val decrypted = ByteArray(decoded.size)
                for (i in decoded.indices) {
                    decrypted[i] = (decoded[i].toInt() xor key[i % key.length].code).toByte()
                }
                
                val decryptedText = String(decrypted, Charsets.UTF_8)
                log("VidxGo", "📝 Decrypted preview: ${decryptedText.take(300)}")
                
                // ============================================================
                // 6. Cerca currentSrc per trovare l'm3u8
                // ============================================================
                val urlRegex = Regex("""currentSrc.+?"(https:[^";]+)"""")
                val urlMatch = urlRegex.find(decryptedText)
                
                if (urlMatch == null) {
                    log("VidxGo", "❌ currentSrc URL non trovato")
                    return null
                }
                
                var m3u8Url = urlMatch.groupValues[1]
                m3u8Url = m3u8Url.replace("\\", "")
                
                log("VidxGo", "🎯 M3U8 TROVATO: $m3u8Url")
                return m3u8Url
                
            } catch (e: Exception) {
                log("VidxGo", "❌ Errore decodifica: ${e.message}")
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
        // ============================================================
        // Estrae l'ID numerico dall'URL
        // ============================================================
        val regex = Regex("""(\d+)""")
        val match = regex.find(url)
        val rawId = match?.value
        
        if (rawId == null) {
            log("VidxGo", "❌ Nessun ID numerico trovato in: $url")
            return
        }
        
        val targetUrl = "$mainUrl/$rawId"
        log("VidxGo", "🎬 URL: $targetUrl")
        
        // ============================================================
        // Scarica l'HTML della pagina
        // ============================================================
        val response = withTimeoutOrNull(15000L) {
            try {
                app.get(targetUrl, headers = CUSTOM_HEADERS, timeout = 15)
            } catch (e: Exception) {
                log("VidxGo", "❌ Errore download: ${e.message}")
                null
            }
        }
        
        if (response == null || !response.isSuccessful) {
            log("VidxGo", "❌ Risposta non valida: ${response?.code}")
            return
        }
        
        val html = response.text
        log("VidxGo", "📄 HTML scaricato: ${html.length} caratteri")
        
        // ============================================================
        // Estrai l'm3u8 dall'HTML
        // ============================================================
        val m3u8Url = extractM3u8FromHtml(html)
        
        if (m3u8Url != null) {
            log("VidxGo", "🎯 M3U8 TROVATO: $m3u8Url")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "VidxGo",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                        "Referer" to mainUrl + "/",
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                    this.referer = mainUrl + "/"
                }
            )
        } else {
            log("VidxGo", "❌ Nessun M3U8 trovato nell'HTML")
        }
    }
}
