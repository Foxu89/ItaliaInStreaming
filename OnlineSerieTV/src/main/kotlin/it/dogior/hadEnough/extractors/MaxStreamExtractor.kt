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
        // WebViewResolver: bypassa Cloudflare e intercetta il redirect
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""/watch_free/"""),
                useOkhttp = false,
                timeout = 25_000L
            )
            val response = app.get(url, referer = referer ?: url, interceptor = resolver)
            val maxweUrl = response.url

            if (maxweUrl.isNotEmpty() && maxweUrl.contains("/watch_free/")) {
                Log.d("MaxStream", "maxweUrl: $maxweUrl")
                val videoId = Regex("""watch_free/[^/]+/([^/]+)""")
                    .find(maxweUrl)?.groupValues?.get(1)
                if (videoId != null) {
                    Log.d("MaxStream", "videoId: $videoId")
                    val iframeUrl = "https://maxstream.video/emhuih/$videoId"

                    // Secondo WebViewResolver: bypassa Cloudflare su maxstream.video,
                    // intercetta la richiesta M3U8 fatta da videojs
                    val resolver2 = WebViewResolver(
                        interceptUrl = Regex("""\.m3u8"""),
                        useOkhttp = false,
                        timeout = 25_000L
                    )
                    val response2 = app.get(iframeUrl, interceptor = resolver2)
                    val m3u8Url = response2.url

                    if (m3u8Url.isNotEmpty() && m3u8Url.contains(".m3u8")) {
                        Log.d("MaxStream", "M3U8 intercettato: $m3u8Url")
                        M3u8Helper.generateM3u8(
                            name, m3u8Url, iframeUrl,
                            headers = mapOf("referer" to "https://maxstream.video/")
                        ).forEach(callback)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("MaxStream", "WebViewResolver fallito: ${e.message}")
        }

        // Fallback: JS unpacking diretto (se Cloudflare non c'e')
        Log.d("MaxStream", "Fallback JS unpacking...")
        fallbackJsUnpack(url, referer, callback)
    }

    private suspend fun fallbackJsUnpack(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "User-Agent" to "Mozilla/5.0 (Windows NT 6.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.110 Safari/537.36",
            "Accept-Language" to "en-US;q=0.5,en;q=0.3",
            "Cache-Control" to "max-age=0",
            "Upgrade-Insecure-Requests" to "1"
        )
        val response = app.get(url, headers = headers, timeout = 10_000)
        val responseBody = response.body.string()

        val script =
            "eval(function(p,a,c,k,e,d)" + responseBody.substringAfter("<script type='text/javascript'>eval(function(p,a,c,k,e,d)")
                .substringBefore(")))") + ")))"
        val unpackedScript = com.lagradost.cloudstream3.utils.getAndUnpack(script)
        val src = unpackedScript.substringAfter("src:\"").substringBefore("\",")
        Log.d("MaxStream", "Script: $src")
        callback.invoke(
            com.lagradost.cloudstream3.utils.newExtractorLink(
                source = name,
                name = name,
                url = src,
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
            ){
                this.referer = referer ?: ""
                this.quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
            }
        )
    }
}
