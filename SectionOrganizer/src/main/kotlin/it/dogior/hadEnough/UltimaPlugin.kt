package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager

@CloudstreamPlugin
class UltimaPlugin : Plugin() {
    var activity: AppCompatActivity? = null
    
    override fun load(context: Context) {
        activity = context as AppCompatActivity
        registerMainAPI(Ultima(this))
        
        openSettings = { ctx ->
            val act = ctx as? AppCompatActivity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                val frag = UltimaSettings(this)
                frag.show(act.supportFragmentManager, "UltimaSettingsDialog")
            } else {
                Log.e("Plugin", "Activity is not valid anymore, cannot show settings dialog")
            }
        }
    }

    // NOTA: questa funzione cerca il plugin nell'elenco ONLINE (scaricabile),
    // non fra quelli attualmente CARICATI: quindi la condizione "pluginData == null"
    // e' quasi sempre falsa (il plugin e' online) e afterPluginsLoadedEvent non
    // scatta praticamente mai. Sembra un tentativo di "ricarica a caldo senza
    // riavviare l'app" con la logica invertita. Non l'ho toccata perche' non ho
    // visibilita' sull'API reale di PluginManager/afterPluginsLoadedEvent in
    // CloudStream: se serve davvero il reload a caldo, condividimi PluginManager.kt
    // (o il repo) e la sistemo per bene. Nel frattempo l'app si affida sempre al
    // riavvio manuale (vedi i flussi "Salvato, riavvia" nelle schermate impostazioni).
    fun reload() {
        val pluginData = PluginManager.getPluginsOnline().find { it.internalName.contains("Ultima") }
        if (pluginData == null) afterPluginsLoadedEvent.invoke(true)
    }
}
