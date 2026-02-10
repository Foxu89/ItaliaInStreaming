package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink

class MySupervideoExtractor : ExtractorApi() {
    override var name = "Supervideo"
    override var mainUrl = "https://supervideo.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Referer" to referer ?: "https://altadefinizionez.sbs/"
            )

            val response = app.get(url, headers = headers)
            val body = response.text
            
            // Cerca pattern comuni per video
            val patterns = listOf(
                """file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
                """src\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
                """sources\s*:\s*\[\s*\{\s*src\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
                """(https?://[^"\s]+\.m3u8[^\s"]*)"""
            )
            
            for (pattern in patterns) {
                val match = Regex(pattern).find(body)
                if (match != null) {
                    var videoUrl = match.groupValues[1]
                    
                    // Decodifica URL se necessario
                    videoUrl = videoUrl.replace("&amp;", "&")
                    
                    if (!videoUrl.startsWith("http")) {
                        videoUrl = if (videoUrl.startsWith("//")) {
                            "https:$videoUrl"
                        } else {
                            "https://$videoUrl"
                        }
                    }
                    
                    // Verifica se è un master playlist M3U8
                    if (videoUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            name,
                            videoUrl,
                            referer = referer ?: url,
                            quality = Qualities.Unknown.value
                        ).forEach(callback)
                        return
                    } else {
                        // Se non è M3U8, prova come link diretto
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Supervideo",
                                url = videoUrl,
                                referer = referer ?: "",
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        return
                    }
                }
            }
            
            // Se non trova M3U8, cerca video diretti
            val directPatterns = listOf(
                """src\s*=\s*["']([^"']+\.mp4[^"']*)["']""",
                """(https?://[^"\s]+\.mp4[^\s"]*)"""
            )
            
            for (pattern in directPatterns) {
                val match = Regex(pattern).find(body)
                if (match != null) {
                    var videoUrl = match.groupValues[1]
                    videoUrl = videoUrl.replace("&amp;", "&")
                    
                    if (!videoUrl.startsWith("http")) {
                        videoUrl = if (videoUrl.startsWith("//")) {
                            "https:$videoUrl"
                        } else {
                            "https://$videoUrl"
                        }
                    }
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Supervideo",
                            url = videoUrl,
                            referer = referer ?: "",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    return
                }
            }
            
        } catch (e: Exception) {
            // Ignora errore
        }
    }
}
