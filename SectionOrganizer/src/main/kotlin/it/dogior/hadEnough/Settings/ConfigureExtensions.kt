package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

class UltimaConfigureExtensions(val plugin: UltimaPlugin) : BottomSheetDialogFragment() {
    private val sm = UltimaStorageManager
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private val extensions = sm.fetchExtensions()

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

    @SuppressLint("DiscouragedApi")
    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", "it.dogior.hadEnough") 
        return inflater.inflate(res.getLayout(id), container, false)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", "it.dogior.hadEnough") 
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", "it.dogior.hadEnough") 
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", "it.dogior.hadEnough") 
        this.background = res.getDrawable(outlineId, null)
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val settings = getLayout("configure_extensions", inflater, container)

        val saveBtn = settings.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.makeTvCompatible()
        saveBtn.setOnClickListener {
            sm.currentExtensions = extensions
            plugin.reload()
            showToast("Salvato")
            dismiss()
        }

        val extNameOnHomeBtn = settings.findView<Switch>("ext_name_on_home_toggle")
        extNameOnHomeBtn.makeTvCompatible()
        extNameOnHomeBtn.isChecked = sm.extNameOnHome
        extNameOnHomeBtn.setOnClickListener { sm.extNameOnHome = extNameOnHomeBtn.isChecked }

        val extensionsListLayout = settings.findView<LinearLayout>("extensions_list")
        extensions.forEach { extension ->
            extensionsListLayout.addView(buildExtensionView(extension, inflater, container))
        }

        return settings
    }

    private fun buildExtensionView(
        extension: UltimaUtils.ExtensionInfo,
        inflater: LayoutInflater,
        container: ViewGroup?
    ): View {
        fun buildSectionView(section: UltimaUtils.SectionInfo, inflater: LayoutInflater, container: ViewGroup?): View {
            val sectionView = getLayout("list_section_item", inflater, container)
            val checkBox = sectionView.findView<CheckBox>("section_checkbox")
            checkBox.text = section.name
            checkBox.makeTvCompatible()
            if (section.enabled == null) section.enabled = true
            checkBox.isChecked = section.enabled == true
            checkBox.setOnCheckedChangeListener { _, isChecked -> section.enabled = isChecked }
            return sectionView
        }

        val extView = getLayout("list_extension_item", inflater, container)
        val extensionDataBtn = extView.findView<LinearLayout>("extension_data")
        val expandImage = extView.findView<ImageView>("expand_icon")
        val extensionNameBtn = extensionDataBtn.findView<TextView>("extension_name")
        val childList = extView.findView<LinearLayout>("sections_list")

        expandImage.setImageDrawable(getDrawable("triangle"))
        expandImage.rotation = 90f
        extensionNameBtn.text = extension.name
        extensionDataBtn.makeTvCompatible()
        extensionDataBtn.setOnClickListener {
            val isVisible = childList.isVisible
            childList.visibility = if (isVisible) View.GONE else View.VISIBLE
            expandImage.rotation = if (isVisible) 90f else 180f
        }

        extension.sections?.forEach { section ->
            childList.addView(buildSectionView(section, inflater, container))
        }

        return extView
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {}

    override fun onDetach() {
        UltimaSettings(plugin).show(activity?.supportFragmentManager ?: throw Exception("Impossibile aprire le impostazioni"), "")
        super.onDetach()
    }
}
