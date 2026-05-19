package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.StreamITALogger.log

class VidxGoExtractor : ExtractorApi() {
    override val mainUrl = "https://v.vidxgo.co"
    override val name = "VidxGo"
    override val requiresReferer = true

    companion object {
        private const val REFERER = "https://altadefinizione.you/"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/148.0.0.0 Safari/537.36"
        private const val TIMEOUT_MS = 20000L
        
        private val CUSTOM_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Priority" to "u=0, i",
            "Referer" to REFERER,
            "Sec-Ch-Ua" to "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Storage-Access" to "active",
            "Upgrade-Insecure-Requests" to "1",
            "User-Agent" to USER_AGENT
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        log("VidxGo", "🚀 Avvio estrattore per: $url")
        
        // Estrae l'ID IMDb e converte tt33504773 -> 33504773
        val targetUrl = buildTargetUrl(url)
        log("VidxGo", "📍 URL target costruita: $targetUrl")
        
        val resolver = WebViewResolver(
            interceptUrl = Regex(".*\\.m3u8.*"),
            useOkhttp = true,
            timeout = TIMEOUT_MS
        )
        log("VidxGo", "🔧 WebViewResolver configurato (timeout: ${TIMEOUT_MS}ms)")
        
        try {
            log("VidxGo", "📡 Inizio richiesta a: $targetUrl")
            log("VidxGo", "📋 Headers inviati: ${CUSTOM_HEADERS.keys.joinToString()}")
            log("VidxGo", "🔑 Referer obbligatorio: $REFERER")
            
            val response = app.get(
                url = targetUrl,
                referer = REFERER,
                interceptor = resolver,
                headers = CUSTOM_HEADERS
            )
            
            val finalUrl = response.url
            log("VidxGo", "📥 Risposta ricevuta - URL finale: $finalUrl")
            log("VidxGo", "📊 Status code: ${response.code}")
            
            if (finalUrl.contains(".m3u8", ignoreCase = true)) {
                log("VidxGo", "🎯🎯🎯 SUCCESSO! M3U8 TROVATO: $finalUrl")
                
                val link = newExtractorLink(
                    source = name,
                    name = name,
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = REFERER
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to REFERER,
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*"
                    )
                }
                
                log("VidxGo", "🔗 Link creato: quality=${link.quality}, type=${link.type}")
                callback.invoke(link)
                log("VidxGo", "✅ Callback invocato con successo")
            } else {
                log("VidxGo", "⚠️ L'URL finale non contiene .m3u8: $finalUrl")
                log("VidxGo", "❌ Estrazione fallita - nessun M3U8 trovato")
            }
        } catch (e: Exception) {
            log("VidxGo", "❌❌❌ ECCEZIONE: ${e.javaClass.simpleName}")
            log("VidxGo", "📝 Messaggio: ${e.message}")
            log("VidxGo", "📍 Stack trace: ${e.stackTrace.take(3).joinToString(" -> ")}")
        }
    }
    
    private fun buildTargetUrl(url: String): String {
        log("VidxGo", "🔨 buildTargetUrl - Input: $url")
        
        // Formato 1: URL già completo (es. "https://v.vidxgo.co/33504773")
        if (url.startsWith(mainUrl)) {
            log("VidxGo", "✅ Rilevato URL completo: $url")
            return url
        }
        
        // Formato 2: solo ID film (es. "tt33504773" o "33504773")
        val patterns = listOf(
            Regex("""tt(\d+)"""),      // tt33504773
            Regex("""^(\d+)$""")       // 33504773
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { id ->
                val result = "$mainUrl/$id"
                log("VidxGo", "✅ Pattern '${pattern.pattern}' matchato. ID=$id → URL=$result")
                return result
            }
        }
        
        // Formato 3: serie TV con stagione/episodio (es. "tt4574334-1-1" o "4574334-1-1")
        val seriePattern = Regex("""(?:tt)?(\d+)[-/](\d+)[-/](\d+)""")
        seriePattern.find(url)?.let { match ->
            val id = match.groupValues[1]
            val season = match.groupValues[2]
            val episode = match.groupValues[3]
            val result = "$mainUrl/$id/$season/$episode"
            log("VidxGo", "✅ Pattern serie TV matchato. ID=$id, S=$season, E=$episode → URL=$result")
            return result
        }
        
        // Fallback: usa l'URL come arriva
        log("VidxGo", "⚠️ Nessun pattern matchato, uso URL originale: $url")
        return url
    }
}
