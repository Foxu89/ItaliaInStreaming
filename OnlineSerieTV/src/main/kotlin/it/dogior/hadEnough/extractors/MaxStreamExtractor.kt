package it.dogior.hadEnough.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class MaxStreamExtractor : ExtractorApi() {
    override var name = "MaxStream"
    override var mainUrl = "https://maxstream.video/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("MaxStream", "🔷 getUrl() ricevuto: $url")

        // Step 1: se URL è uprots/, carica WebView per bypassare Cloudflare sul redirect
        val finalUrl = if (url.contains("uprots/")) {
            Log.d("MaxStream", "🌐 URL uprots, avvio WebViewResolver...")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""/watch_free/"""),
                useOkhttp = false,
                timeout = 20_000L
            )
            val (interceptedRequest, _) = resolver.resolveUsingWebView(url)
            val intercepted = interceptedRequest?.url?.toString()
            Log.d("MaxStream", "📡 Intercettato: $intercepted")
            if (intercepted == null) {
                Log.e("MaxStream", "❌ Nessun watch_free intercettato")
                return
            }
            intercepted
        } else {
            url
        }

        // Step 2: estrai videoId da /watch_free/xxx/{VIDEOID}/hash
        val videoId = Regex("""watch_free/[^/]+/([^/]+)""").find(finalUrl)?.groupValues?.get(1)
        if (videoId == null) {
            Log.e("MaxStream", "❌ VideoId non estratto da: $finalUrl")
            return
        }
        Log.d("MaxStream", "✅ videoId: $videoId")

        // Step 3: fetch iframe e regex M3U8
        val iframeUrl = "https://maxstream.video/emhuih/$videoId"
        Log.d("MaxStream", "🌐 Fetch iframe: $iframeUrl")

        val html = app.get(iframeUrl).body.string()
        val m3u8Url = Regex("""src:\s*"([^"]+master\.m3u8[^"]*)""").find(html)?.groupValues?.get(1)

        if (m3u8Url == null) {
            Log.e("MaxStream", "❌ M3U8 non trovato nell'HTML")
            return
        }

        Log.d("MaxStream", "✅✅ M3U8: $m3u8Url")

        M3u8Helper.generateM3u8(
            name,
            m3u8Url,
            iframeUrl,
            headers = mapOf("referer" to "https://maxstream.video/")
        ).forEach(callback)
    }
}
