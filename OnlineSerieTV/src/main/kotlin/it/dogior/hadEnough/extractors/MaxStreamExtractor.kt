package it.dogior.hadEnough.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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
        Log.d("MaxStream", "🤡 getUrl() ricevuto url: $url")
        Log.d("MaxStream", "🤡 referer: $referer")

        val videoIdMatch = Regex("""watch_free/[^/]+/([^/]+)""").find(url)
        Log.d("MaxStream", "🤡 Regex videoId: ${videoIdMatch?.groupValues}")
        val videoId = videoIdMatch?.groupValues?.get(1)
        if (videoId == null) {
            Log.e("MaxStream", "❌ Video ID non estratto da: $url")
            return
        }
        Log.d("MaxStream", "✅ videoId estratto: $videoId")

        val iframeUrl = "https://maxstream.video/emhuih/$videoId"
        Log.d("MaxStream", "🌐 iframeUrl: $iframeUrl")

        val response = app.get(iframeUrl)
        Log.d("MaxStream", "📡 response.code: ${response.code}  url: ${response.url}")
        val html = response.body.string()
        Log.d("MaxStream", "📄 HTML ricevuto, lunghezza: ${html.length}")
        Log.d("MaxStream", "📄 HTML primi 500:\n${html.take(500)}")
        Log.d("MaxStream", "📄 HTML ultimi 500:\n${html.takeLast(500)}")

        val m3u8Match = Regex("""src:\s*"([^"]+master\.m3u8[^"]*)""").find(html)
        Log.d("MaxStream", "🤡 Regex M3U8: ${m3u8Match?.groupValues}")
        val m3u8Url = m3u8Match?.groupValues?.get(1)

        if (m3u8Url == null) {
            Log.e("MaxStream", "❌ M3U8 non trovato nell'HTML!")
            Log.d("MaxStream", "🔍 Cerco 'master.m3u8' nel HTML: ${html.contains("master.m3u8")}")
            Log.d("MaxStream", "🔍 Cerco 'sources' nel HTML: ${html.contains("sources")}")
            val m3u8Idx = html.indexOf("master.m3u8")
            if (m3u8Idx >= 0) {
                Log.d("MaxStream", "🔍 Contesto master.m3u8: ${html.substring(maxOf(0,m3u8Idx-100), minOf(html.length,m3u8Idx+100))}")
            }
            return
        }

        Log.d("MaxStream", "✅✅✅ M3U8 TROVATO: $m3u8Url")

        M3u8Helper.generateM3u8(
            name,
            m3u8Url,
            iframeUrl,
            headers = mapOf("referer" to "https://maxstream.video/")
        ).forEach(callback)
        Log.d("MaxStream", "🎉 callback invocata con successo!")
    }
}
