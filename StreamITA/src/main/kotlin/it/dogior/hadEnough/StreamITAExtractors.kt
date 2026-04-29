package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.DropLoadExtractor
import it.dogior.hadEnough.extractors.MixDropExtractor
import it.dogior.hadEnough.extractors.StreamHGExtractor
import it.dogior.hadEnough.extractors.VidSrcExtractor
import it.dogior.hadEnough.extractors.VixSrcExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StreamITAExtractors(
    private val scope: CoroutineScope,
    private val subtitleCallback: (SubtitleFile) -> Unit,
    private val callback: (ExtractorLink) -> Unit,
    private val onSuccess: () -> Unit,
    private val tmdbId: Int? = null,
    private val title: String? = null,
    private val imdbId: String? = null
) {
    private val TAG = "StreamITAExtractors"

    // Raccoglie i link trovati per salvarli nella cache
    private var foundMixdrop: String? = null
    private var foundDropload: String? = null
    private var foundStreamhg: String? = null

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
                            foundDropload = fullLink
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
                            foundMixdrop = fullLink
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
                            foundStreamhg = fullLink
                            StreamHGExtractor().getUrl(fullLink, "https://guardahd.stream/", subtitleCallback, callback)
                            onSuccess()
                        }
                }
            } catch (_: Exception) {}
        }

        // Salva nella cache dopo un breve delay per raccogliere tutti i link
        scope.launch {
            delay(5000) // Aspetta 5 secondi per raccogliere i link
            saveToCacheIfFound()
        }
    }

    fun loadCommonExtractors(tmdbId: Int, season: Int?, episode: Int?) {
        // VixSrc (NON salvato nella cache)
        scope.launch {
            try {
                val url = if (season == null) "https://vixsrc.to/movie/$tmdbId"
                else "https://vixsrc.to/tv/$tmdbId/$season/$episode"
                VixSrcExtractor().getUrl(url, "https://vixsrc.to/", subtitleCallback, callback)
                onSuccess()
            } catch (_: Exception) {}
        }

        // VidSrc (NON salvato nella cache)
        scope.launch {
            try {
                val url = if (season == null) "https://vidsrc.ru/movie/$tmdbId"
                else "https://vidsrc.ru/tv/$tmdbId/$season/$episode"
                VidSrcExtractor().getUrl(url, "https://vidsrc.ru/", subtitleCallback, callback)
                onSuccess()
            } catch (_: Exception) {}
        }
    }

    /**
     * Usa i link dalla cache condivisa (senza chiamare guardahd)
     */
    fun loadFromCache(cachedLinks: StreamITACache.CachedLinks) {
        Log.d(TAG, "Usando link dalla cache per TMDB ID: ${cachedLinks.tmdbId}")

        cachedLinks.droploadLink?.let { link ->
            scope.launch {
                try {
                    DropLoadExtractor().getUrl(link, "https://guardahd.stream/", subtitleCallback, callback)
                    onSuccess()
                } catch (_: Exception) {}
            }
        }

        cachedLinks.mixdropLink?.let { link ->
            scope.launch {
                try {
                    MixDropExtractor().getUrl(link, "https://guardahd.stream/", subtitleCallback, callback)
                    onSuccess()
                } catch (_: Exception) {}
            }
        }

        cachedLinks.streamhgLink?.let { link ->
            scope.launch {
                try {
                    StreamHGExtractor().getUrl(link, "https://guardahd.stream/", subtitleCallback, callback)
                    onSuccess()
                } catch (_: Exception) {}
            }
        }
    }

    private fun saveToCacheIfFound() {
        val tId = tmdbId ?: return
        val name = title ?: return

        if (foundMixdrop != null || foundDropload != null || foundStreamhg != null) {
            Log.d(TAG, "Salvando link nella cache condivisa per TMDB ID: $tId")
            kotlinx.coroutines.runBlocking {
                StreamITACache.saveCachedLinks(
                    StreamITACache.CachedLinks(
                        tmdbId = tId,
                        name = name,
                        imdbId = imdbId,
                        mixdropLink = foundMixdrop,
                        droploadLink = foundDropload,
                        streamhgLink = foundStreamhg,
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            }
        } else {
            Log.d(TAG, "Nessun link trovato per TMDB ID: $tId, cache non salvata")
        }
    }
}
