@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.view.*
import android.widget.*
import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class TorrentioSettings(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        @Suppress("UNCHECKED_CAST")
        return this.findViewById(id) as T
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = getLayout("torrentio_settings", inflater, container)

        // ── TorBox ──
        val torboxToggle = view.findView<Switch>("torbox_enable")
        val torboxToken = view.findView<EditText>("torbox_token")
        torboxToggle.isChecked = getKey<Boolean>("torrentio_torbox_enabled") == true
        torboxToken.setText(getKey<String>("torrentio_torbox_token") ?: "")

        // ── Real-Debrid ──
        val rdToggle = view.findView<Switch>("realdebrid_enable")
        val rdToken = view.findView<EditText>("realdebrid_token")
        rdToggle.isChecked = getKey<Boolean>("torrentio_realdebrid_enabled") == true
        rdToken.setText(getKey<String>("torrentio_realdebrid_token") ?: "")

        // ── Premiumize ──
        val pmToggle = view.findView<Switch>("premiumize_enable")
        val pmToken = view.findView<EditText>("premiumize_token")
        pmToggle.isChecked = getKey<Boolean>("torrentio_premiumize_enabled") == true
        pmToken.setText(getKey<String>("torrentio_premiumize_token") ?: "")

        // ── Save ──
        view.findView<Button>("save_btn").setOnClickListener {
            setKey("torrentio_torbox_enabled", torboxToggle.isChecked)
            setKey("torrentio_torbox_token", torboxToken.text.toString())
            setKey("torrentio_realdebrid_enabled", rdToggle.isChecked)
            setKey("torrentio_realdebrid_token", rdToken.text.toString())
            setKey("torrentio_premiumize_enabled", pmToggle.isChecked)
            setKey("torrentio_premiumize_token", pmToken.text.toString())

            showToast("Impostazioni salvate")

            AlertDialog.Builder(requireContext())
                .setTitle("Riavvia applicazione")
                .setMessage("Riavviare per applicare le modifiche?")
                .setPositiveButton("Riavvia") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("Più tardi", null)
                .show()
        }

        return view
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
