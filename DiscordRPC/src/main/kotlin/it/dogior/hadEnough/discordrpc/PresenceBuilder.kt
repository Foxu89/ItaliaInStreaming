package it.dogior.hadEnough.discordrpc

import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Costruisce la lista "activities" dell'op 3 della presenza in base alle
 * impostazioni attive e ai metadati del contenuto in riproduzione.
 *
 * type 3 = Watching. details/state/timestamps/assets rispettano i toggle
 * dell'utente (Show Title / Show Episode / Show Provider / Show Time Elapsed /
 * Show Poster).
 */
object PresenceBuilder {

    /** Stato del contenuto in riproduzione, già estratto dal player. */
    data class PlayerState(
        val meta: ResultEpisode?,
        val positionMs: Long,
        val isPlaying: Boolean,
    )

    /** true se il contenuto è una serie (anche quando tvType è null). */
    private fun isSeries(meta: ResultEpisode): Boolean {
        val tvType = meta.tvType
        if (tvType != null) return !tvType.isMovieType()
        return meta.season != null || meta.episode != null
    }

    /** Righe "S:<n> & EP:<n>" leggibili al posto di "S01E01". */
    private fun seasonEpisodeLine(meta: ResultEpisode): String? {
        val season = meta.season
        val episode = meta.episode
        return when {
            season != null && episode != null && episode > 0 -> "S:$season & EP:$episode"
            episode != null && episode > 0 -> "EP:$episode"
            else -> null
        }
    }

    /**
     * Riga di dettaglio per le serie: "<nome episodio> - S:<n> & EP:<n>",
     * mai "Episode 0". Se mancano nome e numeri, niente riga.
     */
    private fun formatEpisodeLine(meta: ResultEpisode): String? {
        val name = meta.name?.takeIf { it.isNotBlank() && it != meta.headerName }
        val seLine = seasonEpisodeLine(meta)
        return when {
            name != null && seLine != null -> "$name - $seLine"
            name != null -> name
            seLine != null -> seLine
            else -> null
        }
    }

    /** La posizione del player è in ms; se la unità è palesemente sbagliata
     *  (valore > 24h) la trattiamo come se fosse già in secondi. */
    private fun normalizedPosition(positionMs: Long): Long {
        if (positionMs <= 0L) return 0L
        return if (positionMs > 86_400_000L) positionMs / 1000L else positionMs
    }

    /**
     * Il JSON della singola activity (elemento di activities[]) oppure null se
     * non c'è nulla di significativo da mostrare (es. player vuoto).
     *
     * Modello = Navidrome/Navicord (l'unica ricetta verificata che renderizza su
     * mobile): name libero (qui il titolo, così il profilo mostra "Watching <titolo>"),
     * details/state, timestamps, assets, application_id. NIENTE buttons: con un
     * token utente non vengono mai mostrati e possono far scartare la presenza.
     */
    fun buildActivity(state: PlayerState): JsonArray {
        val meta = state.meta
        val title = meta?.headerName?.takeIf { it.isNotBlank() }

        // "Watching <name>": il nome è il titolo del contenuto
        val activityName = if (RPCSettings.showTitle && title != null) title else "CloudStream"

        val isSeries = meta?.let { isSeries(it) } == true

        // details: serie -> "<episodio> - S:<n> & EP:<n>"; film -> il titolo (mai "Episode 0")
        val detailsLine = if (meta == null) null else when {
            RPCSettings.showEpisode && isSeries -> formatEpisodeLine(meta)
            RPCSettings.showEpisode && !isSeries && RPCSettings.showTitle -> title
            else -> null
        }

        // state = provider
        val stateLine = meta?.apiName?.takeIf { it.isNotBlank() && RPCSettings.showProvider }

        val activity = buildJsonObject {
            put("name", activityName)
            put("type", 3)
            detailsLine?.let { put("details", it) }
            stateLine?.let { put("state", it) }

            // SENZA application_id il client scarta l'attività di un token utente:
            // è l'id di un'app Discord con Rich Presence che rende visibile la presenza.
            val appId = RPCSettings.applicationId
            if (appId.isNotBlank()) put("application_id", appId)

            if (RPCSettings.showTimeElapsed && state.isPlaying) {
                // timestamp UNIX in secondi dell'inizio (ora - posizione corrente)
                val position = normalizedPosition(state.positionMs)
                if (position > 0L) {
                    val start = (System.currentTimeMillis() - position) / 1000L
                    put("timestamps", buildJsonObject {
                        put("start", start)
                    })
                }
            }

            if (RPCSettings.showPoster && meta?.poster?.isNotBlank() == true) {
                // URL esterni NON sono accettati in large_image: li convertiamo in
                // asset path ("mp:external/..."). Se non registrabile, niente immagine.
                val large = DiscordAssets.externalPath(meta.poster!!)
                if (large != null) {
                    put("assets", buildJsonObject {
                        put("large_image", large)
                        put("large_text", "CloudStream")
                    })
                }
            }
        }

        return buildJsonArray {
            add(activity)
        }
    }

    fun empty(): JsonArray = buildJsonArray { }
}