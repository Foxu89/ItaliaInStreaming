package it.dogior.hadEnough.wrapped

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val TAG = "CloudStreamWrapped"

/**
 * Plugin solo-CloudStream: genera una card riassuntiva delle statistiche
 * di visione locali (vedi WrappedStats) e permette di condividerla.
 *
 * Punto di ingresso: "Impostazioni" del plugin dalla schermata Estensioni
 * (openSettings sotto) — nessun overlay sul player, non serve essere nel
 * player per vedere le proprie statistiche.
 *
 * FragmentActivity (non AppCompatActivity): lezione imparata da WatchParty,
 * dove il cast ad AppCompatActivity ha causato un NoClassDefFoundError non
 * gestito su alcuni dispositivi/host. supportFragmentManager è già
 * dichiarato su FragmentActivity, non serve scendere fino ad AppCompatActivity.
 */
@CloudstreamPlugin
class WrappedPlugin : Plugin() {

    override fun load(context: Context) {
        this.openSettings = { openWrappedSheet() }
    }

    private fun openWrappedSheet() = runCatching {
        val activity = CommonActivity.activity as? FragmentActivity
        if (activity == null) {
            val msg = "Can't open CloudStream Wrapped here (unsupported activity type)"
            Log.e(TAG, "openWrappedSheet(): $msg")
            showToast(msg, Toast.LENGTH_LONG)
            return@runCatching
        }
        WrappedFragment(this).show(activity.supportFragmentManager, "CloudStreamWrapped")
    }.onFailure {
        Log.e(TAG, "openWrappedSheet(): eccezione inattesa", it)
        showToast("Can't open CloudStream Wrapped: ${it.javaClass.simpleName}", Toast.LENGTH_LONG)
    }
}
