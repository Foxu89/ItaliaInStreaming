package it.dogior.hadEnough

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings dialog built in code (no XML resources): add / refresh / remove Nuvio repositories,
 * choose which scrapers are active, set the per-scraper timeout and the SCRAPER_SETTINGS JSON.
 */
class NuvioSettings(
    private val ctx: Context,
    private val manager: NuvioRepoManager,
) {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var container: LinearLayout
    private var busy = false

    fun show() {
        val scroll = ScrollView(ctx)
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        scroll.addView(container)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Nuvio")
            .setView(scroll)
            .setPositiveButton("Chiudi", null)
            .create()
        dialog.setOnDismissListener { uiScope.cancel() }

        render()
        dialog.show()
    }

    // ── rendering ──

    private fun render() {
        container.removeAllViews()

        container.addView(label("Repository di scraper Nuvio", 17f, bold = true))
        container.addView(
            label(
                "Incolla il link di un repository (github.com/utente/repo oppure il link raw). " +
                        "Gli scraper vengono scaricati e usati per trovare gli stream.",
                12f, alpha = 0.6f
            )
        )

        val urlInput = EditText(ctx).apply {
            hint = "https://github.com/utente/repository"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        container.addView(urlInput)
        container.addView(button("AGGIUNGI REPOSITORY", GREEN) {
            val text = urlInput.text.toString().trim()
            if (text.isEmpty()) {
                toast("Inserisci un link")
            } else {
                runSync("Download degli scraper in corso…") { manager.addRepo(text) }
            }
        })

        val repos = manager.repos()
        if (repos.isEmpty()) {
            container.addView(label("Nessun repository aggiunto.", 13f, alpha = 0.6f, topDp = 12))
        }
        repos.forEach { container.addView(repoCard(it)) }

        container.addView(label("Impostazioni", 17f, bold = true, topDp = 20))

        container.addView(label("Timeout per scraper (secondi)", 13f, bold = true, topDp = 8))
        val timeoutInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(manager.timeoutSeconds.toString())
            setSingleLine(true)
        }
        container.addView(timeoutInput)

        container.addView(label("SCRAPER_SETTINGS (JSON, opzionale)", 13f, bold = true, topDp = 8))
        container.addView(
            label(
                "Valori passati agli scraper che li richiedono, per esempio un cookie. Esempio: {\"chiave\":\"valore\"}",
                12f, alpha = 0.6f
            )
        )
        val settingsInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(manager.scraperSettingsJson)
        }
        container.addView(settingsInput)

        container.addView(button("SALVA IMPOSTAZIONI", GREEN) {
            val seconds = timeoutInput.text.toString().toIntOrNull()
            if (seconds == null) {
                toast("Timeout non valido")
                return@button
            }
            val raw = settingsInput.text.toString().trim().ifEmpty { "{}" }
            try {
                org.json.JSONObject(raw)
            } catch (_: Exception) {
                toast("Il JSON non e' valido")
                return@button
            }
            manager.timeoutSeconds = seconds
            manager.scraperSettingsJson = raw
            toast("Impostazioni salvate")
        })
    }

    private fun repoCard(repo: NuvioRepo): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = outline(GRAY)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val active = repo.scrapers.count { manager.isEnabled(repo, it) }
        card.addView(label(repo.name, 15f, bold = true))
        card.addView(label(repo.url, 11f, alpha = 0.5f))
        card.addView(label("$active/${repo.scrapers.size} scraper attivi", 12f, alpha = 0.7f))

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        row.addView(small("SCRAPER", GREEN) { showScrapers(repo) })
        row.addView(small("AGGIORNA", GREEN) {
            runSync("Aggiornamento in corso…") { manager.refreshRepo(repo.id) }
        })
        row.addView(small("RIMUOVI", RED) {
            AlertDialog.Builder(ctx)
                .setTitle("Rimuovi repository")
                .setMessage("Rimuovere \"${repo.name}\" e i suoi scraper?")
                .setPositiveButton("Rimuovi") { _, _ ->
                    manager.removeRepo(repo.id)
                    render()
                }
                .setNegativeButton("Annulla", null)
                .show()
        })
        card.addView(row)
        return card
    }

    private fun showScrapers(repo: NuvioRepo) {
        val names = repo.scrapers.map { s ->
            buildString {
                append(s.name)
                if (s.version.isNotEmpty()) append("  v").append(s.version)
                if (s.types.isNotEmpty()) append("  [").append(s.types.joinToString("/")).append("]")
            }
        }.toTypedArray()
        val checked = repo.scrapers.map { manager.isEnabled(repo, it) }.toBooleanArray()

        AlertDialog.Builder(ctx)
            .setTitle(repo.name)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                manager.setEnabled(repo, repo.scrapers[which], isChecked)
            }
            .setPositiveButton("OK") { _, _ -> render() }
            .show()
    }

    private fun runSync(message: String, action: suspend () -> NuvioSyncReport) {
        if (busy) return
        busy = true
        toast(message)
        uiScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { action() }
                val msg = buildString {
                    append("${report.repo.name}: ${report.downloaded} scraper scaricati")
                    if (report.failed.isNotEmpty()) {
                        append(", non riusciti: ").append(report.failed.joinToString(", "))
                    }
                }
                toast(msg, long = true)
            } catch (e: Exception) {
                toast("Errore: ${e.message ?: e.javaClass.simpleName}", long = true)
            } finally {
                busy = false
                render()
            }
        }
    }

    // ── small UI helpers ──

    private fun label(
        text: String,
        size: Float,
        bold: Boolean = false,
        alpha: Float = 1f,
        topDp: Int = 4,
    ) = TextView(ctx).apply {
        this.text = text
        textSize = size
        this.alpha = alpha
        if (bold) setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color)
        background = outline(color)
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun small(text: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color)
        background = outline(color)
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        }
        setOnClickListener { onClick() }
    }

    private fun outline(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.argb(20, Color.red(color), Color.green(color), Color.blue(color)))
        setStroke(dp(2), Color.argb(153, Color.red(color), Color.green(color), Color.blue(color)))
    }

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        val GREEN: Int = Color.parseColor("#7CFF9D")
        val RED: Int = Color.parseColor("#FF7F7F")
        val GRAY: Int = Color.parseColor("#FFFFFF")
    }
}
