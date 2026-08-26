package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

/** Foglio principale delle impostazioni. Le sotto-schermate (Configura /
 *  Riordina) tornano qui SOLO quando l'utente le chiude volontariamente
 *  (vedi [onDismiss] in quelle classi) — non più da onDetach, che scattava
 *  anche quando l'Activity moriva per altri motivi (rotazione, memoria) e
 *  in quel caso mandava in crash l'app. */
class UltimaSettings(val plugin: UltimaPlugin) : BottomSheetDialogFragment() {
    private val sm = UltimaStorageManager
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private val packageName = "it.dogior.hadEnough"

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", packageName)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", packageName)
        return res.getDrawable(id, null) ?: throw Exception("Unable to find drawable $name")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", packageName)
        return this.findViewById(id)
    }

    private fun View.card(drawableName: String = "outline") {
        background = getDrawable(drawableName)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val settings = getLayout("settings", inflater, container)

        val saveBtn = settings.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.card("outline_blue")
        saveBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Riavvio necessario")
                .setMessage("Per applicare le modifiche fatte in Configura/Riordina sezioni serve riavviare l'app. Vuoi farlo ora?")
                .setPositiveButton("Riavvia ora") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("Più tardi") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        val configRow = settings.findView<View>("config_row")
        configRow.card()
        configRow.findView<ImageView>("config_img").setImageDrawable(getDrawable("edit_icon"))
        configRow.setOnClickListener {
            UltimaConfigureExtensions(plugin).show(parentFragmentManager, "UltimaConfigureExtensions")
            dismiss()
        }

        val reorderRow = settings.findView<View>("reorder_row")
        reorderRow.card()
        reorderRow.findView<ImageView>("reorder_img").apply {
            setImageDrawable(getDrawable("triangle"))
            rotation = 90f
        }
        reorderRow.setOnClickListener {
            UltimaReorder(plugin).show(parentFragmentManager, "UltimaReorder")
            dismiss()
        }

        val extNameRow = settings.findView<View>("ext_name_on_home_row")
        extNameRow.card()
        val extNameToggle = settings.findView<Switch>("ext_name_on_home_toggle")
        extNameToggle.isChecked = sm.extNameOnHome
        extNameRow.setOnClickListener {
            extNameToggle.isChecked = !extNameToggle.isChecked
            sm.extNameOnHome = extNameToggle.isChecked
            showToast("Salvato. Riavvia l'app per aggiornare i nomi già in home.")
        }
        extNameToggle.setOnClickListener {
            // lo Switch ha già cambiato stato da solo col tap: allineiamo lo
            // storage invece di ri-invertirlo (altrimenti doppio-toggle col
            // click sulla riga sopra)
            sm.extNameOnHome = extNameToggle.isChecked
            showToast("Salvato. Riavvia l'app per aggiornare i nomi già in home.")
        }

        val guideRow = settings.findView<View>("guide_row")
        guideRow.card()
        guideRow.findView<ImageView>("guide_icon").apply {
            setImageDrawable(getDrawable("triangle"))
            rotation = 90f
        }
        guideRow.setOnClickListener {
            val url = "https://github.com/DieGon7771/ItaliaInStreaming/blob/master/guide/README_SectionOrganizer.md"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                showToast("Impossibile aprire il link")
            }
        }

        val deleteRow = settings.findView<View>("delete_row")
        deleteRow.card("outline_danger")
        val deleteIcon = deleteRow.findView<ImageView>("delete_icon")
        deleteIcon.setImageDrawable(getDrawable("delete_icon"))
        deleteRow.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset SectionOrganizer")
                .setMessage("Questo cancellerà tutte le sezioni selezionate e le preferenze. Continuare?")
                .setPositiveButton("Reset") { _, _ ->
                    sm.deleteAllData()
                    showToast("Sezioni cancellate. Riavvia l'app per applicare.")
                    dismiss()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        return settings
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.component?.let {
            context.startActivity(Intent.makeRestartActivityTask(it))
            Runtime.getRuntime().exit(0)
        }
    }
}
