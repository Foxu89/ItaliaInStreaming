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
        Log.d("MaxStream", "🔷🔷🔷 getUrl() INIZIO")
        Log.d("MaxStream", "🔷 url ricevuto: $url")
        Log.d("MaxStream", "🔷 referer: $referer")
        Log.d("MaxStream", "🔷 contiene uprots/? ${url.contains("uprots/")}")
        Log.d("MaxStream", "🔷 contiene watch_free/? ${url.contains("watch_free")}")

        // Step 1: se URL è uprots/, carica WebView per bypassare Cloudflare sul redirect
        val finalUrl = if (url.contains("uprots/")) {
            Log.d("MaxStream", "🌐🌐🌐 URL uprots rilevato, avvio WebViewResolver...")
            Log.d("MaxStream", "🌐 timeout=20s, useOkhttp=false")
            Log.d("MaxStream", "🌐 interceptUrl=Regex(/watch_free/)")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""/watch_free/"""),
                useOkhttp = false,
                timeout = 20_000L
            )
            Log.d("MaxStream", "🌐 Chiamo resolveUsingWebView()...")
            val (interceptedRequest, extraRequests) = resolver.resolveUsingWebView(url)
            Log.d("MaxStream", "🌐 resolveUsingWebView() terminato")
            Log.d("MaxStream", "🌐 interceptedRequest: ${interceptedRequest?.url}")
            Log.d("MaxStream", "🌐 extraRequests size: ${extraRequests.size}")
            extraRequests.forEachIndexed { i, r ->
                Log.d("MaxStream", "🌐 extra[$i]: ${r.url}")
            }
            val intercepted = interceptedRequest?.url?.toString()
            Log.d("MaxStream", "📡 URL intercettato: $intercepted")
            if (intercepted == null) {
                Log.e("MaxStream", "❌❌❌ Nessuna richiesta intercettata con /watch_free/!")
                Log.e("MaxStream", "❌ Il WebView potrebbe non aver seguito il redirect")
                Log.e("MaxStream", "❌ O Cloudflare ha bloccato la richiesta")
                return
            }
            Log.d("MaxStream", "✅✅ URL intercettato con successo: $intercepted")
            intercepted
        } else if (url.contains("watch_free")) {
            Log.d("MaxStream", "⚡⚡ URL contiene già watch_free, uso diretto")
            url
        } else {
            Log.e("MaxStream", "❌❌❌ URL non riconosciuto: $url")
            Log.e("MaxStream", "❌ Non contiene uprots/ né watch_free")
            return
        }

        // Step 2: estrai videoId da /watch_free/xxx/{VIDEOID}/hash
        Log.d("MaxStream", "🎯 Estraggo videoId da: $finalUrl")
        val videoIdMatch = Regex("""watch_free/[^/]+/([^/]+)""").find(finalUrl)
        Log.d("MaxStream", "🎯 Regex match: ${videoIdMatch?.groupValues}")
        val videoId = videoIdMatch?.groupValues?.get(1)
        if (videoId == null) {
            Log.e("MaxStream", "❌❌❌ VideoId non estratto da: $finalUrl")
            Log.e("MaxStream", "❌ La regex non ha matchato")
            return
        }
        Log.d("MaxStream", "✅✅ videoId estratto: $videoId")

        // Step 3: fetch iframe e regex M3U8
        val iframeUrl = "https://maxstream.video/emhuih/$videoId"
        Log.d("MaxStream", "🌐🌐 Fetch iframe: $iframeUrl")

        val response = app.get(iframeUrl)
        Log.d("MaxStream", "📡 iframe response code: ${response.code}")
        Log.d("MaxStream", "📡 iframe response url: ${response.url}")
        val html = response.body.string()
        Log.d("MaxStream", "📄 HTML iframe ricevuto, lunghezza: ${html.length}")
        Log.d("MaxStream", "📄 HTML iframe primi 500:\n${html.take(500)}")
        Log.d("MaxStream", "📄 HTML iframe ultimi 500:\n${html.takeLast(500)}")
        Log.d("MaxStream", "🔍 master.m3u8 nell'HTML? ${html.contains("master.m3u8")}")
        Log.d("MaxStream", "🔍 sources nell'HTML? ${html.contains("sources")}")

        val m3u8Match = Regex("""src:\s*"([^"]+master\.m3u8[^"]*)""").find(html)
        Log.d("MaxStream", "🔍 Regex M3U8 match: ${m3u8Match?.groupValues}")
        val m3u8Url = m3u8Match?.groupValues?.get(1)

        if (m3u8Url == null) {
            Log.e("MaxStream", "❌❌❌ M3U8 non trovato nell'HTML!")
            val idx = html.indexOf("master.m3u8")
            if (idx >= 0) {
                Log.d("MaxStream", "🔍 Trovato master.m3u8 a idx=$idx, contesto: ${html.substring(maxOf(0,idx-80), minOf(html.length,idx+80))}")
            }
            return
        }

        Log.d("MaxStream", "✅✅✅ M3U8 TROVATO: $m3u8Url")
        Log.d("MaxStream", "✅ Dominio: ${m3u8Url.substringBefore("/", "")}")
        Log.d("MaxStream", "✅ Inizia con ms-? ${m3u8Url.contains("ms-")}")

        M3u8Helper.generateM3u8(
            name,
            m3u8Url,
            iframeUrl,
            headers = mapOf("referer" to "https://maxstream.video/")
        ).forEach(callback)

        Log.d("MaxStream", "🎉🎉🎉 Callback invocata, done!")
    }
}
