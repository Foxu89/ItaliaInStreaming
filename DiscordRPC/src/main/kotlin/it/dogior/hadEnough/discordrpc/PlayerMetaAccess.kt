package it.dogior.hadEnough.discordrpc

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.IPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.result.ResultEpisode

/**
 * NON è API ufficiale dei plugin CloudStream: si aggancia agli stessi percorsi
 * interni già usati da WatchParty/PlayerAccess (CommonActivity.activity,
 * MainActivity.supportFragmentManager, GeneratorPlayer.player) e in più legge i
 * metadati del contenuto corrente (titolo, episodio, stagione, poster, provider)
 * dal ViewModel del player.
 *
 * Il ViewModel è creato dal fragment con ViewModelProvider(fragment)[...]
 * (GeneratorPlayer.onCreateView): recuperarlo qui con la stessa chiamata
 * restituisce la STESSA istanza. I metadati del contenuto corrente stanno in
 * vm.state.generatorState?.meta (l'SDK pre-release non espone più getMeta()).
 *
 * Ogni accesso è avvolto in runCatching: se un aggiornamento dell'app cambia
 * questi nomi, il plugin degrada in silenzio invece di crashare.
 */
object PlayerMetaAccess {

    /** Il fragment del player attualmente in primo piano, se presente. */
    fun currentPlayerFragment(): GeneratorPlayer? = runCatching {
        val activity = CommonActivity.activity as? MainActivity ?: return null
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return null
        val top: Fragment? = navHost.childFragmentManager.fragments.lastOrNull()
        top as? GeneratorPlayer
    }.getOrNull()

    /** True se l'utente ha in questo momento la schermata del player aperta. */
    fun isPlayerScreenActive(): Boolean = currentPlayerFragment() != null

    /** L'istanza IPlayer attiva, se il player è aperto. */
    fun currentPlayer(): IPlayer? = runCatching {
        currentPlayerFragment()?.player
    }.getOrNull()

    /** Metadati del contenuto in riproduzione (titolo, ep, stagione, poster, provider). */
    fun currentEpisode(): ResultEpisode? = runCatching {
        val fragment = currentPlayerFragment() ?: return null
        val vm = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
        // NOTE: l'API del PlayerGeneratorViewModel nell'artefatto pre-release del SDK
        // non espone getMeta(): i metadati del contenuto corrente stanno in
        // vm.state.generatorState?.meta (aggiornati da loadLinks).
        vm.state.generatorState?.meta as? ResultEpisode
    }.getOrNull()

    /**
     * Poster del contenuto in riproduzione, con fallback per i film: nel
     * ResultEpisode del film (generatorState.meta) il poster è spesso null, il
     * poster vero sta nel LoadResponse (generatorState.response) che il
     * RepoLinkGenerator usa come "page". Ultima spiaggia: il primo episodio di
     * allMeta che abbia un poster.
     */
    fun currentPoster(): String? {
        return try {
            val fragment = currentPlayerFragment() ?: return null
            val vm = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
            val gen = vm.state.generatorState ?: return null
            (gen.meta as? ResultEpisode)?.poster?.takeIf { it.isNotBlank() }
                ?: gen.response?.posterUrl?.takeIf { it.isNotBlank() }
                ?: gen.allMeta?.filterIsInstance<ResultEpisode>()
                    ?.firstNotNullOfOrNull { ep -> ep.poster?.takeIf { it.isNotBlank() } }
        } catch (t: Throwable) {
            null
        }
    }
}