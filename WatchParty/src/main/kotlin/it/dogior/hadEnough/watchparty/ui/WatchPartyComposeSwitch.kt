package it.dogior.hadEnough.watchparty

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.widget.FrameLayout
import androidx.appcompat.widget.SwitchCompat

private const val TAG = "WatchParty"

/**
 * Colore "accento" del tema reale dell'app (quello che CloudStream/Nuvio
 * usano per interruttori/pulsanti attivi), letto dal Context invece di
 * essere hardcoded. Senza questo, uno Switch costruito a mano (Compose o
 * SwitchCompat che sia) non eredita automaticamente i colori del tema
 * come faceva quello scritto nell'XML — da qui il colore sbagliato che
 * si vedeva prima di questo fix.
 *
 * Prova prima colorAccent di AppCompat (quello che il tema di CloudStream
 * imposta davvero), poi quello di sistema, poi un blu fisso come ultima
 * rete di sicurezza — mai un crash per questo.
 */
fun themeAccentColor(context: Context): Int {
    val typedValue = TypedValue()
    val fromAppCompat = runCatching {
        val a = context.theme.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorAccent))
        val color = a.getColor(0, 0)
        a.recycle()
        color.takeIf { it != 0 }
    }.getOrNull()
    if (fromAppCompat != null) return fromAppCompat

    val fromPlatform = runCatching {
        if (context.theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)) typedValue.data else null
    }.getOrNull()
    if (fromPlatform != null) return fromPlatform

    return 0xFF2E7DFF.toInt() // stesso blu di riserva già usato altrove nel plugin
}

/**
 * Monta un interruttore dentro [host] (un FrameLayout vuoto nell'XML,
 * "wpa_xxx_host"): un vero Material3 Compose Switch se siamo su
 * CloudStream e Compose risolve, altrimenti un SwitchCompat classico
 * (stesso identico widget/colori di quando era scritto nell'XML — NON
 * android.widget.Switch grezzo: quello NON si tinge come l'originale,
 * perché l'"upgrade" automatico a SwitchCompat lo fa AppCompat solo
 * quando la view è inflazionata da XML, non quando è costruita a mano).
 * Entrambi i rami prendono il colore vero dal tema con themeAccentColor(),
 * invece di un colore fisso scollegato dal tema dell'app.
 *
 * NOTA — il ramo Compose resta la parte meno testabile di tutto il lavoro
 * fatto finora (vedi NUVIO_COMPATIBILITY_NOTES.md): se qualsiasi cosa va
 * storta, cade IMMEDIATAMENTE sul fallback — mai un crash.
 *
 * [initialChecked]/[onCheckedChange] hanno la stessa semantica di uno
 * Switch.setChecked/setOnCheckedChangeListener normale: il chiamante non
 * deve sapere se sotto c'è Compose o no.
 */
fun mountToggle(host: FrameLayout, initialChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    host.removeAllViews()
    val accent = themeAccentColor(host.context)

    val mountedCompose = if (WatchPartyPlayback.isCloudStreamHost) {
        runCatching { mountComposeSwitch(host, initialChecked, accent, onCheckedChange) }
            .onFailure { Log.w(TAG, "🧵 Compose Switch non montato, uso quello nativo", it) }
            .getOrDefault(false)
    } else {
        false
    }

    if (!mountedCompose) {
        val switchView = SwitchCompat(host.context).apply {
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
private fun mountComposeSwitch(
    host: FrameLayout,
    initialChecked: Boolean,
    accentColorInt: Int,
    onCheckedChange: (Boolean) -> Unit,
): Boolean {
    val composeView = androidx.compose.ui.platform.ComposeView(host.context)
    val state = androidx.compose.runtime.mutableStateOf(initialChecked)
    val accent = androidx.compose.ui.graphics.Color(accentColorInt)
    val white = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    val darkTrack = androidx.compose.ui.graphics.Color(0xFF35343A)
    val lightGreyBorderAndThumb = androidx.compose.ui.graphics.Color(0xFF928F98)
    composeView.setContent {
        androidx.compose.material3.MaterialTheme {
            androidx.compose.material3.Switch(
                checked = state.value,
                onCheckedChange = { checked ->
                    state.value = checked
                    onCheckedChange(checked)
                },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    // acceso: contorno e pista nel colore del tema, pallino bianco puro
                    checkedThumbColor = white,
                    checkedTrackColor = accent,
                    checkedBorderColor = accent,
                    // spento: pista #35343A, contorno e pallino #928F98 (valori estratti dal tema reale)
                    uncheckedThumbColor = lightGreyBorderAndThumb,
                    uncheckedTrackColor = darkTrack,
                    uncheckedBorderColor = lightGreyBorderAndThumb,
                ),
            )
        }
    }
    host.addView(
        composeView,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
    )
    return true
}
