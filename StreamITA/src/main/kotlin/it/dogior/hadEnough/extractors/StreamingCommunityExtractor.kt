package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class StreamingCommunityExtractor(
    private val sourceName: String = "StreamingCommunity",
    private val displayName: String = "StreamingCommunity",
) : ExtractorApi() {
    override val mainUrl = "https://streamingunity.dog"
    override val name = "StreamingCommunity"
    override val requiresReferer = false

    private val headers = mutableMapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // 1. Carica la pagina iframe
        val iframeHtml = app.get(url, headers = headers).text
        val doc = org.jsoup.Jsoup.parse(iframeHtml)
        val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: return

        // 2. Estrai il master playlist da VixCloud
        val vixcloudHtml = app.get(iframeSrc, headers = headers).text
        val vixcloudDoc = org.jsoup.Jsoup.parse(vixcloudHtml)
        val script = vixcloudDoc.select("script")
            .firstOrNull { it.data().contains("masterPlaylist") }
            ?.data()
            ?.replace("\n", "\t")
            ?: return

        val json = JSONObject(sanitizeScript(script))
        val masterPlaylist = json.getJSONObject("masterPlaylist")
        val params = masterPlaylist.getJSONObject("params")
        val token = params.getString("token")
        val expires = params.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")
        val finalUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&token=$token&expires=$expires"
        } else {
            "$playlistUrl?token=$token&expires=$expires"
        }

        callback(
            newExtractorLink(
                source = sourceName,
                name = displayName,
                url = if (json.getBoolean("canPlayFHD")) "$finalUrl&h=1" else finalUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.headers = this@StreamingCommunityExtractor.headers
            }
        )
    }

    private fun sanitizeScript(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1)
        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()
        val jsonObjects = keys.zip(parts).map { (key, value) ->
            val cleaned = value
                .replace(";", "")
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()
            "\"$key\": $cleaned"
        }
        return "{\n${jsonObjects.joinToString(",\n")}\n}".replace("'", "\"")
    }
}
