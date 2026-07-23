package it.dogior.hadEnough.extractor

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class StreamCenterVidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = false

    companion object {
        private const val TAG = "VidxGo"

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
            Log.d(TAG, "🔍 extractM3u8FromHtml() — iniziata estrazione M3U8 dall'HTML")
            try {
                val scriptRegex = Regex("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
                val scripts = scriptRegex.findAll(html).map { it.groupValues[1] }.toList()
                Log.d(TAG, "📜 Trovati ${scripts.size} script inline nell'HTML")

                val pattern = Regex("""var\s+(\w+)\s*=\s*'([^']*)'\s*,\s*(\w+)\s*=\s*atob\(\s*'([^']*)'""")

                for ((idx, script) in scripts.withIndex()) {
                    val match = pattern.find(script)
                    if (match != null) {
                        val key = match.groupValues[2]
                        val base64Str = match.groupValues[4]
                        Log.d(TAG, "🔑 Script #$idx — trovato pattern cifrato (key='$key', base64=${base64Str.length} chars)")

                        val decoded = Base64.decode(base64Str, Base64.DEFAULT)
                        Log.d(TAG, "📦 Base64 decodificato → ${decoded.size} bytes")

                        val decrypted = ByteArray(decoded.size)
                        for (i in decoded.indices) {
                            decrypted[i] = (decoded[i].toInt() xor key[i % key.length].code).toByte()
                        }

                        val decryptedText = String(decrypted, Charsets.UTF_8)
                        Log.d(TAG, "🔓 Decriptato → ${decryptedText.length} chars")

                        val urlPattern = Regex("""currentSrc[^"]*["'](https:[^"']+\.m3u8[^"']*)""")
                        val urlMatch = urlPattern.find(decryptedText)
                        if (urlMatch != null) {
                            val raw = urlMatch.groupValues[1]
                            val clean = raw.replace("\\", "")
                            Log.d(TAG, "✅✅✅ M3U8 TROVATO in script #$idx → $clean")
                            return clean
                        }

                        val altUrlPattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                        val altMatch = altUrlPattern.find(decryptedText)
                        if (altMatch != null) {
                            val raw = altMatch.groupValues[1]
                            val clean = raw.replace("\\", "")
                            Log.d(TAG, "⚠️ M3U8 trovato via pattern alternativo in script #$idx → $clean")
                            return clean
                        }

                        Log.d(TAG, "❌ Script #$idx decriptato ma nessun M3U8 trovato nel contenuto")
                    }
                }

                Log.w(TAG, "😱 Nessuno script cifrato conteneva un URL M3U8! Script totali: ${scripts.size}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "💥 ERRORE in extractM3u8FromHtml: ${e.message}", e)
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
        Log.d(TAG, "🚀🚀🚀 getUrl() chiamato — url='$url', referer='$referer'")

        val targetUrl = buildTargetUrl(url)
        Log.d(TAG, "🎯 Target URL costruito: $targetUrl")

        Log.d(TAG, "🌐 Fetch HTML da VidxGo...")
        val response = app.get(targetUrl, headers = HTML_HEADERS)
        Log.d(TAG, "📥 HTML ricevuto — status=${response.code}, size=${response.text.length} chars, url finale=${response.url}")

        val html = response.text
        Log.d(TAG, "📄 HTML (primi 200): ${html.take(200)}")

        val m3u8Url = extractM3u8FromHtml(html)

        if (m3u8Url != null) {
            Log.d(TAG, "🎬 M3U8 estratto con successo! URL: $m3u8Url")

            Log.d(TAG, "🔄 Pre-fetch M3U8 per verificare accessibilità...")
            val preFetchResult = runCatching {
                val m3u8Response = app.get(m3u8Url, headers = M3U8_HEADERS)
                Log.d(TAG, "✅ Pre-fetch M3U8 completato — status=${m3u8Response.code}, size=${m3u8Response.text.length}")
                m3u8Response
            }
            preFetchResult.onFailure { e ->
                Log.e(TAG, "❌ Pre-fetch M3U8 FALLITO: ${e.message}", e)
            }
            preFetchResult.onSuccess { r ->
                if (r.code != 200) Log.w(TAG, "⚠️ Pre-fetch M3U8 ha risposto con status ${r.code} (non 200)")
                else Log.d(TAG, "✅ Pre-fetch M3U8 OK (200)")
            }

            Log.d(TAG, "📡 Invio callback con ExtractorLink...")
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
            Log.d(TAG, "✅✅✅ getUrl() completato con successo! M3U8 fornito al player")
        } else {
            Log.w(TAG, "😱😱😱 Nessun M3U8 trovato nell'HTML — impossibile procedere!")
        }
    }

    private fun buildTargetUrl(url: String): String {
        Log.d(TAG, "🔧 buildTargetUrl() — input: '$url'")
        if (url.startsWith(mainUrl)) {
            Log.d(TAG, "✅ URL già completo, restituito così com'è: $url")
            return url
        }

        val idRegex = Regex("""(\d+)""")
        val idMatch = idRegex.find(url)
        val rawId = idMatch?.value
        if (rawId == null) {
            Log.w(TAG, "⚠️ Impossibile estrarre ID numerico da '$url', restituito tal quale")
            return url
        }
        Log.d(TAG, "🆔 ID estratto: $rawId")

        val seasonEpisodeRegex = Regex("""(?:tt)?\d+[-/](\d+)[-/](\d+)""")
        val seMatch = seasonEpisodeRegex.find(url)

        val result = if (seMatch != null) {
            val season = seMatch.groupValues[1]
            val episode = seMatch.groupValues[2]
            val built = "$mainUrl/$rawId/$season/$episode"
            Log.d(TAG, "📺 URL con stagione/episodio: $built (S${season}E${episode})")
            built
        } else {
            val built = "$mainUrl/$rawId"
            Log.d(TAG, "🎬 URL senza stagione/episodio: $built")
            built
        }

        return result
    }
}
