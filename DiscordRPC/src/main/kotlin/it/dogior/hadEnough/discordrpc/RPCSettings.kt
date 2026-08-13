package it.dogior.hadEnough.discordrpc

import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Persistenza delle impostazioni del plugin, riusando lo stesso pattern di
 * WatchPartyConsent (CloudStreamApp.getKey/setKey): i valori finiscono nelle
 * SharedPreferences dell'app, a chiavi uniche per modulo.
 */
object RPCSettings {

    private const val KEY_ENABLED = "drpc_enabled"
    private const val KEY_TOKEN = "drpc_token"
    private const val KEY_SHOW_TITLE = "drpc_show_title"
    private const val KEY_SHOW_EPISODE = "drpc_show_episode"
    private const val KEY_SHOW_PROVIDER = "drpc_show_provider"
    private const val KEY_SHOW_TIME = "drpc_show_time"
    private const val KEY_SHOW_POSTER = "drpc_show_poster"
    private const val KEY_USERNAME = "drpc_username"

    var enabled: Boolean
        get() = CloudStreamApp.getKey<Boolean>(KEY_ENABLED) ?: true
        set(value) = CloudStreamApp.setKey(KEY_ENABLED, value)

    var token: String
        get() = CloudStreamApp.getKey<String>(KEY_TOKEN).orEmpty()
        set(value) = CloudStreamApp.setKey(KEY_TOKEN, value)

    var showTitle: Boolean
        get() = CloudStreamApp.getKey<Boolean>(KEY_SHOW_TITLE) ?: true
        set(value) = CloudStreamApp.setKey(KEY_SHOW_TITLE, value)

    var showEpisode: Boolean
        get() = CloudStreamApp.getKey<Boolean>(KEY_SHOW_EPISODE) ?: true
        set(value) = CloudStreamApp.setKey(KEY_SHOW_EPISODE, value)

    var showProvider: Boolean
        get() = CloudStreamApp.getKey<Boolean>(KEY_SHOW_PROVIDER) ?: false
        set(value) = CloudStreamApp.setKey(KEY_SHOW_PROVIDER, value)

    var showTimeElapsed: Boolean
        get() = CloudStreamApp.getKey<Boolean>(KEY_SHOW_TIME) ?: true
        set(value) = CloudStreamApp.setKey(KEY_SHOW_TIME, value)

    var showPoster: Boolean
        get() = CloudStreamApp.getKey<Boolean>(KEY_SHOW_POSTER) ?: true
        set(value) = CloudStreamApp.setKey(KEY_SHOW_POSTER, value)

    /** Nome utente Discord letto dall'op 0 READY, per mostrarlo nella UI. */
    var username: String?
        get() = CloudStreamApp.getKey<String>(KEY_USERNAME)
        set(value) = value?.let { CloudStreamApp.setKey(KEY_USERNAME, it) } ?: CloudStreamApp.setKey(KEY_USERNAME, "")

    fun clearToken() {
        CloudStreamApp.removeKey(KEY_TOKEN)
        CloudStreamApp.removeKey(KEY_USERNAME)
    }
}