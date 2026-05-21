package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.DropLoadExtractor
import it.dogior.hadEnough.extractors.MixDropExtractor
import it.dogior.hadEnough.extractors.StreamHGExtractor
import it.dogior.hadEnough.extractors.VidSrcExtractor
import it.dogior.hadEnough.extractors.VidxGoExtractor
import it.dogior.hadEnough.extractors.VixSrcExtractor
import kotlinx.coroutines.withTimeoutOrNull

class StreamITAExtractors(
    private val subtitleCallback: (SubtitleFile) -> Unit,
    private val callback: (ExtractorLink) -> Unit,
    private val onSuccess: () -> Unit,
    private val sharedPref: SharedPreferences? = null
) {
    private fun isEnabled(name: String, default: Boolean = true): Boolean {
        return sharedPref?.getBoolean(
            StreamITAPlugin.extractorEnabledKey(name), default
        ) ?: default
    }

    private suspend fun getTimeoutMs(name: String, defaultSeconds: Int): Long {
        val saved = sharedPref?.getString(
            StreamITAPlugin.extractorTimeoutKey(name), null
        )
        val seconds = saved?.toLongOrNull()?.takeIf { it > 0 } ?: defaultSeconds.toLong()
        return seconds * 1000L
    }

    suspend fun tryGuardahd(imdbId: String): Boolean {
        if (!isEnabled("dropload") && !isEnabled("mixdrop") && !isEnabled("streamhg")) return false
        val timeout = maxOf(
            getTimeoutMs("dropload", 15),
            getTimeoutMs("mixdrop", 30),
            getTimeoutMs("streamhg", 15)
        )
        var any = false
        withTimeoutOrNull(timeout) {
            val response = com.lagradost.cloudstream3.app.get(
                "https://guardahd.stream/index.php?task=set-movie-u&id_imdb=$imdbId"
            )
            if (response.isSuccessful) {
                val html = response.text
                if (isEnabled("dropload")) {
                    Regex("""data-link\s*=\s*"(//[^"]*dr0pstream[^"]*|https?://[^"]*dr0pstream[^"]*)""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.get(1)?.trim()?.let { link ->
                            val fullLink = if (link.startsWith("//")) "https:$link" else link
                            DropLoadExtractor().getUrl(fullLink, "https://guardahd.stream/", subtitleCallback, callback)
                            onSuccess(); any = true
                        }
                }
                if (isEnabled("mixdrop")) {
                    Regex("""data-link\s*=\s*"(//[^"]*m1xdrop[^"]*|https?://[^"]*m1xdrop[^"]*)""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.get(1)?.trim()?.let { link ->
                            val fullLink = if (link.startsWith("//")) "https:$link" else link
                            MixDropExtractor().getUrl(fullLink, "https://guardahd.stream/", subtitleCallback, callback)
                            onSuccess(); any = true
                        }
                }
                if (isEnabled("streamhg")) {
                    Regex("""data-link\s*=\s*"(//[^"]*dhcplay[^"]*|https?://[^"]*dhcplay[^"]*)""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.get(1)?.trim()?.let { link ->
                            val fullLink = if (link.startsWith("//")) "https:$link" else link
                            StreamHGExtractor().getUrl(fullLink, "https://guardahd.stream/", subtitleCallback, callback)
                            onSuccess(); any = true
                        }
                }
            }
        }
        return any
    }

    suspend fun tryVixSrc(tmdbId: Int, season: Int?, episode: Int?): Boolean {
        if (!isEnabled("vixsrc")) return false
        var any = false
        withTimeoutOrNull(getTimeoutMs("vixsrc", 15)) {
            val url = if (season == null) "https://vixsrc.to/movie/$tmdbId"
            else "https://vixsrc.to/tv/$tmdbId/$season/$episode"
            VixSrcExtractor().getUrl(url, "https://vixsrc.to/", subtitleCallback, callback)
            onSuccess(); any = true
        }
        return any
    }

    suspend fun tryVidSrc(tmdbId: Int, season: Int?, episode: Int?): Boolean {
        if (!isEnabled("vidsrc")) return false
        var any = false
        withTimeoutOrNull(getTimeoutMs("vidsrc", 15)) {
            val url = if (season == null) "https://vidsrc.ru/movie/$tmdbId"
            else "https://vidsrc.ru/tv/$tmdbId/$season/$episode"
            VidSrcExtractor().getUrl(url, "https://vidsrc.ru/", subtitleCallback, callback)
            onSuccess(); any = true
        }
        return any
    }

    suspend fun tryVidxGo(imdbId: String, season: Int?, episode: Int?): Boolean {
        if (!isEnabled("vidxgo")) return false
        var any = false
        withTimeoutOrNull(getTimeoutMs("vidxgo", 20)) {
            val rawId = imdbId.replace("tt", "")
            val url = if (season == null || episode == null) {
                "https://v.vidxgo.co/$rawId"
            } else {
                "https://v.vidxgo.co/$rawId/$season/$episode"
            }
            VidxGoExtractor().getUrl(url, "https://v.vidxgo.co/", subtitleCallback, callback)
            onSuccess(); any = true
        }
        return any
    }
}
