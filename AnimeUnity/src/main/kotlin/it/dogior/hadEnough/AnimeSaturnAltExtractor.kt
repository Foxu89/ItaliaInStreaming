package it.dogior.hadEnough

import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.SubtitleFile

class AnimeSaturnAltExtractor : ExtractorApi() {
    override val name = "AnimeSaturn (Alt)"
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
            
            val altWatchLink = episodeDoc.select("a[href*='&s=alt']").attr("href")
            if (altWatchLink.isBlank()) return
            
            val watchUrl = fixUrl(altWatchLink)
            val playerDoc = app.get(watchUrl, timeout = timeout).document
            
            var videoUrl = ""
            
            val scripts = playerDoc.select("script")
            scripts.forEach { script ->
                val content = script.html()
                if (content.contains("jwplayer")) {
                    val pattern = Regex("file: \"(https?://[^\"]+)\"")
                    val match = pattern.find(content)
                    videoUrl = match?.groupValues?.get(1) ?: ""
                }
            }
            
            if (videoUrl.isBlank()) {
                videoUrl = playerDoc.select("video source").attr("src")
            }
            
            if (videoUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "AnimeSaturn (Alt)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = 1080
                        this.referer = mainUrl
                    }
                )
                return
            }
            
        } catch (e: Exception) {
            // Silently fail
        }
    }
}
