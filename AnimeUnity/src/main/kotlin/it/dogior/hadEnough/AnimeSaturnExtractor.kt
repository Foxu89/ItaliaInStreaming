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
            val altWatchLink = episodeDoc.select("a[href*='&s=alt']").attr("href")
            
            suspend fun processPlayer(playerUrl: String, playerName: String): Boolean {
                if (playerUrl.isBlank()) return false
                
                val watchUrl = fixUrl(playerUrl)
                val playerDoc = app.get(watchUrl, timeout = timeout).document
                
                var videoUrl = playerDoc.select("video source").attr("src")
                
                if (videoUrl.isBlank()) {
                    val scripts = playerDoc.select("script")
                    scripts.forEach { script ->
                        val content = script.html()
                        if (content.contains("jwplayer")) {
                            val pattern = Regex("file: \"(https?://[^\"]+)\"")
                            val match = pattern.find(content)
                            videoUrl = match?.groupValues?.get(1) ?: ""
                        }
                    }
                }
                
                if (videoUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = playerName,
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = 1080
                            this.referer = mainUrl
                        }
                    )
                    return true
                }
                
                return false
            }
            
            if (processPlayer(watchLink, "AnimeSaturn")) {
                return
            }
            
            if (processPlayer(altWatchLink, "AnimeSaturn (Alt)")) {
                return
            }
            
        } catch (e: Exception) {
        }
    }
}
