package it.dogior.hadEnough.watchparty

import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Switch

private const val TAG = "WatchParty"

/**
 * Monta un interruttore dentro [host] (un FrameLayout vuoto nell'XML,
 * "wpa_xxx_host"): un vero Material3 Compose Switch se siamo su
 * CloudStream e Compose risolve, altrimenti un android.widget.Switch
 * classico (identico a quello che c'era prima).
 *
 * NOTA IMPORTANTE — questa è la parte meno testabile di tutto il lavoro
 * fatto finora: Compose-in-Fragment è un pattern diffuso e generalmente
 * affidabile, ma qui si aggiunge la variabile in più di girare dentro un
 * plugin con resources agganciate dinamicamente e un Context "identity"
 * verso CloudStream. Va verificato su un device vero prima di fidarsene
 * quanto il resto. Se qualsiasi cosa va storta, cade IMMEDIATAMENTE sullo
 * Switch nativo — mai un crash, nella peggiore delle ipotesi l'interruttore
 * torna quello di prima.
 *
 * [initialChecked]/[onCheckedChange] hanno la stessa semantica di uno
 * Switch.setChecked/setOnCheckedChangeListener normale: il chiamante non
 * deve sapere se sotto c'è Compose o no.
 */
fun mountToggle(host: FrameLayout, initialChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    host.removeAllViews()

    val mountedCompose = if (WatchPartyPlayback.isCloudStreamHost) {
        runCatching { mountComposeSwitch(host, initialChecked, onCheckedChange) }
            .onFailure { Log.w(TAG, "🧵 Compose Switch non montato, uso quello nativo", it) }
            .getOrDefault(false)
    } else {
        false
    }

    if (!mountedCompose) {
        val switchView = Switch(host.context).apply {
            showText = false
            isChecked = initialChecked
            setOnCheckedChangeListener { _, checked -> onCheckedChange(checked) }
        }
        host.addView(
            switchView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
    }
}

/**
 * Isolata in una funzione a parte apposta: referenzia ComposeView/Switch/
 * MaterialTheme di Compose SOLO qui dentro, chiamata solo quando
 * isCloudStreamHost è vero — grazie alla risoluzione pigra delle classi
 * di Android, su un host senza Compose questa funzione non viene mai
 * caricata/verificata, quindi non causa NoClassDefFoundError anche se
 * quelle classi non esistono lì (stessa protezione già usata per
 * FloatingActionButton/MaterialAlertDialogBuilder/IPlayer).
 */
private fun mountComposeSwitch(host: FrameLayout, initialChecked: Boolean, onCheckedChange: (Boolean) -> Unit): Boolean {
    val composeView = androidx.compose.ui.platform.ComposeView(host.context)
    val state = androidx.compose.runtime.mutableStateOf(initialChecked)
    composeView.setContent {
        androidx.compose.material3.MaterialTheme {
            androidx.compose.material3.Switch(
                checked = state.value,
                onCheckedChange = { checked ->
                    state.value = checked
                    onCheckedChange(checked)
                },
            )
        }
    }
    host.addView(
        composeView,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
    )
    return true
}
