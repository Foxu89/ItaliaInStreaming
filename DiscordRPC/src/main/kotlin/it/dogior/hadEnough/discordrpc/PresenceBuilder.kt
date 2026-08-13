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
     */
    fun buildActivity(state: PlayerState): JsonArray {
        val meta = state.meta
        val episodeLine = meta?.let { formatEpisode(it) }
        val title = meta?.headerName?.takeIf { it.isNotBlank() }
        val subName = meta?.name?.takeIf { it.isNotBlank() && it != meta.headerName }

        val details = StringBuilder()
        if (RPCSettings.showTitle && title != null) {
            details.append(title)
        }
        if (RPCSettings.showEpisode && episodeLine != null) {
            if (details.isNotEmpty()) details.append(" ")
            details.append(episodeLine)
        }

        // se niente è mostrato, mandiamo comunque qualcosa di minimo
        val activityDetails = details.toString().ifBlank { title ?: "CloudStream" }

        // riga "state": titolo episodio e/o provider, se attivati
        val stateLine = buildList {
            subName?.takeIf { RPCSettings.showEpisode }?.let { add(it) }
            meta?.apiName?.takeIf { it.isNotBlank() && RPCSettings.showProvider }?.let { add(it) }
        }.joinToString(" • ").ifBlank { null }

        val activity = buildJsonObject {
            put("name", "CloudStream")
            put("type", 3)
            put("details", activityDetails)
            stateLine?.let { put("state", it) }

            if (RPCSettings.showTimeElapsed && state.isPlaying) {
                // timestamp UNIX in secondi dell'inizio (ora - posizione corrente)
                val start = (System.currentTimeMillis() - state.positionMs) / 1000L
                put("timestamps", buildJsonObject {
                    put("start", start)
                })
            }

            if (RPCSettings.showPoster && meta?.poster?.isNotBlank() == true) {
                put("assets", buildJsonObject {
                    put("large_image", meta.poster)
                    put("large_text", "CloudStream")
                })
            }

            // button non garantiti con token utente, ma best-effort
            if (meta?.parentId != null) {
                put("buttons", buildJsonArray {
                    val label = if (RPCSettings.showProvider && meta.apiName.isNotBlank()) "Watch on ${meta.apiName}" else "Watch on CloudStream"
                    add(buildJsonObject {
                        put("label", label)
                        put("url", "https://cloudstream.app")
                    })
                })
            }
        }

        return buildJsonArray {
            add(activity)
        }
    }

    fun empty(): JsonArray = buildJsonArray { }
}