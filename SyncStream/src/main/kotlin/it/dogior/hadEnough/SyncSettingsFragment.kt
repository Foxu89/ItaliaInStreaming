@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.*

private const val TAG = "SyncStream"

abstract class SyncBaseSettingsFragment : BottomSheetDialogFragment() {

    protected val plugin: Plugin
        get() = SyncPlugin.activePlugin ?: error("Plugin not available")

    protected val res
        get() = plugin.resources ?: error("Resources not available")

    protected abstract val layoutName: String

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = res.getIdentifier(layoutName, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(layoutId), container, false)
    }

    protected fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id)
    }

    protected fun getDrawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(res, it, null) }
    }

    protected fun View.applyOutlineBackground() {
        this.background = getDrawable("outline")
    }

    protected fun setupSaveButton(view: View, onClick: () -> Unit) {
        val saveBtn: ImageButton? = view.findView("save_btn")
        saveBtn?.applyOutlineBackground()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))
        saveBtn?.setOnClickListener { onClick() }
    }
}

class SyncSettingsFragment : SyncBaseSettingsFragment() {

    override val layoutName: String = "settings"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val providerName = if (ApiUtils.isDrive()) "Google Drive" else "GitHub"
        val status = when {
            !ApiUtils.isLoggedIn() -> "Non configurato"
            getKey<String>("backup_device") == "true" && getKey<String>("restore_device") == "true" -> "$providerName · backup e ripristino attivi"
            getKey<String>("backup_device") == "true" -> "$providerName · backup attivo"
            getKey<String>("restore_device") == "true" -> "$providerName · ripristino attivo"
            else -> "$providerName · configurato"
        }
        view.findView<TextView>("header_status").text = status

        view.findView<View>("login_card").applyOutlineBackground()
        view.findView<View>("prefs_card").applyOutlineBackground()
        view.findView<View>("guide_card").applyOutlineBackground()

        view.findView<View>("login_card").setOnClickListener {
            val fm = requireActivity().supportFragmentManager
            AlertDialog.Builder(requireContext())
                .setTitle("Dove vuoi sincronizzare?")
                .setItems(arrayOf("Google Drive (più semplice)", "GitHub")) { _, which ->
                    if (which == 0) SyncDriveFragment().show(fm, "Drive")
                    else SyncLoginFragment().show(fm, "Login")
                }
                .show()
        }

        view.findView<View>("guide_card").setOnClickListener {
            val url = "https://github.com/DieGon7771/ItaliaInStreaming/blob/master/guide/README_SyncStream.md"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        view.findView<View>("prefs_card").setOnClickListener {
            SyncPrefsFragment().show(requireActivity().supportFragmentManager, "Prefs")
        }
    }
}

class SyncPrefsFragment : SyncBaseSettingsFragment() {

    override val layoutName: String = "prefs"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findView<View>("prefs_body_card").applyOutlineBackground()

        val intervals = listOf(10_000L, 15_000L, 30_000L, 60_000L)
        val autoPullSwitch = view.findView<Switch>("auto_pull_switch")
        val prefsOptions = view.findView<View>("auto_pull_options")

        fun refreshPrefsUI() {
            val enabled = getKey<Boolean>("auto_pull_enabled") ?: false
            autoPullSwitch.isChecked = enabled
            prefsOptions.visibility = if (enabled) View.VISIBLE else View.GONE
            val current = getKey<Long>("auto_pull_seconds") ?: 30_000L
            intervals.forEach { sec ->
                view.findView<TextView>("auto_pull_${sec / 1000}_check").text =
                    if (sec == current) "✓" else ""
            }
        }

        refreshPrefsUI()

        autoPullSwitch.setOnCheckedChangeListener { _, isChecked ->
            setKey("auto_pull_enabled", isChecked)
            prefsOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        intervals.forEach { sec ->
            view.findView<View>("auto_pull_${sec / 1000}_row").setOnClickListener {
                setKey("auto_pull_seconds", sec)
                refreshPrefsUI()
            }
        }

        val closeBtn = view.findView<TextView>("close_btn")
        closeBtn.background = getDrawable("outline")
        closeBtn.setOnClickListener { dismiss() }
    }
}

class SyncLoginFragment : SyncBaseSettingsFragment() {

    override val layoutName: String = "login"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findView<View>("creds_card").applyOutlineBackground()

        val tokenInput = view.findView<EditText>("token")
        tokenInput.setText(getKey<String>("sync_token"))
        val prNumInput = view.findView<EditText>("project_num")
        prNumInput.setText(getKey<String>("sync_project_num"))
        val backupSwitch = view.findView<Switch>("backup_device")
        backupSwitch.text = "Backup dei dati sul cloud"
        backupSwitch.isChecked = getKey<String>("backup_device") == "true"
        val restoreSwitch = view.findView<Switch>("restore_device")
        restoreSwitch.text = "Recupera dati dal cloud"
        restoreSwitch.isChecked = getKey<String>("restore_device") == "true"

        setupSaveButton(view) {
            save(tokenInput, prNumInput, backupSwitch, restoreSwitch)
        }

        val resetBtn = view.findView<TextView>("reset_btn")
        val dangerDrawable = getDrawable("outline_danger")
        if (dangerDrawable != null) resetBtn.background = dangerDrawable else resetBtn.applyOutlineBackground()
        resetBtn.setTextColor(Color.parseColor("#FFFF7F7F"))
        resetBtn.setOnClickListener {
            setKey("sync_token", "")
            setKey("sync_project_num", "")
            showToast("Credentials removed")
            dismiss()
        }
    }

    private fun save(
        tokenInput: EditText,
        prNumInput: EditText,
        backupSwitch: Switch,
        restoreSwitch: Switch
    ) {
        val token = tokenInput.text.trim().toString()
        val prNum = prNumInput.text.toString()
        if (token.isEmpty() || prNum.isEmpty()) {
            showToast("Please fill in all information")
            return
        }
        setKey("sync_provider", "github")
        setKey("sync_token", token)
        setKey("sync_project_num", prNum)
        setKey("backup_device", "${backupSwitch.isChecked}")
        setKey("restore_device", "${restoreSwitch.isChecked}")

        val ctx = requireContext()
        val loadingDialog = AlertDialog.Builder(ctx)
            .setView(inflateLoading())
            .setCancelable(false)
            .create()
        loadingDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ApiUtils.syncProjectDetails(ctx)
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    if (result?.first == true) {
                        (plugin as? SyncPlugin)?.onLoginCompleted()
                        showToast(result.second)
                        dismiss()
                    } else {
                        showToast(result?.second ?: "Errore di sincronizzazione")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    showToast("Error syncing: ${e.message}")
                }
            }
        }
    }

    private fun inflateLoading(): View {
        val id = res.getIdentifier("loading", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return LayoutInflater.from(requireContext()).inflate(res.getLayout(id), null, false)
    }
}


/** Configurazione della sincronizzazione con Google Drive (accesso con codice su google.com/device). */
class SyncDriveFragment : SyncBaseSettingsFragment() {

    override val layoutName: String = "drive"

    private var loginJob: Job? = null

    override fun onDestroyView() {
        loginJob?.cancel()
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findView<View>("drive_card").applyOutlineBackground()
        val statusTv = view.findView<TextView>("drive_status")
        val loginBtn = view.findView<TextView>("drive_login_btn")
        val backupSwitch = view.findView<Switch>("backup_device")
        val restoreSwitch = view.findView<Switch>("restore_device")

        backupSwitch.text = "Backup dei dati sul cloud"
        restoreSwitch.text = "Recupera dati dal cloud"
        // alla prima configurazione sono entrambi attivi
        backupSwitch.isChecked = (getKey<String>("backup_device") ?: "true") == "true"
        restoreSwitch.isChecked = (getKey<String>("restore_device") ?: "true") == "true"

        fun refresh() {
            val logged = ApiUtils.isDrive() && GoogleDriveApi.isLoggedIn()
            statusTv.text = when {
                !GoogleDriveApi.isConfigured() -> "Google Drive non è configurato in questa versione del plugin"
                logged -> "Collegato a Google Drive"
                else -> "Non collegato"
            }
            loginBtn.text = if (logged) "ACCEDI DI NUOVO" else "ACCEDI CON GOOGLE"
        }
        refresh()

        loginBtn.applyOutlineBackground()
        loginBtn.setOnClickListener { startLogin(backupSwitch.isChecked, restoreSwitch.isChecked) { refresh() } }

        setupSaveButton(view) {
            setKey("backup_device", "${backupSwitch.isChecked}")
            setKey("restore_device", "${restoreSwitch.isChecked}")
            showToast(if (ApiUtils.isDrive() && GoogleDriveApi.isLoggedIn()) "Impostazioni salvate" else "Salvato. Ora accedi con Google")
            if (ApiUtils.isDrive() && GoogleDriveApi.isLoggedIn()) dismiss()
        }

        val resetBtn = view.findView<TextView>("reset_btn")
        val dangerDrawable = getDrawable("outline_danger")
        if (dangerDrawable != null) resetBtn.background = dangerDrawable else resetBtn.applyOutlineBackground()
        resetBtn.setTextColor(Color.parseColor("#FFFF7F7F"))
        resetBtn.setOnClickListener {
            GoogleDriveApi.logout()
            showToast("Disconnesso da Google Drive")
            dismiss()
        }
    }

    private fun startLogin(backup: Boolean, restore: Boolean, onDone: () -> Unit) {
        if (!GoogleDriveApi.isConfigured()) {
            showToast("Google Drive non è configurato in questa versione del plugin")
            return
        }
        val ctx = requireContext()
        loginJob?.cancel()
        loginJob = CoroutineScope(Dispatchers.IO).launch {
            val code = GoogleDriveApi.requestDeviceCode()
            if (code == null) {
                withContext(Dispatchers.Main) { showToast("Google ha risposto: ${GoogleDriveApi.lastError ?: "errore sconosciuto"}") }
                return@launch
            }
            val url = code.verificationUrl ?: "https://www.google.com/device"
            val userCode = code.userCode ?: ""

            var dialog: AlertDialog? = null
            withContext(Dispatchers.Main) {
                val d = AlertDialog.Builder(ctx)
                    .setTitle("Accedi con Google")
                    .setMessage(
                        "1. Apri $url (anche da un altro dispositivo)\n" +
                            "2. Inserisci questo codice:\n\n$userCode\n\n" +
                            "3. Accedi con il tuo account Google e consenti l'accesso.\n\n" +
                            "In attesa dell'autorizzazione..."
                    )
                    .setPositiveButton("Apri link", null)
                    .setNeutralButton("Copia codice", null)
                    .setNegativeButton("Annulla") { _, _ -> loginJob?.cancel() }
                    .setCancelable(false)
                    .create()
                d.show()
                // i pulsanti non chiudono la finestra
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        showToast("Apri $url dal browser")
                    }
                }
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Codice Google", userCode))
                    showToast("Codice copiato")
                }
                dialog = d
            }

            val auth = try {
                GoogleDriveApi.pollToken(code)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { dialog?.dismiss() }
            }
            if (!auth.first) {
                withContext(Dispatchers.Main) { showToast(auth.second ?: "Accesso non riuscito") }
                return@launch
            }

            setKey("sync_provider", "gdrive")
            setKey("backup_device", "$backup")
            setKey("restore_device", "$restore")
            val result = try {
                ApiUtils.syncDriveDetails(ctx)
            } catch (e: Exception) {
                false to "Errore: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                if (result.first) {
                    (plugin as? SyncPlugin)?.onLoginCompleted()
                    showToast(result.second)
                    dismiss()
                } else {
                    showToast(result.second ?: "Errore di sincronizzazione")
                    onDone()
                }
            }
        }
    }
}
