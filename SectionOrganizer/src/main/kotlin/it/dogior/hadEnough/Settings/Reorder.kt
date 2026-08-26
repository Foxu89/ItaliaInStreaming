package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UltimaReorder(val plugin: UltimaPlugin) : BottomSheetDialogFragment() {
    private val sm = UltimaStorageManager
    private val extensions = sm.fetchExtensions()
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private val packageName = "it.dogior.hadEnough"

    // Campo di ISTANZA (non più a livello di file!): prima era condiviso da
    // TUTTE le aperture di questa schermata per l'intera vita del processo.
    // Se l'utente selezionava una sezione e chiudeva senza completare lo
    // spostamento, la selezione restava "appesa"; alla riapertura, un
    // riferimento ormai orfano (non più presente nella nuova lista appena
    // ricostruita da fetchExtensions()) mandava removeAt(-1) in crash.
    // Ora si azzera automaticamente ogni volta che il fragment viene ricreato.
    private var selectedSection: UltimaUtils.SectionInfo? = null

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", packageName)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", packageName)
        return res.getDrawable(id, null) ?: throw Exception("Unable to find drawable $name")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", packageName)
        return findViewById(id)
    }

    private fun View.card(drawableName: String = "outline") {
        background = getDrawable(drawableName)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = getLayout("reorder", inflater, container)

        val saveBtn = root.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.card("outline_blue")
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
        noSectionWarning?.visibility = View.GONE

        val displaySections = sections.sortedByDescending { it.priority }
        var counter = displaySections.size

        displaySections.forEach { section ->
            val sectionView = getLayout("list_section_reorder_item", inflater, container)
            val sectionName = sectionView.findView<TextView>("section_name")

            if (section.priority == 0) section.priority = counter
            sectionName.text = "${section.pluginName}: ${section.name}"

            // Confronto per IDENTITÀ (===), non per uguaglianza di valore (==).
            // SectionInfo è una data class: due sezioni con stessi
            // nome/url/pluginName/enabled/priority risulterebbero "uguali"
            // anche se sono oggetti diversi, con il rischio di evidenziare o
            // spostare quella sbagliata.
            sectionView.card(if (section === selectedSection) "outline_blue" else "outline")

            sectionView.setOnClickListener {
                val selected = selectedSection
                when {
                    selected == null -> {
                        selectedSection = section
                        showToast("Selezionata! Ora tocca una destinazione.")
                        updateSectionList(sectionsListView, inflater, container, noSectionWarning, displaySections)
                    }
                    selected === section -> {
                        selectedSection = null
                        updateSectionList(sectionsListView, inflater, container, noSectionWarning, displaySections)
                    }
                    else -> {
                        val sectionsMutable = displaySections.toMutableList()
                        val selectedIndex = sectionsMutable.indexOfFirst { it === selected }
                        val targetIndex = sectionsMutable.indexOfFirst { it === section }

                        // Rete di sicurezza: se per qualche motivo la sezione
                        // selezionata non è (più) in questa lista, non
                        // crashiamo con removeAt(-1) — semplicemente
                        // ripartiamo da una selezione pulita.
                        if (selectedIndex == -1 || targetIndex == -1) {
                            selectedSection = null
                            updateSectionList(sectionsListView, inflater, container, noSectionWarning, displaySections)
                            return@setOnClickListener
                        }

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
            increaseBtn.card()
            decreaseBtn.card()

            increaseBtn.setOnClickListener {
                val idx = displaySections.indexOfFirst { it === section }
                if (idx > 0) {
                    val newList = displaySections.toMutableList()
                    newList.removeAt(idx)
                    newList.add(idx - 1, section)
                    newList.forEachIndexed { index, sec -> sec.priority = newList.size - index }
                    updateSectionList(sectionsListView, inflater, container, noSectionWarning, newList)
                } else showToast("Già in cima")
            }

            decreaseBtn.setOnClickListener {
                val idx = displaySections.indexOfFirst { it === section }
                if (idx in 0 until displaySections.lastIndex) {
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

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        selectedSection = null
        // Come per ConfigureExtensions: torna alle Impostazioni solo alla
        // chiusura VOLONTARIA (onDismiss), non da onDetach — che scattava
        // anche a fragment/Activity distrutti dal sistema e mandava in
        // crash l'app quando activity era già null.
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        UltimaSettings(plugin).show(act.supportFragmentManager, "UltimaSettingsDialog")
    }
}
