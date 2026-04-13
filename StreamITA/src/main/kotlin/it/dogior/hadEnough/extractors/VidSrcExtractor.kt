package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup

class VidSrcExtractor : ExtractorApi() {
    override val mainUrl = "https://vidsrc.ru"
    override val name = "VidSrc"
    override val requiresReferer = true
    private val TAG = "VidSrcExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "Processing URL: $url")

        try {
            val playlistUrl = getPlaylistLink(url, referer)
            Log.i(TAG, "Final M3U8 URL: $playlistUrl")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "VidSrc",
                    url = playlistUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract video URL: ${e.message}")
            // Provo un approccio alternativo con WebView come fallback
            tryFallbackMethod(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun getPlaylistLink(url: String, referer: String?): String {
        val headers = buildHeaders(url, referer)

        // 1. Carica la pagina dell'iframe
        val response = app.get(url, headers = headers)
        val document = response.document
        val html = response.text

        // 2. Cerca l'URL del master playlist nei vari formati possibili
        // Formato 1: JSON nella pagina
        val jsonPatterns = listOf(
            Regex("""masterPlaylist["']?\s*:\s*["']([^"']+)["']"""),
            Regex("""file:\s*["']([^"']*\.m3u8[^"']*)["']"""),
            Regex("""src:\s*["']([^"']*\.m3u8[^"']*)["']"""),
            Regex("""source:\s*["']([^"']*\.m3u8[^"']*)["']""")
        )

        for (pattern in jsonPatterns) {
            pattern.find(html)?.groupValues?.get(1)?.let { found ->
                Log.d(TAG, "Found M3U8 via pattern: $found")
                return found
            }
        }

        // Formato 2: JSON in script tag
        val scriptTags = document.select("script")
        for (script in scriptTags) {
            val scriptContent = script.data()
            if (scriptContent.contains("masterPlaylist") || scriptContent.contains(".m3u8")) {
                try {
                    // Cerco di estrarre un JSON valido
                    val jsonPattern = Regex("""\{[^{}]*"masterPlaylist"[^{}]*\}""")
                    jsonPattern.find(scriptContent)?.value?.let { jsonStr ->
                        val json = JSONObject(jsonStr)
                        json.optString("masterPlaylist")?.takeIf { it.isNotEmpty() }?.let {
                            Log.d(TAG, "Found M3U8 in JSON: $it")
                            return it
                        }
                    }

                    // Cerco pattern window.xxx = {...}
                    val windowPattern = Regex("""window\.\w+\s*=\s*(\{[^}]+\})""")
                    windowPattern.find(scriptContent)?.groupValues?.get(1)?.let { jsonStr ->
                        try {
                            val json = JSONObject(jsonStr)
                            json.optString("masterPlaylist")?.takeIf { it.isNotEmpty() }?.let {
                                Log.d(TAG, "Found M3U8 in window object: $it")
                                return it
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        // Formato 3: Video tag
        document.select("video source").firstOrNull()?.attr("src")?.takeIf {
            it.contains(".m3u8")
        }?.let {
            Log.d(TAG, "Found M3U8 in video source: $it")
            return it
        }

        // Formato 4: Iframe annidato
        val iframe = document.select("iframe").firstOrNull()?.attr("src")
        if (iframe != null && iframe != url) {
            Log.d(TAG, "Following nested iframe: $iframe")
            return getPlaylistLink(iframe, url)
        }

        throw Exception("No M3U8 URL found in page")
    }

    private suspend fun tryFallbackMethod(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fallback: Prova a usare l'API alternativa se disponibile
        try {
            val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
            val imdbId = Regex("""/(?:movie|tv)/(tt\d+)""").find(url)?.groupValues?.get(1)

            val seasonEpisode = Regex("""/tv/[^/]+/(\d+)/(\d+)""").find(url)
            val season = seasonEpisode?.groupValues?.get(1)
            val episode = seasonEpisode?.groupValues?.get(2)

            val apiUrl = when {
                imdbId != null -> "https://vidsrc.ru/movie/$imdbId"
                season != null && episode != null -> "https://vidsrc.ru/tv/$tmdbId/$season/$episode"
                tmdbId != null -> "https://vidsrc.ru/movie/$tmdbId"
                else -> null
            }

            if (apiUrl != null && apiUrl != url) {
                Log.d(TAG, "Trying alternative URL: $apiUrl")
                val playlistUrl = getPlaylistLink(apiUrl, referer)
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "VidSrc (Fallback)",
                        url = playlistUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback also failed: ${e.message}")
        }
    }

    private fun buildHeaders(url: String, referer: String?): Map<String, String> {
        val host = try {
            java.net.URI(url).host
        } catch (_: Exception) {
            "vidsrc.ru"
        }

        return mutableMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Alt-Used" to host,
            "Connection" to "keep-alive",
            "Host" to host,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Upgrade-Insecure-Requests" to "1",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
        ).apply {
            referer?.let { put("Referer", it) }
        }
    }
}
