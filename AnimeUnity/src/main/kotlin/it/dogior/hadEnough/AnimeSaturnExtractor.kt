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
            println("🎬 AnimeSaturnExtractor - URL player: $url")
            
            // L'URL è già il link al player (/watch?file=...)
            val watchUrl = fixUrl(url)
            val playerDoc = app.get(watchUrl, timeout = timeout).document
            
            // Estrai il video MP4 diretto
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
                return
            }
            
        } catch (e: Exception) {
            println("❌ Errore extractor: ${e.message}")
        }
    }
}
