package it.dogior.hadEnough.discordrpc

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig

private const val TAG = "DiscordRPC"

class DiscordSettingsFragment(
    private val plugin: Plugin,
    private val manager: RPCManager,
) : BottomSheetDialogFragment() {

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun getDrawable(name: String): android.graphics.drawable.Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) return null
        return ResourcesCompat.getDrawable(res, id, null)
    }

    private fun View.applyDiscordBackground() {
        background = getDrawable("discord_blue")
    }

    private fun View.applyOutlineBackground() {
        background = getDrawable("outline")
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("discordrpc_settings", inflater, container)

        val statusDot = root.findView<View>("drp_status_dot")
        val status = root.findView<TextView>("drp_status")
        val enableSwitch = root.findView<Switch>("drp_enable")
        val previewBtn = root.findView<TextView>("drp_preview_btn")
        val tokenInput = root.findView<EditText>("drp_token_input")
        val loginBtn = root.findView<TextView>("drp_login_btn")
        val logoutBtn = root.findView<TextView>("drp_logout_btn")
        val showTitle = root.findView<Switch>("drp_show_title")
        val showEpisode = root.findView<Switch>("drp_show_episode")
        val showProvider = root.findView<Switch>("drp_show_provider")
        val showTime = root.findView<Switch>("drp_show_time")
        val showPoster = root.findView<Switch>("drp_show_poster")
        val appIdInput = root.findView<EditText>("drp_app_id_input")

        // --- stile card ---
        root.findView<View>("drp_account_card").applyOutlineBackground()
        root.findView<View>("drp_token_card").applyOutlineBackground()
        root.findView<View>("drp_display_card").applyOutlineBackground()
        previewBtn.applyDiscordBackground()
        loginBtn.applyDiscordBackground()
        logoutBtn.applyOutlineBackground()

        // --- valori salvati ---
        enableSwitch.isChecked = RPCSettings.enabled
        tokenInput.setText(RPCSettings.token)
        showTitle.isChecked = RPCSettings.showTitle
        showEpisode.isChecked = RPCSettings.showEpisode
        showProvider.isChecked = RPCSettings.showProvider
        showTime.isChecked = RPCSettings.showTimeElapsed
        showPoster.isChecked = RPCSettings.showPoster
        appIdInput.setText(RPCSettings.applicationId)

        // --- stati in tempo reale dal manager ---
        fun updateStatus(dot: Int, text: String) {
            statusDot.setBackgroundResource(dot)
            status.text = text
        }

        val loggedUsername = RPCSettings.username
        if (!loggedUsername.isNullOrBlank() && RPCSettings.token.isNotBlank()) {
            updateStatus(android.R.drawable.presence_online, "Logged in as $loggedUsername")
        } else {
            updateStatus(android.R.drawable.presence_offline, "Not logged in. Paste your Discord user token below.")
        }

        manager.onConnectionStateChanged = { s ->
            activity?.runOnUiThread {
                val (dot, text) = when (s) {
                    RPCManager.ConnectionState.CONNESSO ->
                        android.R.drawable.presence_online to "Connected to Discord"
                    RPCManager.ConnectionState.CONNESSIONE_IN_CORSO ->
                        android.R.drawable.presence_away to "Connecting to Discord…"
                    RPCManager.ConnectionState.RICONNESSIONE_IN_CORSO ->
                        android.R.drawable.presence_away to "Reconnecting to Discord…"
                    RPCManager.ConnectionState.DISCONNESSO ->
                        android.R.drawable.presence_offline to "Disconnected"
                }
                val user = RPCSettings.username
                val suffix = if (s == RPCManager.ConnectionState.CONNESSO && !user.isNullOrBlank()) " as $user" else ""
                updateStatus(dot, "$text$suffix")
            }
        }

        // --- salvataggio impostazioni ---
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            RPCSettings.enabled = checked
            if (checked) manager.start() else manager.stop()
        }

        showTitle.setOnCheckedChangeListener { _, checked -> RPCSettings.showTitle = checked; manager.pushPreviewUpdate() }
        showEpisode.setOnCheckedChangeListener { _, checked -> RPCSettings.showEpisode = checked; manager.pushPreviewUpdate() }
        showProvider.setOnCheckedChangeListener { _, checked -> RPCSettings.showProvider = checked; manager.pushPreviewUpdate() }
        showTime.setOnCheckedChangeListener { _, checked -> RPCSettings.showTimeElapsed = checked; manager.pushPreviewUpdate() }
        showPoster.setOnCheckedChangeListener { _, checked -> RPCSettings.showPoster = checked; manager.pushPreviewUpdate() }

        appIdInput.doAfterTextChanged {
            RPCSettings.applicationId = it?.toString()?.trim().orEmpty()
            manager.pushPreviewUpdate()
        }

        // --- login / logout ---
        loginBtn.setOnClickListener {
            val token = tokenInput.text?.toString()?.trim().orEmpty()
            if (token.isBlank()) {
                com.lagradost.cloudstream3.CommonActivity.showToast("Paste your token first")
                return@setOnClickListener
            }
            RPCSettings.token = token
            manager.stop()
            manager.start()
            com.lagradost.cloudstream3.CommonActivity.showToast("Logging in…")
        }

        logoutBtn.setOnClickListener {
            RPCSettings.clearToken()
            manager.stop()
            tokenInput.setText("")
            updateStatus(android.R.drawable.presence_offline, "Logged out. Token removed.")
        }

        // --- anteprima ---
        previewBtn.setOnClickListener {
            showPreviewDialog()
        }

        root
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Errore in onCreateView", e)
        null
    }

    /** Dialog di anteprima: mostra come apparirà la presenza con lo stato attuale. */
    private fun showPreviewDialog() {
        val ctx = context ?: return
        val pad = (16 * resources.displayMetrics.density).toInt()

        val state = runCatching {
            val player = PlayerMetaAccess.currentPlayer()
            val meta = PlayerMetaAccess.currentEpisode()
            val playing = player?.let { it.getIsPlaying() } ?: false
            val position = player?.getPosition() ?: 0L
            PresenceBuilder.PlayerState(meta, position, playing)
        }.getOrElse { PresenceBuilder.PlayerState(null, 0L, false) }

        val activities = PresenceBuilder.buildActivity(state)

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad/2, pad, 0)
        }

        val preview = android.widget.TextView(ctx).apply {
            textSize = 12f
            setLineSpacing((2 * resources.displayMetrics.density).toFloat(), 1f)
            text = if (activities.isNotEmpty()) {
                prettyJson(activities.toString())
            } else {
                "Not watching anything right now — the presence will be cleared."
            }
        }
        container.addView(preview)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Rich Presence preview")
            .setView(container)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun prettyJson(raw: String): String = raw
        .replace("},{", "},\n{")
        .replace(",", ", ")
}