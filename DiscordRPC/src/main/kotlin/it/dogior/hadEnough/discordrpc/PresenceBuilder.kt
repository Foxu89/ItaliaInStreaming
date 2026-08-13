package it.dogior.hadEnough.discordrpc

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

    private fun formatEpisode(meta: ResultEpisode): String? {
        val season = meta.season
        val episode = meta.episode
        return if (season != null && episode != null) {
            "S${season.takeIf { it in 1..99 } ?: season}E${episode.takeIf { it in 1..999 } ?: episode}"
        } else if (episode != null) {
            "Episode $episode"
        } else {
            null
        }
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
        val episodeLine = meta?.let { formatEpisode(it) }
        val title = meta?.headerName?.takeIf { it.isNotBlank() }
        val subName = meta?.name?.takeIf { it.isNotBlank() && it != meta.headerName }

        // "Watching <name>": il nome è il titolo del contenuto
        val activityName = if (RPCSettings.showTitle && title != null) title else "CloudStream"

        // details = episodio, altrimenti nome dell'episodio (niente duplicati col titolo)
        val detailsLine = when {
            RPCSettings.showEpisode && episodeLine != null -> episodeLine
            RPCSettings.showEpisode && subName != null -> subName
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
                val start = (System.currentTimeMillis() - state.positionMs) / 1000L
                put("timestamps", buildJsonObject {
                    put("start", start)
                })
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