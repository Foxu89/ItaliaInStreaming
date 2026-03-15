package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private var selectedSection: UltimaUtils.SectionInfo? = null

class UltimaReorder(val plugin: UltimaPlugin) : BottomSheetDialogFragment() {
    private val sm = UltimaStorageManager
    private val extensions = sm.fetchExtensions()
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private val packageName = "it.dogior.hadEnough"

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", packageName)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", packageName)
        return res.getDrawable(id, null) ?: throw Exception("Unable to find drawable $name")
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", packageName)
        return findViewById(id)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", packageName)
        background = res.getDrawable(outlineId, null)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = getLayout("reorder", inflater, container)

        val saveBtn = root.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.makeTvCompatible()
        saveBtn.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { sm.currentExtensions = extensions }
                showToast("Salvato. Riavvia l'app per applicare le modifiche.")
                dismiss()
            }
        }

        val noSectionWarning = root.findView<TextView>("no_section_warning")
        val sectionsListView = root.findView<LinearLayout>("section_list")
        updateSectionList(sectionsListView, inflater, container, noSectionWarning)

        return root
    }

    private fun updateSectionList(
        sectionsListView: LinearLayout,
        inflater: LayoutInflater,
        container: ViewGroup?,
        noSectionWarning: TextView? = null,
        currentSections: List<UltimaUtils.SectionInfo>? = null
    ) {
        sectionsListView.removeAllViews()

        val sections = currentSections ?: run {
            extensions.flatMap { ext -> ext.sections?.filter { it.enabled } ?: emptyList() }
        }

        if (sections.isEmpty()) {
            noSectionWarning?.visibility = View.VISIBLE
            return
        }

        val displaySections = sections.sortedByDescending { it.priority }
        var counter = displaySections.size

        displaySections.forEach { section ->
            val sectionView = getLayout("list_section_reorder_item", inflater, container)
            val sectionName = sectionView.findView<TextView>("section_name")

            if (section.priority == 0) section.priority = counter
            sectionName.text = "${section.pluginName}: ${section.name}"

            sectionView.background = LayerDrawable(
                arrayOf(
                    ColorDrawable(if (section == selectedSection) 0x2200FF00.toInt() else Color.TRANSPARENT),
                    getDrawable("outline")
                )
            )

            sectionView.setOnClickListener {
                when (selectedSection) {
                    null -> {
                        selectedSection = section
                        showToast("Selezionata! Ora tocca una destinazione.")
                        updateSectionList(sectionsListView, inflater, container, noSectionWarning, displaySections)
                    }
                    section -> {
                        selectedSection = null
                        updateSectionList(sectionsListView, inflater, container, noSectionWarning, displaySections)
                    }
                    else -> {
                        val selected = selectedSection!!
                        val sectionsMutable = displaySections.toMutableList()
                        val selectedIndex = sectionsMutable.indexOf(selected)
                        val targetIndex = sectionsMutable.indexOf(section)

                        if (selectedIndex == targetIndex) {
                            showToast("Già in questa posizione")
                            return@setOnClickListener
                        }

                        sectionsMutable.removeAt(selectedIndex)
                        sectionsMutable.add(targetIndex, selected)
                        sectionsMutable.forEachIndexed { index, sec -> sec.priority = sectionsMutable.size - index }

                        selectedSection = null
                        updateSectionList(sectionsListView, inflater, container, noSectionWarning, sectionsMutable)
                        showToast("Sezione spostata in posizione ${targetIndex + 1}")
                    }
                }
            }

            val increaseBtn = sectionView.findView<ImageView>("increase")
            val decreaseBtn = sectionView.findView<ImageView>("decrease")
            increaseBtn.setImageDrawable(getDrawable("triangle"))
            decreaseBtn.setImageDrawable(getDrawable("triangle"))
            decreaseBtn.rotation = 180f
            increaseBtn.makeTvCompatible()
            decreaseBtn.makeTvCompatible()

            increaseBtn.setOnClickListener {
                val idx = displaySections.indexOf(section)
                if (idx > 0) {
                    val newList = displaySections.toMutableList()
                    newList.removeAt(idx)
                    newList.add(idx - 1, section)
                    newList.forEachIndexed { index, sec -> sec.priority = newList.size - index }
                    updateSectionList(sectionsListView, inflater, container, noSectionWarning, newList)
                } else showToast("Già in cima")
            }

            decreaseBtn.setOnClickListener {
                val idx = displaySections.indexOf(section)
                if (idx < displaySections.lastIndex) {
                    val newList = displaySections.toMutableList()
                    newList.removeAt(idx)
                    newList.add(idx + 1, section)
                    newList.forEachIndexed { index, sec -> sec.priority = newList.size - index }
                    updateSectionList(sectionsListView, inflater, container, noSectionWarning, newList)
                } else showToast("Già in fondo")
            }

            counter -= 1
            sectionsListView.addView(sectionView)
        }
    }

    override fun onDetach() {
        super.onDetach()
        UltimaSettings(plugin).show(activity?.supportFragmentManager ?: throw Exception("Impossibile aprire le impostazioni"), "")
    }
}
