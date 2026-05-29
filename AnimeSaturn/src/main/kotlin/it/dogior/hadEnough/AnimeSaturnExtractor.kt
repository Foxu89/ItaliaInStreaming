package it.dogior.hadEnough

import com.lagradost.api.Log
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

        Log.d(TAG, "🔗 getUrl() → url: '$url', referer: '$referer'")

        try {
            val episodeDoc = app.get(url, timeout = timeout).document
            Log.d(TAG, "📄 getUrl() → documento episodio ottenuto, titolo: '${episodeDoc.title()}'")

            val watchLink = episodeDoc.select("a[href*='/watch?file=']").attr("href")
            Log.d(TAG, "👁️ getUrl() → watchLink trovato: '$watchLink'")

            if (watchLink.isBlank()) {
                Log.d(TAG, "❌ getUrl() → watchLink vuoto, esco")
                return
            }

            val watchUrl = fixUrl(watchLink)
            Log.d(TAG, "🌐 getUrl() → watchUrl: '$watchUrl'")

            val playerDoc = app.get(watchUrl, timeout = timeout).document
            Log.d(TAG, "📄 getUrl() → documento player ottenuto, titolo: '${playerDoc.title()}'")

            val videoUrl = playerDoc.select("video source").attr("src")
            Log.d(TAG, "🎬 getUrl() → videoUrl dal player principale: '$videoUrl'")

            if (videoUrl.isNotBlank()) {
                Log.d(TAG, "✅ getUrl() → video trovato nel player principale, invoco callback")
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
                Log.d(TAG, "✅ getUrl() → callback invocato con successo (player principale), esco")
                return
            }

            Log.d(TAG, "⚠️ getUrl() → video NON trovato nel player principale, provo player alternativo")

            val altPlayerLink = episodeDoc.select("a[href*='&s=alt']").attr("href")
            Log.d(TAG, "🔀 getUrl() → altPlayerLink: '$altPlayerLink'")

            if (altPlayerLink.isNotBlank()) {
                val altUrl = fixUrl(altPlayerLink)
                Log.d(TAG, "🌐 getUrl() → altUrl: '$altUrl'")

                val altDoc = app.get(altUrl, timeout = timeout).document
                Log.d(TAG, "📄 getUrl() → documento player alternativo ottenuto, titolo: '${altDoc.title()}'")

                val altVideoUrl = altDoc.select("video source").attr("src")
                Log.d(TAG, "🎬 getUrl() → altVideoUrl: '$altVideoUrl'")

                if (altVideoUrl.isNotBlank()) {
                    Log.d(TAG, "✅ getUrl() → video trovato nel player alternativo, invoco callback")
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
                    Log.d(TAG, "✅ getUrl() → callback invocato con successo (player alternativo), esco")
                    return
                } else {
                    Log.d(TAG, "❌ getUrl() → video NON trovato nemmeno nel player alternativo")
                }
            } else {
                Log.d(TAG, "⚠️ getUrl() → nessun link player alternativo trovato")
            }

        } catch (e: Exception) {
            Log.d(TAG, "💥 getUrl() → eccezione: ${e.message}")
        }

        Log.d(TAG, "🏁 getUrl() → fine funzione, nessun video trovato")
    }
}
