package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.DropLoadExtractor
import it.dogior.hadEnough.extractors.MixDropExtractor
import it.dogior.hadEnough.extractors.StreamHGExtractor
import it.dogior.hadEnough.extractors.VidSrcExtractor
import it.dogior.hadEnough.extractors.VixSrcExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class StreamITAExtractors(
    private val scope: CoroutineScope,
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

    fun loadMovieExtractors(imdbId: String) {
        if (isEnabled("dropload")) {
            scope.launch {
                try {
                    val timeout = getTimeoutMs("dropload", 15)
                    withTimeoutOrNull(timeout) {
                        val response = com.lagradost.cloudstream3.app.get(
                            "https://guardahd.stream/index.php?task=set-movie-u&id_imdb=$imdbId"
                        )
                        if (response.isSuccessful) {
                            val html = response.text
                            Regex("""data-link\s*=\s*"(//[^"]*dr0pstream[^"]*|https?://[^"]*dr0pstream[^"]*)""", RegexOption.IGNORE_CASE)
                                .find(html)?.groupValues?.get(1)?.trim()?.let { link ->
                                    val fullLink = if (link.startsWith("//")) "https:$link" else link
                                    DropLoadExtractor().getUrl(fullLink, "https://guardahd.stream/", subtitleCallback, callback)
                                    onSuccess()
                                }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (isEnabled("mixdrop")) {
            scope.launch {
                try {
                    val timeout = getTimeoutMs("mixdrop", 30)
                    withTimeoutOrNull(timeout) {
                        val response = com.lagradost.cloudstream3.app.get(
                            "https://guardahd.stream/index.php?task=set-movie-u&id_imdb=$imdbId"
                        )
                        if (response.isSuccessful) {
                            val html = response.text
                            Regex("""data-link\s*=\s*"(//[^"]*m1xdrop[^"]*|https?://[^"]*m1xdrop[^"]*)""", RegexOption.IGNORE_CASE)
                                .find(html)?.groupValues?.get(1)?.trim()?.let { link ->
                                    val fullLink = if (link.startsWith("//")) "https:$link" else link
                                    MixDropExtractor().getUrl(fullLink, "https://guardahd.stream/", subtitleCallback, callback)
                                    onSuccess()
                                }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (isEnabled("streamhg")) {
            scope.launch {
                try {
                    val timeout = getTimeoutMs("streamhg", 15)
                    withTimeoutOrNull(timeout) {
                        val response = com.lagradost.cloudstream3.app.get(
                            "https://guardahd.stream/index.php?task=set-movie-u&id_imdb=$imdbId"
                        )
                        if (response.isSuccessful) {
                            val html = response.text
                            Regex("""data-link\s*=\s*"(//[^"]*dhcplay[^"]*|https?://[^"]*dhcplay[^"]*)""", RegexOption.IGNORE_CASE)
                                .find(html)?.groupValues?.get(1)?.trim()?.let { link ->
                                    val fullLink = if (link.startsWith("//")) "https:$link" else link
                                    StreamHGExtractor().getUrl(fullLink, "https://guardahd.stream/", subtitleCallback, callback)
                                    onSuccess()
                                }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun loadCommonExtractors(tmdbId: Int, season: Int?, episode: Int?) {
        if (isEnabled("vixsrc")) {
            scope.launch {
                try {
                    val timeout = getTimeoutMs("vixsrc", 15)
                    withTimeoutOrNull(timeout) {
                        val url = if (season == null) "https://vixsrc.to/movie/$tmdbId"
                        else "https://vixsrc.to/tv/$tmdbId/$season/$episode"
                        VixSrcExtractor().getUrl(url, "https://vixsrc.to/", subtitleCallback, callback)
                        onSuccess()
                    }
                } catch (_: Exception) {}
            }
        }

        if (isEnabled("vidsrc")) {
            scope.launch {
                try {
                    val timeout = getTimeoutMs("vidsrc", 15)
                    withTimeoutOrNull(timeout) {
                        val url = if (season == null) "https://vidsrc.ru/movie/$tmdbId"
                        else "https://vidsrc.ru/tv/$tmdbId/$season/$episode"
                        VidSrcExtractor().getUrl(url, "https://vidsrc.ru/", subtitleCallback, callback)
                        onSuccess()
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
