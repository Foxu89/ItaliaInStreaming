package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
}

/** Esegue [blockList] con al massimo [limit] esecuzioni in parallelo. */
suspend fun <T> runLimitedParallel(limit: Int = 4, blockList: List<suspend () -> T>): List<T> {
    val semaphore = Semaphore(limit)
    return coroutineScope {
        blockList.map { block -> async(Dispatchers.IO) { semaphore.withPermit { block() } } }.awaitAll()
    }
}
