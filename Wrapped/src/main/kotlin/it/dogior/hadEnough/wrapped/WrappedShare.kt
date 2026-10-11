package it.dogior.hadEnough.wrapped

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider

/**
 * Condivisione via share sheet nativo (Telegram/WhatsApp/ecc, qualunque app
 * presente): niente integrazione dedicata con nessun servizio, solo
 * Intent.ACTION_SEND standard.
 *
 * Il file va condiviso come content:// (non file://, bloccato da Android da
 * anni per app con targetSdk recente), quindi serve un FileProvider. Non ne
 * registriamo uno nuovo: CloudStream ne ha già uno dichiarato nel suo
 * AndroidManifest (authority "<applicationId>.provider", percorsi che
 * coprono anche la cache — vedi res/xml/provider_paths.xml del suo
 * sorgente), già usato per altre condivisioni/apertura file interne
 * all'app. Lo riusiamo scrivendo il file in context.cacheDir, esattamente
 * come fa CloudStream stesso in OpenInAppAction.kt.
 */
object WrappedShare {

    fun share(context: Context, bitmap: Bitmap) {
        val file = WrappedCardRenderer.saveToCache(context, bitmap)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.applicationContext.packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "Share your CloudStream Wrapped").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
