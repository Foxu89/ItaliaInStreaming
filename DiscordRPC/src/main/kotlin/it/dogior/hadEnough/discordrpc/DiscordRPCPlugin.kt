package it.dogior.hadEnough.discordrpc

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val TAG = "DiscordRPC"

/**
 * Plugin sperimentale: mostra su Discord cosa stai guardando in CloudStream.
 *
 * DIPENDENZE NON UFFICIALI: si aggancia al player e al ViewModel dell'app
 * (vedi PlayerMetaAccess.kt) e usa il token utente per la presenza (self-bot).
 * Entrambe le cose possono smettere di funzionare dopo un aggiornamento
 * dell'app o una modifica dei ToS di Discord: il plugin degrada in silenzio.
 */
@CloudstreamPlugin
class DiscordRPCPlugin : Plugin() {

    private val manager = RPCManager()

    override fun load(context: Context) {
        Log.d(TAG, "load() chiamato")
        manager.start()
    }

    override fun beforeUnload() {
        Log.d(TAG, "beforeUnload() chiamato")
        manager.stop()
    }

    init {
        // Chiamato quando l'utente apre le impostazioni del plugin dalla
        // schermata Estensioni.
        this.openSettings = {
            openSettingsSheet()
        }
    }

    private fun openSettingsSheet() {
        val rawActivity = CommonActivity.activity
        val activity = rawActivity as? AppCompatActivity ?: return
        DiscordSettingsFragment(plugin = this, manager = manager)
            .show(activity.supportFragmentManager, "DiscordRPC")
    }
}