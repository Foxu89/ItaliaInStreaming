package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.DropLoadExtractor
import it.dogior.hadEnough.extractors.MixDropExtractor
import it.dogior.hadEnough.extractors.StreamHGExtractor
import it.dogior.hadEnough.extractors.VidSrcExtractor
import it.dogior.hadEnough.extractors.VixSrcExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class StreamITAExtractors(
    private val scope: CoroutineScope,
    private val subtitleCallback: (SubtitleFile) -> Unit,
    private val callback: (ExtractorLink) -> Unit,
    private val onSuccess: () -> Unit
) {
    fun loadMovieExtractors(imdbId: String) {
        // DropLoad
        scope.launch {
            try {
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
            } catch (_: Exception) {}
        }

        // MixDrop
        scope.launch {
            try {
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
            } catch (_: Exception) {}
        }

        // StreamHG
        scope.launch {
            try {
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
            } catch (_: Exception) {}
        }
    }

    fun loadCommonExtractors(tmdbId: Int, season: Int?, episode: Int?) {
        // VixSrc
        scope.launch {
            try {
                val url = if (season == null) "https://vixsrc.to/movie/$tmdbId"
                else "https://vixsrc.to/tv/$tmdbId/$season/$episode"
                VixSrcExtractor().getUrl(url, "https://vixsrc.to/", subtitleCallback, callback)
                onSuccess()
            } catch (_: Exception) {}
        }

        // VidSrc
        scope.launch {
            try {
                val url = if (season == null) "https://vidsrc.ru/movie/$tmdbId"
                else "https://vidsrc.ru/tv/$tmdbId/$season/$episode"
                VidSrcExtractor().getUrl(url, "https://vidsrc.ru/", subtitleCallback, callback)
                onSuccess()
            } catch (_: Exception) {}
        }
    }
}
