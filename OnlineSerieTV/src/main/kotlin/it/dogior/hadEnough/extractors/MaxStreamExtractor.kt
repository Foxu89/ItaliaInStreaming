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
        Log.d("MaxStream", "🟦 getUrl() INIZIO")
        Log.d("MaxStream", "🟦 URL ricevuto: $url")

        // Step 1: WebViewResolver per intercettare /watch_free/ su maxwe241.site
        Log.d("MaxStream", "🟡 Step 1: WebViewResolver(/watch_free/)...")
        val resolver1 = WebViewResolver(
            interceptUrl = Regex("""/watch_free/"""),
            useOkhttp = false,
            timeout = 30_000L
        )
        val response1 = app.get(url, referer = referer ?: url, interceptor = resolver1)
        val maxweUrl = response1.url
        Log.d("MaxStream", "🟡 Step 1 - URL intercettato: $maxweUrl")

        if (maxweUrl.isEmpty() || !maxweUrl.contains("/watch_free/")) {
            Log.e("MaxStream", "❌ Step 1 fallito: /watch_free/ non trovato in $maxweUrl")
            return
        }

        // Estrai videoId da /watch_free/xxx/{VIDEOID}/hash
        val videoIdMatch = Regex("""watch_free/[^/]+/([^/]+)""").find(maxweUrl)
        val videoId = videoIdMatch?.groupValues?.get(1)
        if (videoId == null) {
            Log.e("MaxStream", "❌ videoId non estratto da: $maxweUrl")
            return
        }
        Log.d("MaxStream", "✅ Step 1 - videoId: $videoId")

        // Step 2: WebViewResolver per caricare maxstream.video/emhuih/{videoId}
        // e intercettare la pagina HTML (non l'M3U8)
        val iframeUrl = "https://maxstream.video/emhuih/$videoId"
        Log.d("MaxStream", "🟡 Step 2: WebViewResolver(/emhuih/) su $iframeUrl")

        val resolver2 = WebViewResolver(
            interceptUrl = Regex("""/emhuih/"""),
            useOkhttp = false,
            timeout = 30_000L
        )
        val response2 = app.get(iframeUrl, interceptor = resolver2)
        val responseUrl = response2.url
        Log.d("MaxStream", "🟡 Step 2 - URL response: $responseUrl")

        val html = response2.body.string()
        Log.d("MaxStream", "🟡 Step 2 - HTML ricevuto, lunghezza: ${html.length}")

        // Cerca master.m3u8 nell'HTML
        val m3u8Match = Regex("""src:\s*"([^"]+master\.m3u8[^"]*)""").find(html)
        val m3u8Url = m3u8Match?.groupValues?.get(1)

        if (m3u8Url == null) {
            Log.e("MaxStream", "❌ M3U8 non trovato nell'HTML!")
            Log.d("MaxStream", "🔍 HTML primi 1000:\n${html.take(1000)}")
            Log.d("MaxStream", "🔍 master.m3u8 presente? ${html.contains("master.m3u8")}")
            Log.d("MaxStream", "🔍 sources presente? ${html.contains("sources")}")
            return
        }

        Log.d("MaxStream", "✅✅✅ M3U8 TROVATO: $m3u8Url")
        M3u8Helper.generateM3u8(
            name, m3u8Url, iframeUrl,
            headers = mapOf("referer" to "https://maxstream.video/")
        ).forEach(callback)
        Log.d("MaxStream", "🎉 Done!")
    }
}
