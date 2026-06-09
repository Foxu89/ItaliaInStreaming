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
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 30_000L
            )
            val response = app.get(url, referer = referer ?: url, interceptor = resolver)
            val m3u8Url = response.url

            if (m3u8Url.isNotEmpty() && m3u8Url.contains(".m3u8")) {
                Log.d("MaxStream", "M3U8: $m3u8Url")
                M3u8Helper.generateM3u8(
                    name, m3u8Url, url,
                    headers = mapOf("referer" to "https://maxstream.video/")
                ).forEach(callback)
                return
            }
        } catch (e: Exception) {
            Log.d("MaxStream", "WebViewResolver fallito: ${e.message}")
        }
    }
}
