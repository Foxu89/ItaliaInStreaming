package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.graphics.drawable.Drawable
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainAPIActor

@CloudstreamPlugin
class AltaDefinizionePlugin : Plugin() {
    override fun load(context: Context) {
        // Registra il provider principale
        registerMainAPI(AltaDefinizioneMain(context))
        // Registra gli estrattori
        registerExtractorAPI(DroploadExtractor())
    }
}

// Gestore principale che decide quale versione usare
class AltaDefinizioneMain(private val context: Context) : MainAPIActor() {
    private lateinit var sharedPref: SharedPreferences
    
    override fun getKey(): String = "altadefinizione"
    
    override fun getPluginName(): String = "Altadefinizione"
    
    override fun getPluginIcon(): Drawable? = null
    
    // Questo fa apparire l'icona ingranaggio nel plugin
    override fun getSettingsView(): Class<*>? = AltaDefinizioneSettings::class.java
    
    override fun getMainAPI(): MainAPI {
        // Carica le preferenze
        sharedPref = context.getSharedPreferences("altadefinizione_prefs", Context.MODE_PRIVATE)
        val siteVersion = sharedPref.getString("site_version", "v1") ?: "v1"
        
        // Scegli la versione corretta
        return when (siteVersion) {
            "v2" -> AltaDefinizioneV2()  // Versione vecchia
            else -> AltaDefinizioneV1()  // Versione attuale (default)
        }
    }
}
