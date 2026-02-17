package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import org.jsoup.Jsoup

class AnimeSaturnExtractor : ExtractorApi() {
    override val name = "AnimeSaturn"
    override val mainUrl = "https://www.animesaturn.cx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val timeout = 60L
        
        // Step 1: Carica la pagina episodio (/ep/...)
        val episodeDoc = app.get(url, timeout = timeout).document
        
        // Step 2: Trova il link alla pagina player (/watch?file=...)
        val watchLink = episodeDoc.select("a[href*='/watch?file=']").attr("href")
        if (watchLink.isBlank()) return
        
        // Step 3: Carica la pagina player
        val watchUrl = fixUrl(watchLink)
        val playerDoc = app.get(watchUrl, timeout = timeout).document
        
        // Step 4: Estrai il video MP4 diretto
        val videoUrl = playerDoc.select("video source").attr("src")
        if (videoUrl.isNotBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "AnimeSaturn",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = mainUrl
                }
            )
            return
        }
        
        // Step 5: Se non trova video, cerca player alternativo
        val altPlayerLink = episodeDoc.select("a[href*='&s=alt']").attr("href")
        if (altPlayerLink.isNotBlank()) {
            val altUrl = fixUrl(altPlayerLink)
            val altDoc = app.get(altUrl, timeout = timeout).document
            val altVideoUrl = altDoc.select("video source").attr("src")
            
            if (altVideoUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "AnimeSaturn (Alt)",
                        url = altVideoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = mainUrl
                    }
                )
            }
        }
    }
}
