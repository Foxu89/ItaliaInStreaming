package it.dogior.hadEnough.watchparty

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Crea un AlertDialog.Builder, scegliendo l'implementazione giusta per l'host:
 *  - CloudStream: MaterialAlertDialogBuilder vera (angoli arrotondati,
 *    colori del tema — comportamento identico a come era sempre stato).
 *  - Altri host (es. Nuvio Enhanced, dove Material non è disponibile a
 *    runtime): AlertDialog.Builder di sistema, poi vestito con
 *    styleAsWatchPartyPanel per restare comunque coerente col resto
 *    dell'interfaccia del plugin (vedi sotto).
 *
 * Stesso motivo/stessa protezione di FabButton.kt: il riferimento a
 * MaterialAlertDialogBuilder vive in una funzione separata, chiamata solo
 * quando isCloudStreamHost è true, quindi grazie alla risoluzione pigra
 * delle classi di Android non viene mai toccato su un host senza Material.
 */
fun newAlertDialogBuilder(context: Context): AlertDialog.Builder {
    if (WatchPartyPlayback.isCloudStreamHost) {
        runCatching { return newMaterialAlertDialogBuilder(context) }
    }
    return AlertDialog.Builder(context)
}

private fun newMaterialAlertDialogBuilder(context: Context): AlertDialog.Builder =
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)

/**
 * Applica uno sfondo (tipicamente watchparty_panel_background: nero con
 * angoli arrotondati, stesso stile del pannello chat) alla Window di un
 * Dialog/DialogFragment — SOLO quando serve, cioè quando il dialog non è
 * già una vera MaterialAlertDialogBuilder (quella ha già il suo aspetto
 * corretto, sovrascriverlo qui lo romperebbe invece di sistemarlo).
 *
 * Il [background] va risolto dal chiamante con il proprio getDrawable(name)
 * locale (via plugin.resources.getIdentifier), non con un riferimento
 * diretto a R.drawable: gli ID R compilati nel plugin non sono affidabili
 * una volta che le risorse vengono agganciate dinamicamente nell'host
 * (stesso motivo per cui WatchPartyOverlay/WatchPartySettingsFragment
 * hanno già il loro getDrawable locale invece di usare R direttamente).
 */
fun styleAsWatchPartyPanel(dialog: Dialog?, background: Drawable?) {
    if (WatchPartyPlayback.isCloudStreamHost) return // già vestito da Material
    val window = dialog?.window ?: return
    if (background == null) return
    runCatching {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.findViewById<android.view.View>(android.R.id.content)
            ?.background = background
    }
}
