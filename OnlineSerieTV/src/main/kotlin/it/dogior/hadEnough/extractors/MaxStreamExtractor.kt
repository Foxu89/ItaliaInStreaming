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
        val videoId = Regex("""watch_free/[^/]+/([^/]+)""")
            .find(url)?.groupValues?.get(1)
        if (videoId == null) {
            Log.e("MaxStream", "Video ID non estratto da: $url")
            return
        }

        val iframeUrl = "https://maxstream.video/emhuih/$videoId"
        Log.d("MaxStream", "Fetching: $iframeUrl")

        val html = app.get(iframeUrl).body.string()

        val m3u8Url = Regex("""src:\s*"([^"]+master\.m3u8[^"]*)""")
            .find(html)?.groupValues?.get(1)

        if (m3u8Url == null) {
            Log.e("MaxStream", "M3U8 non trovato nell'HTML")
            return
        }

        Log.d("MaxStream", "M3U8: $m3u8Url")

        M3u8Helper.generateM3u8(
            name,
            m3u8Url,
            iframeUrl,
            headers = mapOf("referer" to "https://maxstream.video/")
        ).forEach(callback)
    }
}
