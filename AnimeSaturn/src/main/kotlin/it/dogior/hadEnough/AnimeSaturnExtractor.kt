package it.dogior.hadEnough

import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.SubtitleFile

class AnimeSaturnExtractor : ExtractorApi() {
    override val name = "AnimeSaturn"
    override val mainUrl = "https://www.animesaturn.cx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val timeout = 60L

        try {
            val episodeDoc = app.get(url, timeout = timeout).document
            val watchLink = episodeDoc.select("a[href*='/watch?file=']").attr("href")
            if (watchLink.isBlank()) return

            val baseWatchUrl = fixUrl(watchLink)

            // ── Main: video source ──
            try {
                val playerDoc = app.get(baseWatchUrl, timeout = timeout).document
                val videoUrl = playerDoc.select("video source").attr("src")
                if (videoUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "AnimeSaturn",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = 1080
                            this.referer = mainUrl
                        }
                    )
                }
            } catch (_: Exception) {}

            // ── Alt: jwplayer file ──
            try {
                val altUrl = "$baseWatchUrl&s=alt"
                val altDoc = app.get(altUrl, timeout = timeout).document
                val altVideoUrl = altDoc.select("script").mapNotNull { script ->
                    Regex("""file:\s*"([^"]+)""").find(script.html())?.groupValues?.get(1)
                }.firstOrNull()

                if (!altVideoUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "AnimeSaturn (Alt)",
                            url = altVideoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = 1080
                            this.referer = mainUrl
                        }
                    )
                }
            } catch (_: Exception) {}

        } catch (_: Exception) {}
    }
}
