package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeUnionPlugin : Plugin() {
    companion object {
        const val PREFS = "AnimeUnion"
        const val KEY_LANG_TAGS = "show_lang_tags"
        const val KEY_SPLIT = "split_dub_sub"
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // attivo di default
        val showTags = sharedPref.getBoolean(KEY_LANG_TAGS, true)

        val split = sharedPref.getBoolean(KEY_SPLIT, false)
        registerMainAPI(AnimeUnion(showTags, split))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            AnimeUnionSettings(this, sharedPref).show(activity.supportFragmentManager, "AnimeUnionSettings")
        }
    }
}
