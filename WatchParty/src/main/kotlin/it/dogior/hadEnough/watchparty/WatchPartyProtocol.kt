package it.dogior.hadEnough.watchparty

import kotlinx.serialization.Serializable

/**
 * Informazioni sulla sorgente in riproduzione, condivise da Host a Guest
 * quando la stanza viene creata o quando l'host cambia episodio/mirror.
 */
@Serializable
data class MediaInfo(
    val url: String,
    val title: String? = null,
    val position: Long = 0L,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val quality: Int = 0,
    val type: String? = null,
)

/**
 * Unico envelope scambiato sul canale WebSocket.
 * Il server di relay non lo interpreta: si limita a inoltrarlo all'altro
 * peer nella stessa stanza (vedi WatchPartyServer/worker.js).
 *
 * type possibili:
 *  - "PEER_JOINED" / "PEER_LEFT"  (generati dal server, non dal client)
 *  - "SYNC_REQUEST" / "SYNC_STATE"
 *  - "PLAY" / "PAUSE"
 *  - "SEEK"        (position, playing)
 *  - "BUFFERING" / "READY"
 *  - "CHANGE_SOURCE" (url/title/referer/headers/quality/position/playing)
 *  - "LEAVE_ROOM"
 */
@Serializable
data class WatchPartyMessage(
    val type: String,
    val position: Long? = null,
    val playing: Boolean? = null,
    val url: String? = null,
    val title: String? = null,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val quality: Int = 0,
)
