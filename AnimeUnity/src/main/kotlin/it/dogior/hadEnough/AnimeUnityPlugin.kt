package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeUnityPlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("AnimeUnity", Context.MODE_PRIVATE)
    
    override fun load(context: Context) {
        // ✅ LEGGI LA PREFERENZA DEL LOGO
        val showLogo = sharedPref?.getBoolean("show_logo", false) ?: false
        
        // ✅ PASSA showLogo AL COSTRUTTORE
        registerMainAPI(AnimeUnity(showLogo))
        
        // ✅ REGISTRA LE IMPOSTAZIONI
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = AnimeUnitySettings(this, sharedPref)
            frag.show(activity.supportFragmentManager, "AnimeUnitySettings")
        }
    }
}
