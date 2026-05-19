package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class VidxGoExtractor : ExtractorApi() {
    override val mainUrl = "https://v.vidxgo.co"
    override val name = "VidxGo"
    override val requiresReferer = true

    companion object {
        private const val REFERER = "https://altadefinizione.you/"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/148.0.0.0 Safari/537.36"
        
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
        // Estrae l'ID IMDb e converte tt33504773 -> 33504773
        val targetUrl = buildTargetUrl(url)
        
        val resolver = WebViewResolver(
            interceptUrl = Regex(".*\\.m3u8.*"),
            useOkhttp = true,
            timeout = 20000L
        )
        
        try {
            val response = app.get(
                url = targetUrl,
                referer = REFERER,
                interceptor = resolver,
                headers = CUSTOM_HEADERS
            )
            
            val finalUrl = response.url
            
            if (finalUrl.contains(".m3u8", ignoreCase = true)) {
                callback.invoke(
                    newExtractorLink(
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
                )
            }
        } catch (_: Exception) {
            // Silenzioso
        }
    }
    
    private fun buildTargetUrl(url: String): String {
        // Supporta diversi formati di input
        
        // Formato 1: URL già completo (es. "https://v.vidxgo.co/33504773")
        if (url.startsWith(mainUrl)) {
            return url
        }
        
        // Formato 2: solo ID film (es. "tt33504773" o "33504773")
        val patterns = listOf(
            Regex("""tt(\d+)"""),      // tt33504773
            Regex("""^(\d+)$""")       // 33504773
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { id ->
                return "$mainUrl/$id"
            }
        }
        
        // Formato 3: serie TV con stagione/episodio (es. "tt4574334-1-1" o "4574334-1-1")
        val seriePattern = Regex("""(?:tt)?(\d+)[-/](\d+)[-/](\d+)""")
        seriePattern.find(url)?.let { match ->
            val id = match.groupValues[1]
            val season = match.groupValues[2]
            val episode = match.groupValues[3]
            return "$mainUrl/$id/$season/$episode"
        }
        
        // Fallback: usa l'URL come arriva
        return url
    }
}
