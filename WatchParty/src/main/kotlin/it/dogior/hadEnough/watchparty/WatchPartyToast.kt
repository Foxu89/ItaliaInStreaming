package it.dogior.hadEnough.watchparty

import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity

/**
 * Sostituto di CommonActivity.showToast (CloudStream vero) che non fa
 * affidamento su quel simbolo: il CommonActivity minimale di Nuvio Enhanced
 * espone solo `activity`/`setActivityInstance`, non `showToast`. Usando
 * direttamente android.widget.Toast funziona identico su entrambi gli
 * host, senza bisogno di rilevare quale sia.
 *
 * Stesso nome/firma della funzione originale (showToast(message: String)):
 * ai punti di chiamata basta cambiare l'import, nessun'altra modifica.
 */
fun showToast(message: String) {
    val activity = CommonActivity.activity ?: return
    runCatching {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
