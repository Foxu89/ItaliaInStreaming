package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import androidx.core.net.toUri

class UltimaSettings(val plugin: UltimaPlugin) : BottomSheetDialogFragment() {
    private val sm = UltimaStorageManager
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private val packageName = "it.dogior.hadEnough"

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

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

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", packageName)
        this.background = res.getDrawable(outlineId, null)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val settings = getLayout("settings", inflater, container)

        val saveBtn = settings.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.makeTvCompatible()
        saveBtn.isFocusable = true
        saveBtn.isFocusableInTouchMode = true
        saveBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Riavvio necessario")
                .setMessage("Modifiche salvate. Vuoi riavviare l'app per applicarle?")
                .setPositiveButton("Sì") { _, _ ->
                    sm.save()
                    showToast("Salvato. Riavvio...")
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { dialog, _ ->
                    sm.save()
                    showToast("Salvato. Riavvia dopo per applicare le modifiche.")
                    dialog.dismiss()
                    dismiss()
                }.show()
        }
        saveBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && 
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                saveBtn.performClick()
                true
            } else {
                false
            }
        }

        val configRow = settings.findView<LinearLayout>("config_row")
        configRow.makeTvCompatible()
        configRow.isFocusable = true
        configRow.isFocusableInTouchMode = true
        configRow.isClickable = true
        configRow.setOnClickListener {
            UltimaConfigureExtensions(plugin).show(
                activity?.supportFragmentManager ?: throw Exception("Impossibile aprire le impostazioni"),
                ""
            )
            dismiss()
        }
        configRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && 
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                configRow.performClick()
                true
            } else {
                false
            }
        }

        val reorderRow = settings.findView<LinearLayout>("reorder_row")
        reorderRow.makeTvCompatible()
        reorderRow.isFocusable = true
        reorderRow.isFocusableInTouchMode = true
        reorderRow.isClickable = true
        reorderRow.setOnClickListener {
            UltimaReorder(plugin).show(
                activity?.supportFragmentManager ?: throw Exception("Impossibile aprire le impostazioni"),
                ""
            )
            dismiss()
        }
        reorderRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && 
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                reorderRow.performClick()
                true
            } else {
                false
            }
        }

        val guideRow = settings.findView<LinearLayout>("guide_row")
        guideRow.makeTvCompatible()
        guideRow.isFocusable = true
        guideRow.isFocusableInTouchMode = true
        guideRow.isClickable = true
        guideRow.setOnClickListener {
            val url = "https://github.com/DieGon7771/ItaliaInStreaming/blob/master/guide/README_SectionOrganizer.md"
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        guideRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && 
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                guideRow.performClick()
                true
            } else {
                false
            }
        }

        val deleteRow = settings.findView<LinearLayout>("delete_row")
        deleteRow.makeTvCompatible()
        deleteRow.isFocusable = true
        deleteRow.isFocusableInTouchMode = true
        deleteRow.isClickable = true
        deleteRow.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset SectionOrganizer")
                .setMessage("Questo cancellerà tutte le sezioni selezionate.")
                .setPositiveButton("Reset") { _, _ ->
                    sm.deleteAllData()
                    sm.save()
                    showToast("Sezioni cancellate")
                    dismiss()
                }
                .setNegativeButton("Annulla", null)
                .show()
                .setDefaultFocus()
        }
        deleteRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && 
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                deleteRow.performClick()
                true
            } else {
                false
            }
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
