package it.dogior.hadEnough.watchparty

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Plugin sperimentale di riproduzione sincronizzata (Watch Party).
 *
 * Si basa su percorsi pubblici ma non ufficialmente garantiti dall'API dei
 * plugin CloudStream (vedi PlayerAccess.kt). Può smettere di funzionare
 * dopo un aggiornamento dell'app: in quel caso il polling ritorna
 * semplicemente null e il plugin resta inerte, senza crashare l'app.
 */
@CloudstreamPlugin
class WatchPartyPlugin : Plugin() {

    private val manager = WatchPartyManager()
    private lateinit var overlay: WatchPartyOverlay

    override fun load(context: Context) {
        overlay = WatchPartyOverlay(onClick = { openSettingsSheet() })
        overlay.start()
    }

    override fun beforeUnload() {
        overlay.stop()
        manager.release()
    }

    private fun openSettingsSheet() {
        val activity = com.lagradost.cloudstream3.CommonActivity.activity as? AppCompatActivity ?: return
        WatchPartySettingsFragment(this, manager).show(activity.supportFragmentManager, "WatchParty")
    }

    init {
        // Chiamato quando l'utente apre le impostazioni del plugin dalla
        // schermata Estensioni: stesso ingresso usato dal FAB sopra il player.
        this.openSettings = { openSettingsSheet() }
    }
}
