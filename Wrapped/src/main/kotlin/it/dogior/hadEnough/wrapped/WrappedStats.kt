package it.dogior.hadEnough.wrapped

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.RESULT_WATCH_STATE_DATA
import com.lagradost.cloudstream3.utils.VIDEO_POS_DUR

/** Risultato del calcolo, pronto per essere mostrato/disegnato sulla card. */
data class WrappedStats(
    val profileName: String,
    val totalWatchedMs: Long,
    val moviesWatched: Int,
    val seriesWatched: Int,
    val rank: WrappedRank,
)

/**
 * Legge solo dati già salvati localmente da CloudStream (DataStoreHelper):
 * nessuna rete, nessun provider, nessun nuovo permesso. Pensata per essere
 * chiamata una volta sola quando l'utente apre la schermata Wrapped, non
 * in background: è un semplice snapshot di quello che c'è già su disco.
 *
 * Due fonti diverse per due scopi diversi:
 *  - "tempo totale": somma su VIDEO_POS_DUR, la chiave che il player
 *    aggiorna per OGNI film/episodio su cui hai premuto play, a prescindere
 *    da liste/preferiti. La fonte più completa che CloudStream tenga, e
 *    l'unica che continua a crescere anche senza toccare la libreria.
 *  - "film/serie guardati": contati sulla Libreria (RESULT_WATCH_STATE_DATA,
 *    i contenuti con uno stato tipo "Watching"/"Completed"), perché è
 *    l'unico posto dove CloudStream salva anche il TIPO (film o serie) per
 *    ogni voce. VIDEO_POS_DUR non lo sa: un id lì è solo un numero, senza
 *    sapere se è un film o un episodio di una serie.
 *  Significa che "tempo totale" include tutto ciò che hai mai guardato,
 *  mentre i due contatori riflettono solo ciò che hai aggiunto alla
 *  libreria — onesto da dire all'utente, non un bug.
 */
object WrappedStats {

    fun compute(): WrappedStats {
        val profileName = currentProfileName()
        val totalMs = computeTotalWatchedMs()
        val (movies, series) = computeLibraryCounts()
        val rank = WrappedRank.forTotalMs(totalMs)
        return WrappedStats(
            profileName = profileName,
            totalWatchedMs = totalMs,
            moviesWatched = movies,
            seriesWatched = series,
            rank = rank,
        )
    }

    private fun currentProfileName(): String {
        val context = CloudStreamApp.context ?: return "Guest"
        val accounts = DataStoreHelper.getAccounts(context)
        val active = accounts.find { it.keyIndex == DataStoreHelper.selectedKeyIndex }
        return active?.name ?: DataStoreHelper.getDefaultAccount(context).name
    }

    /** Somma di min(position, duration) su ogni id mai salvato in VIDEO_POS_DUR. */
    private fun computeTotalWatchedMs(): Long {
        val folder = "${DataStoreHelper.currentAccount}/$VIDEO_POS_DUR"
        val keys = CloudStreamApp.getKeys(folder) ?: return 0L
        val prefix = "$folder/"
        return keys.sumOf { fullKey ->
            val id = fullKey.removePrefix(prefix)
            val posDur = CloudStreamApp.getKey<DataStoreHelper.PosDur>(folder, id)
            posDur?.let { minOf(it.position, it.duration).coerceAtLeast(0L) } ?: 0L
        }
    }

    /** Conta film vs serie tra i contenuti presenti in Libreria (hanno un tipo salvato). */
    private fun computeLibraryCounts(): Pair<Int, Int> {
        val folder = "${DataStoreHelper.currentAccount}/$RESULT_WATCH_STATE_DATA"
        val keys = CloudStreamApp.getKeys(folder) ?: return 0 to 0
        val prefix = "$folder/"
        var movies = 0
        var series = 0
        keys.forEach { fullKey ->
            val id = fullKey.removePrefix(prefix).toIntOrNull() ?: return@forEach
            val data = DataStoreHelper.getBookmarkedData(id) ?: return@forEach
            // "type" è un var (campo mutabile su BookmarkedData): copiato in un
            // val locale perché lo smart-cast a non-null non funziona su un var.
            val type = data.type ?: return@forEach
            if (type.isMovieType()) movies++ else series++
        }
        return movies to series
    }
}
