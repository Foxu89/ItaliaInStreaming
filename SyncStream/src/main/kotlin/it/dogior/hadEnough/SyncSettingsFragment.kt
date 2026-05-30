@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.view.*
import android.widget.*
import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.content.DialogInterface
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context as appContext
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*

class SyncSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {
    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun View.makeTvCompatible() {
        val outlineId = plugin.resources!!.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        this.background = plugin.resources!!.getDrawable(outlineId, null)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return try {
            val view = getLayout("settings", inflater, container)

            val tokenInput = view.findView<EditText>("token")
            val projectInput = view.findView<EditText>("project_num")
            val syncSection = view.findView<LinearLayout>("sync_section")
            val categoryTable = view.findView<LinearLayout>("category_table")
            val forceSyncBtn = view.findView<Button>("btn_force_sync")
            val lastSyncInfo = view.findView<TextView>("last_sync_info")

            // Pre-popola
            tokenInput.setText(getKey<String>("sync_token"))
            projectInput.setText(getKey<String>("sync_project_num"))

            // Costruisci tabella categorie
            buildCategoryTable(categoryTable)

            // Stato iniziale sync section
            updateSyncSectionState(syncSection, lastSyncInfo)

            // Buttons
            view.findView<Button>("btn_login").setOnClickListener {
                onLogin(tokenInput, projectInput, syncSection, lastSyncInfo)
            }
            view.findView<Button>("btn_reset").setOnClickListener {
                onReset()
            }

            forceSyncBtn.setOnClickListener {
                onForceSync(lastSyncInfo)
            }

            view.findView<ImageView>("btn_guide").setOnClickListener {
                val url = "https://github.com/DieGon7771/ItaliaInStreaming/blob/master/guide/README_SyncStream.md"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }

            view
        } catch (e: Exception) {
            null
        }
    }

    private fun buildCategoryTable(container: LinearLayout) {
        container.removeAllViews()

        // Header row
        val headerRow = LinearLayout(container.context)
        headerRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.setPadding(0, 4, 0, 4)

        val nameHeader = TextView(container.context).apply {
            text = "Categoria"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(4, 0, 4, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val backupHeader = TextView(container.context).apply {
            text = "Backup"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            minWidth = 100
        }
        val restoreHeader = TextView(container.context).apply {
            text = "Restore"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            minWidth = 100
        }
        headerRow.addView(nameHeader)
        headerRow.addView(backupHeader)
        headerRow.addView(restoreHeader)
        container.addView(headerRow)

        // Divider
        val divider = View(container.context).apply {
            setBackgroundColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        container.addView(divider)

        // Category rows
        val categoryLabels = mapOf(
            SyncCategory.EXTENSIONS to "Estensioni",
            SyncCategory.BOOKMARKS to "Bookmarks",
            SyncCategory.RESUME_WATCHING to "Resume Watching",
            SyncCategory.SEARCH_HISTORY to "Cronologia",
            SyncCategory.SETTINGS_PLAYER to "Player",
            SyncCategory.SETTINGS_SUBTITLES to "Sottotitoli",
            SyncCategory.SETTINGS_THEME to "Tema",
            SyncCategory.SETTINGS_LAYOUT to "Layout",
            SyncCategory.SETTINGS_DOWNLOADS to "Download",
            SyncCategory.SETTINGS_GENERAL to "Generali",
        )

        for ((category, label) in categoryLabels) {
            val row = LinearLayout(container.context)
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 8, 0, 8)

            val nameText = TextView(container.context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(4, 0, 4, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val backupSwitch = Switch(container.context).apply {
                isChecked = getKey<String>(SyncCategory.backupKey(category)) == "true"
                setOnCheckedChangeListener { _, isChecked ->
                    setKey(SyncCategory.backupKey(category), if (isChecked) "true" else "false")
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                minWidth = 100
            }

            val restoreSwitch = Switch(container.context).apply {
                isChecked = getKey<String>(SyncCategory.restoreKey(category)) == "true"
                setOnCheckedChangeListener { _, isChecked ->
                    setKey(SyncCategory.restoreKey(category), if (isChecked) "true" else "false")
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                minWidth = 100
            }

            row.addView(nameText)
            row.addView(backupSwitch)
            row.addView(restoreSwitch)
            container.addView(row)
        }
    }

    private fun updateSyncSectionState(syncSection: LinearLayout, lastSyncInfo: TextView) {
        val loggedIn = ApiUtils.isLoggedIn()
        syncSection.alpha = if (loggedIn) 1.0f else 0.3f
        syncSection.isClickable = loggedIn
        syncSection.isEnabled = loggedIn
        if (loggedIn) {
            lastSyncInfo.text = "Connesso come: ${getKey<String>("device_id")?.take(12)}..."
        } else {
            lastSyncInfo.text = "Esegui il login per abilitare le categorie"
        }
    }

    private fun onLogin(
        tokenInput: EditText,
        projectInput: EditText,
        syncSection: LinearLayout,
        lastSyncInfo: TextView,
    ) {
        val token = tokenInput.text.trim().toString()
        val prNum = projectInput.text.toString()
        if (token.isNullOrEmpty() || prNum.isNullOrEmpty()) {
            showToast("Inserisci token e numero progetto")
            return
        }

        setKey("sync_token", token)
        setKey("sync_project_num", prNum)

        val loadingView = getLayout("loading", LayoutInflater.from(context), null)
        val loadingDialog = AlertDialog.Builder(context ?: return)
            .setView(loadingView)
            .setCancelable(false)
            .create()
        loadingDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ApiUtils.syncProjectDetails(appContext)
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    showToast(result?.second ?: "Errore")
                    if (result?.first == true) {
                        updateSyncSectionState(syncSection, lastSyncInfo)
                        buildCategoryTable(syncSection.findView("category_table"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    showToast("Errore: ${e.message}")
                }
            }
        }
    }

    private fun onReset() {
        setKey("sync_token", "")
        setKey("sync_project_num", "")
        setKey("sync_project_id", "")
        setKey("sync_item_id", "")
        setKey("sync_device_id", "")
        showToast("Credenziali rimosse")
        dismiss()
    }

    private fun onForceSync(lastSyncInfo: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ApiUtils.pushAllCategories(appContext)
                withContext(Dispatchers.Main) {
                    showToast("Sync completato")
                    lastSyncInfo.text = "Ultimo sync: ${System.currentTimeMillis().takeLast(5)}..."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("Sync fallito: ${e.message}")
                }
            }
        }
    }
}
