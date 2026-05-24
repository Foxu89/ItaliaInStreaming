package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity.showToast
import org.json.JSONArray
import org.json.JSONObject

class SettingsSectionsFragment(
    private val plugin: StremioXPlugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val PREF_KEY_SECTIONS = "stremio_sections"
    private val PREF_KEY_OLD = "stremio_saved_links"

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    private val sections = mutableListOf<SectionConfig>()

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = res.getIdentifier("settings_sections", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(layoutId), container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        migrateOldFormat()

        sections.clear()
        sections.addAll(loadSections())
        rebuildRows(view)

        val addBtn = view.findViewByName<View>("add_section_btn")
        addBtn?.let { btn ->
            val greenDrawable = getDrawable("outline_green")
            if (greenDrawable != null) btn.background = greenDrawable
            else applyOutlineBackground(btn)
            btn.setOnClickListener { showAddDialog(view) }
        }

        setupSaveButton(view)
    }

    private fun setupSaveButton(view: View) {
        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        if (saveBtn == null) return
        saveBtn.background = getDrawable("outline")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.setOnClickListener {
            saveSections(sections)
            requireContext().showToast("Sezioni salvate")
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
    }

    // ── migration from old format ──

    private fun migrateOldFormat() {
        val oldJson = sharedPref.getString(PREF_KEY_OLD, null) ?: return
        val newJson = sharedPref.getString(PREF_KEY_SECTIONS, null)
        if (newJson != null) return

        val oldArr = try { JSONArray(oldJson) } catch (_: Exception) { return }
        val migrated = mutableListOf<SectionConfig>()

        for (i in 0 until oldArr.length()) {
            val obj = oldArr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val link = obj.optString("link", "")
            val type = obj.optString("type", "StremioX")
            if (link.isBlank()) continue

            val sectionName = name.ifBlank { "Sezione ${migrated.size + 1}" }
            migrated.add(
                SectionConfig(
                    id = System.currentTimeMillis() + i,
                    name = sectionName,
                    catalogUrl = if (type == "StremioC") link else null,
                    streamAddons = if (type == "StremioX") {
                        listOf(StreamAddonConfig(
                            id = System.currentTimeMillis() + i,
                            name = name.ifBlank { "Stream" },
                            url = link,
                            type = "https"
                        ))
                    } else emptyList()
                )
            )
        }

        if (migrated.isNotEmpty()) {
            saveSections(migrated)
            sharedPref.edit { remove(PREF_KEY_OLD) }
            Log.d("SettingsSectionsFragment", "Migrated ${migrated.size} old links to sections")
        }
    }

    // ── persist sections ──

    private fun loadSections(): List<SectionConfig> {
        val json = sharedPref.getString(PREF_KEY_SECTIONS, null) ?: return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val list = mutableListOf<SectionConfig>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val addonsArr = obj.optJSONArray("streamAddons")
            val addons = mutableListOf<StreamAddonConfig>()
            if (addonsArr != null) {
                for (j in 0 until addonsArr.length()) {
                    val ao = addonsArr.optJSONObject(j) ?: continue
                    addons.add(StreamAddonConfig(
                        id = ao.optLong("id", System.currentTimeMillis()),
                        name = ao.optString("name", ""),
                        url = ao.optString("url", ""),
                        type = ao.optString("type", "https")
                    ))
                }
            }
            list.add(SectionConfig(
                id = obj.optLong("id", System.currentTimeMillis()),
                name = obj.optString("name", ""),
                catalogUrl = obj.optString("catalogUrl", "").ifEmpty { null },
                streamAddons = addons
            ))
        }
        return list
    }

    private fun saveSections(list: List<SectionConfig>) {
        val arr = JSONArray()
        for (s in list) {
            val addonsArr = JSONArray()
            for (a in s.streamAddons) {
                addonsArr.put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("url", a.url)
                    put("type", a.type)
                })
            }
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("catalogUrl", s.catalogUrl ?: "")
                put("streamAddons", addonsArr)
            })
        }
        sharedPref.edit { putString(PREF_KEY_SECTIONS, arr.toString()) }
    }

    // ── build UI rows ──

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun rebuildRows(view: View) {
        val container: LinearLayout? = view.findViewByName("sections_container")
        container?.removeAllViews()

        for (section in sections) {
            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(10) }
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
                background = getDrawable("outline")
            }

            // header: name + count
            val headerRow = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dpToPx(44)
            }

            val nameText = TextView(requireContext()).apply {
                text = section.name
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dpToPx(8), 0, dpToPx(8), 0)
            }
            headerRow.addView(nameText)

            val addonCount = TextView(requireContext()).apply {
                text = "${section.streamAddons.size}/5 addon"
                textSize = 12f
                setPadding(dpToPx(8), 0, dpToPx(8), 0)
            }
            headerRow.addView(addonCount)
            row.addView(headerRow)

            // subheader: catalog type
            val catalogType = TextView(requireContext()).apply {
                text = if (section.catalogUrl != null) "Catalogo: ${section.catalogUrl}" else "Catalogo: TMDB"
                textSize = 12f
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), 0)
            }
            row.addView(catalogType)

            // action buttons
            val actionsRow = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(10) }
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }

            val editBtn = TextView(requireContext()).apply {
                text = "MODIFICA"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(36), 1f).apply {
                    rightMargin = dpToPx(4)
                }
                val greenDrawable = getDrawable("outline_green")
                if (greenDrawable != null) background = greenDrawable
                else applyOutlineBackground(this)
                setTextColor(Color.parseColor("#997CFF9D"))
                setOnClickListener { showEditDialog(requireView(), section) }
            }
            actionsRow.addView(editBtn)

            val deleteBtn = TextView(requireContext()).apply {
                text = "RIMUOVI"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(36), 1f).apply {
                    leftMargin = dpToPx(4)
                }
                val dangerDrawable = getDrawable("outline_danger")
                if (dangerDrawable != null) background = dangerDrawable
                else applyOutlineBackground(this)
                setTextColor(Color.parseColor("#FFFF7F7F"))
                setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Rimuovi sezione")
                        .setMessage("Rimuovere \"${section.name}\"?")
                        .setPositiveButton("Rimuovi") { _, _ ->
                            sections.remove(section)
                            rebuildRows(requireView())
                        }
                        .setNegativeButton("Annulla", null)
                        .show()
                }
            }
            actionsRow.addView(deleteBtn)
            row.addView(actionsRow)

            container?.addView(row)
        }
    }

    // ── add section dialog ──

    private fun showAddDialog(view: View) {
        val ctx = context ?: return
        val scrollView = LayoutInflater.from(ctx).inflate(
            res.getLayout(res.getIdentifier("settings_section_editor", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)),
            null
        )

        val titleView: TextView? = scrollView.findViewByName("editor_title")
        titleView?.text = "Nuova Sezione"

        AlertDialog.Builder(ctx)
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .setNegativeButton("Annulla", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val nameInput: EditText = scrollView.findViewByName("etSectionName") ?: return@setOnClickListener
                        val urlInput: EditText = scrollView.findViewByName("etCatalogUrl") ?: return@setOnClickListener
                        val name = nameInput.text.toString().trim()
                        val url = urlInput.text.toString().trim()
                        if (name.isEmpty()) {
                            showToast("Inserisci un nome")
                            return@setOnClickListener
                        }

                        // collect addons from dynamic slots
                        val addonContainer: LinearLayout = scrollView.findViewByName("addon_container") ?: return@setOnClickListener
                        val addons = mutableListOf<StreamAddonConfig>()
                        for (i in 0 until addonContainer.childCount) {
                            val slot = addonContainer.getChildAt(i)
                            val aname = slot.findViewByName<EditText>("etAddonName")?.text?.toString()?.trim().orEmpty()
                            val aurl = slot.findViewByName<EditText>("etAddonUrl")?.text?.toString()?.trim().orEmpty()
                            if (aurl.isNotBlank()) {
                                addons.add(StreamAddonConfig(
                                    id = System.currentTimeMillis() + i,
                                    name = aname.ifBlank { "Addon ${addons.size + 1}" },
                                    url = aurl,
                                    type = "https"
                                ))
                            }
                        }

                        sections.add(SectionConfig(
                            id = System.currentTimeMillis(),
                            name = name,
                            catalogUrl = url.ifBlank { null },
                            streamAddons = addons
                        ))
                        rebuildRows(view)
                        dismiss()
                    }
                }
                show()
            }
    }

    // ── edit section dialog ──

    private fun showEditDialog(view: View, section: SectionConfig) {
        val ctx = context ?: return
        val scrollView = LayoutInflater.from(ctx).inflate(
            res.getLayout(res.getIdentifier("settings_section_editor", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)),
            null
        )

        val titleView: TextView? = scrollView.findViewByName("editor_title")
        titleView?.text = "Modifica Sezione"

        val nameInput: EditText = scrollView.findViewByName("etSectionName") ?: return
        nameInput.setText(section.name)

        val urlInput: EditText = scrollView.findViewByName("etCatalogUrl") ?: return
        urlInput.setText(section.catalogUrl ?: "")

        // build addon slots
        val addonContainer: LinearLayout = scrollView.findViewByName("addon_container") ?: return
        val addonSlots = mutableListOf<StreamAddonConfig>()
        addonSlots.addAll(section.streamAddons)

        fun rebuildAddonSlots() {
            addonContainer.removeAllViews()
            for (i in 0 until maxOf(addonSlots.size, 5)) {
                val slotLayout = res.getLayout(
                    res.getIdentifier("item_addon_slot", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
                )
                val slot = LayoutInflater.from(ctx).inflate(slotLayout, addonContainer, false)
                val label: TextView = slot.findViewByName("addon_label") ?: continue
                label.text = "Addon #${i + 1}"

                val aname: EditText = slot.findViewByName("etAddonName") ?: continue
                val aurl: EditText = slot.findViewByName("etAddonUrl") ?: continue

                if (i < addonSlots.size) {
                    aname.setText(addonSlots[i].name)
                    aurl.setText(addonSlots[i].url)
                }

                // track changes
                val idx = i
                aname.setOnFocusChangeListener { _, _ ->
                    if (idx < addonSlots.size) {
                        addonSlots[idx] = addonSlots[idx].copy(name = aname.text.toString().trim())
                    }
                }
                aurl.setOnFocusChangeListener { _, _ ->
                    if (idx < addonSlots.size) {
                        addonSlots[idx] = addonSlots[idx].copy(url = aurl.text.toString().trim())
                    } else if (aurl.text.toString().trim().isNotBlank()) {
                        addonSlots.add(StreamAddonConfig(
                            id = System.currentTimeMillis() + idx,
                            name = aname.text.toString().trim().ifBlank { "Addon ${idx + 1}" },
                            url = aurl.text.toString().trim(),
                            type = "https"
                        ))
                    }
                }

                addonContainer.addView(slot)
            }
        }
        rebuildAddonSlots()

        AlertDialog.Builder(ctx)
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .setNegativeButton("Annulla", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = nameInput.text.toString().trim()
                        val url = urlInput.text.toString().trim()
                        if (name.isEmpty()) {
                            showToast("Inserisci un nome")
                            return@setOnClickListener
                        }

                        // collect addons
                        val currentAddons = mutableListOf<StreamAddonConfig>()
                        for (i in 0 until addonContainer.childCount) {
                            val slot = addonContainer.getChildAt(i)
                            val aname = slot.findViewByName<EditText>("etAddonName")?.text?.toString()?.trim().orEmpty()
                            val aurl = slot.findViewByName<EditText>("etAddonUrl")?.text?.toString()?.trim().orEmpty()
                            if (aurl.isNotBlank()) {
                                currentAddons.add(StreamAddonConfig(
                                    id = if (i < addonSlots.size) addonSlots[i].id else System.currentTimeMillis() + i,
                                    name = aname.ifBlank { "Addon ${currentAddons.size + 1}" },
                                    url = aurl,
                                    type = "https"
                                ))
                            }
                        }

                        val idx = sections.indexOfFirst { it.id == section.id }
                        if (idx >= 0) {
                            sections[idx] = section.copy(
                                name = name,
                                catalogUrl = url.ifBlank { null },
                                streamAddons = currentAddons
                            )
                        }
                        rebuildRows(view)
                        dismiss()
                    }
                }
                show()
            }
    }

    // ── helpers ──

    private fun <T : View> View.findViewByName(name: String): T? {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { findViewById(it) }
    }

    private fun getDrawable(name: String): android.graphics.drawable.Drawable? {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { androidx.core.content.res.ResourcesCompat.getDrawable(res, it, null) }
    }

    private fun applyOutlineBackground(view: View) {
        view.background = getDrawable("outline")
    }

    private fun dpToPx(dp: Int): Int =
        (dp * requireContext().resources.displayMetrics.density).toInt()

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
