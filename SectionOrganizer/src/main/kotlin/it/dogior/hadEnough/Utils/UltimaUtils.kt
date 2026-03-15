package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object UltimaUtils {
    data class SectionInfo(
        @JsonProperty("name") var name: String,
        @JsonProperty("url") var url: String,
        @JsonProperty("pluginName") var pluginName: String,
        @JsonProperty("enabled") var enabled: Boolean = false,
        @JsonProperty("priority") var priority: Int = 0
    )

    data class ExtensionInfo(
        @JsonProperty("name") var name: String? = null,
        @JsonProperty("sections") var sections: Array<SectionInfo>? = null
    )

    enum class Category { ANIME, MEDIA, NONE }
}

suspend fun <T> retry(times: Int = 3, delayMillis: Long = 1000, block: suspend () -> T): T? {
    repeat(times - 1) { runCatching { return block() }.onFailure { delay(delayMillis) } }
    return runCatching { block() }.getOrNull()
}

data class DomainsParser(
    val moviesdrive: String, @JsonProperty("HDHUB4u") val hdhub4u: String,
    @JsonProperty("4khdhub") val n4khdhub: String, @JsonProperty("MultiMovies") val multiMovies: String,
    val bollyflix: String, @JsonProperty("UHDMovies") val uhdmovies: String, val moviesmod: String,
    val topMovies: String, val hdmovie2: String, val vegamovies: String, val rogmovies: String,
    val luxmovies: String, val xprime: String, val extramovies: String, val dramadrip: String
)

private var cachedDomains: DomainsParser? = null
private const val DOMAINS_URL = "https://raw.githubusercontent.com/phisher98/TVVVV/main/domains.json"

suspend fun getDomains(forceRefresh: Boolean = false): DomainsParser? {
    if (cachedDomains == null || forceRefresh) {
        try {
            cachedDomains = app.get(DOMAINS_URL).parsedSafe()
        } catch (e: Exception) {
            Log.e("getDomains", "Error fetching domains: ${e.message}")
            return null
        }
    }
    return cachedDomains
}

suspend fun <T> runLimitedParallel(limit: Int = 4, blockList: List<suspend () -> T>): List<T> {
    val semaphore = Semaphore(limit)
    return coroutineScope {
        blockList.map { block -> async(Dispatchers.IO) { semaphore.withPermit { block() } } }.awaitAll()
    }
}

fun cleanTitle(title: String): String {
    val parts = title.split(".", "-", "_")
    val qualityTags = listOf("WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV", "CAM", "TS", "R5", "DVDScr", "BRRip", "BDRip", "DVD", "PDTV", "HD")
    val audioTags = listOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD5", "EAC3", "Atmos")
    val subTags = listOf("ESub", "ESubs", "Subs", "MultiSub", "NoSub", "EnglishSub", "HindiSub")
    val codecTags = listOf("x264", "x265", "H264", "HEVC", "AVC")

    val startIndex = parts.indexOfFirst { part -> qualityTags.any { part.contains(it, true) } }
    val endIndex = parts.indexOfLast { part ->
        subTags.any { part.contains(it, true) } || audioTags.any { part.contains(it, true) } || codecTags.any { part.contains(it, true) }
    }

    return when {
        startIndex != -1 && endIndex != -1 && endIndex >= startIndex -> parts.subList(startIndex, endIndex + 1).joinToString(".")
        startIndex != -1 -> parts.subList(startIndex, parts.size).joinToString(".")
        else -> parts.takeLast(3).joinToString(".")
    }
}
