package it.dogior.hadEnough.extractor

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class StreamCenterVidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = false

    companion object {
        private val HTML_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/148.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Referer" to "https://altadefinizione.study/",
            "DNT" to "1",
        )

        private val M3U8_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/148.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to "https://v.vidxgo.co",
            "Referer" to "https://v.vidxgo.co/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "DNT" to "1",
        )

        private fun extractM3u8FromHtml(html: String): String? {
            try {
                val scriptRegex = Regex("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
                val scripts = scriptRegex.findAll(html).map { it.groupValues[1] }.toList()

                val pattern = Regex("""var\s+(\w+)\s*=\s*'([^']*)'\s*,\s*(\w+)\s*=\s*atob\(\s*'([^']*)'""")

                for (script in scripts) {
                    val match = pattern.find(script)
                    if (match != null) {
                        val key = match.groupValues[2]
                        val base64Str = match.groupValues[4]

                        val decoded = Base64.decode(base64Str, Base64.DEFAULT)
                        val decrypted = ByteArray(decoded.size)
                        for (i in decoded.indices) {
                            decrypted[i] = (decoded[i].toInt() xor key[i % key.length].code).toByte()
                        }

                        val decryptedText = String(decrypted, Charsets.UTF_8)

                        val urlPattern = Regex("""currentSrc[^"]*["'](https:[^"']+\.m3u8[^"']*)""")
                        val urlMatch = urlPattern.find(decryptedText)
                        if (urlMatch != null) {
                            return urlMatch.groupValues[1].replace("\\", "")
                        }

                        val altUrlPattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                        val altMatch = altUrlPattern.find(decryptedText)
                        if (altMatch != null) {
                            return altMatch.groupValues[1].replace("\\", "")
                        }
                    }
                }

                return null
            } catch (_: Exception) {
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
        val targetUrl = buildTargetUrl(url)

        val response = app.get(targetUrl, interceptor = CloudflareKiller(), headers = HTML_HEADERS)

        val html = response.text
        val m3u8Url = extractM3u8FromHtml(html)

        if (m3u8Url != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "VidxGo",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = M3U8_HEADERS
                    this.referer = "https://v.vidxgo.co/"
                }
            )
        }
    }

    private fun buildTargetUrl(url: String): String {
        if (url.startsWith(mainUrl)) return url

        val idRegex = Regex("""(\d+)""")
        val idMatch = idRegex.find(url)
        val rawId = idMatch?.value ?: return url

        val seasonEpisodeRegex = Regex("""(?:tt)?\d+[-/](\d+)[-/](\d+)""")
        val seMatch = seasonEpisodeRegex.find(url)

        return if (seMatch != null) {
            val season = seMatch.groupValues[1]
            val episode = seMatch.groupValues[2]
            "$mainUrl/$rawId/$season/$episode"
        } else {
            "$mainUrl/$rawId"
        }
    }
}
