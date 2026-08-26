package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

class UltimaConfigureExtensions(val plugin: UltimaPlugin) : BottomSheetDialogFragment() {
    private val sm = UltimaStorageManager
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private val packageName = "it.dogior.hadEnough"
    private val extensions = sm.fetchExtensions()

    @SuppressLint("DiscouragedApi")
    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", packageName)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", packageName)
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", packageName)
        return this.findViewById(id)
    }

    private fun View.card(drawableName: String = "outline") {
        background = getDrawable(drawableName)
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val settings = getLayout("configure_extensions", inflater, container)

        val saveBtn = settings.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.card("outline_blue")
        saveBtn.setOnClickListener {
            sm.currentExtensions = extensions
            plugin.reload()
            showToast("Salvato. Riavvia l'app per applicare le modifiche.")
            dismiss()
        }

        val extensionsListLayout = settings.findView<LinearLayout>("extensions_list")
        if (extensions.isEmpty()) {
            extensionsListLayout.addView(TextView(requireContext()).apply {
                text = "Nessuna estensione installata."
                alpha = 0.6f
                textSize = 13f
            })
        } else {
            extensions.forEach { extension ->
                extensionsListLayout.addView(buildExtensionView(extension, inflater, container))
            }
        }

        return settings
    }

    private fun buildExtensionView(
        extension: UltimaUtils.ExtensionInfo,
        inflater: LayoutInflater,
        container: ViewGroup?
    ): View {
        val checkBoxes = mutableListOf<CheckBox>()

        fun buildSectionView(section: UltimaUtils.SectionInfo, inflater: LayoutInflater, container: ViewGroup?): View {
            val sectionView = getLayout("list_section_item", inflater, container)
            sectionView.card()
            val checkBox = sectionView.findView<CheckBox>("section_checkbox")
            checkBox.text = section.name
            checkBox.isChecked = section.enabled
            checkBox.setOnCheckedChangeListener { _, isChecked -> section.enabled = isChecked }
            checkBoxes += checkBox

            sectionView.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }

            return sectionView
        }

        val extView = getLayout("list_extension_item", inflater, container)
        val extensionDataBtn = extView.findView<LinearLayout>("extension_data")
        val expandImage = extView.findView<ImageView>("expand_icon")
        val extensionNameBtn = extensionDataBtn.findView<TextView>("extension_name")
        val childList = extView.findView<LinearLayout>("sections_list")
        val selectAllBtn = extensionDataBtn.findView<TextView>("select_all")

        expandImage.setImageDrawable(getDrawable("triangle"))
        expandImage.rotation = 90f
        extensionNameBtn.text = extension.name ?: "Estensione"
        extensionDataBtn.card()
        selectAllBtn.card("outline_blue")

        extensionDataBtn.setOnClickListener {
            val isVisible = childList.isVisible
            childList.visibility = if (isVisible) View.GONE else View.VISIBLE
            expandImage.rotation = if (isVisible) 90f else 180f
        }

        // "Tutte" / "Nessuna": comodo per non dover spuntare a mano decine
        // di sezioni una per una quando un'estensione ne ha tante.
        selectAllBtn.setOnClickListener {
            val allSelected = checkBoxes.isNotEmpty() && checkBoxes.all { it.isChecked }
            val target = !allSelected
            checkBoxes.forEach { it.isChecked = target }
            selectAllBtn.text = if (target) "Nessuna" else "Tutte"
        }

        val sections = extension.sections
        if (sections.isNullOrEmpty()) {
            selectAllBtn.visibility = View.GONE
            childList.addView(TextView(requireContext()).apply {
                text = "Nessuna sezione trovata per questa estensione."
                alpha = 0.6f
                textSize = 12f
                setPadding(0, 8, 0, 8)
            })
        } else {
            sections.forEach { section ->
                childList.addView(buildSectionView(section, inflater, container))
            }
            selectAllBtn.text = if (sections.all { it.enabled }) "Nessuna" else "Tutte"
        }

        return extView
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Torna al foglio Impostazioni SOLO quando è l'utente a chiudere
        // volontariamente questa schermata (back, tap fuori, o dopo Salva).
        // A differenza di onDetach(), onDismiss non scatta se l'Activity
        // viene distrutta per altri motivi (rotazione, memoria) — quindi
        // niente crash quando activity/supportFragmentManager non sono più
        // disponibili.
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        UltimaSettings(plugin).show(act.supportFragmentManager, "UltimaSettingsDialog")
    }
}
