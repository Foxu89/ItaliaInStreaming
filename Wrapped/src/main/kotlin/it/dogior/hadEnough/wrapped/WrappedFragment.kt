package it.dogior.hadEnough.wrapped

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WrappedFragment"

/**
 * Schermata "CloudStream Wrapped": calcola le statistiche, le disegna come
 * card, le mostra in anteprima, e lascia condividerle con lo share sheet
 * nativo. Tutto il lavoro pesante (lettura DataStoreHelper + disegno del
 * bitmap) gira su Dispatchers.Default, non sul thread UI.
 *
 * Stesso pattern di caricamento risorse di WatchParty (plugin.resources,
 * mai l'R.id/R.layout generato per l'host, che non li contiene).
 */
class WrappedFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private var lastBitmap: Bitmap? = null

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    /**
     * Sfondi caricati e assegnati via codice, MAI via android:background="@drawable/..."
     * nell'XML: stesso bug già trovato e risolto su WatchParty, dove quel
     * riferimento non si risolveva in modo affidabile per le risorse agganciate
     * dinamicamente di un plugin, l'inflate lanciava eccezione, e onCreateView()
     * tornava null (pannello "vuoto"/invisibile).
     */
    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: run {
            android.util.Log.e(TAG, "getDrawable('$name'): plugin.resources è null")
            return null
        }
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) {
            android.util.Log.e(TAG, "getDrawable('$name'): risorsa non trovata (id=0)")
            return null
        }
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }
            .onFailure { android.util.Log.e(TAG, "getDrawable('$name'): eccezione nel caricare il drawable", it) }
            .getOrNull()
    }

    /** Rete di sicurezza se il drawable del plugin non si carica per qualche motivo. */
    private fun coloredFallback(fill: Int, radiusDp: Float = 16f, strokeColor: Int? = null): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
            strokeColor?.let { setStroke((2 * resources.displayMetrics.density).toInt(), it) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("wrapped_screen", inflater, container)

        val preview = root.findView<ImageView>("wrapped_preview")
        val loading = root.findView<ProgressBar>("wrapped_loading")
        val shareBtn = root.findView<TextView>("wrapped_share")
        val closeBtn = root.findView<TextView>("wrapped_close")

        preview.background = getDrawable("wrapped_preview_bg")
            ?: coloredFallback(0xFF1C1B29.toInt(), radiusDp = 20f)
        shareBtn.background = getDrawable("wrapped_share_bg")
            ?: coloredFallback(0xFF3B65F5.toInt(), radiusDp = 16f)
        closeBtn.background = getDrawable("wrapped_outline_bg")
            ?: coloredFallback(0x12FFFFFF, radiusDp = 16f, strokeColor = 0x33FFFFFF)

        shareBtn.isEnabled = false
        shareBtn.alpha = 0.5f
        closeBtn.setOnClickListener { dismiss() }
        shareBtn.setOnClickListener {
            lastBitmap?.let { bmp -> WrappedShare.share(requireContext(), bmp) }
        }

        generateCard(preview, loading, shareBtn)

        root
    } catch (t: Throwable) {
        // Throwable e non Exception: un NoClassDefFoundError (come il bug
        // reale trovato in WatchParty) o qualsiasi altro Error non verrebbe
        // intercettato da un catch (e: Exception), farebbe fallire
        // silenziosamente .show() e il pannello sembrerebbe vuoto/invisibile
        // senza nessun indizio visibile per l'utente.
        android.util.Log.e(TAG, "Eccezione in onCreateView()", t)
        runCatching {
            showToast("CloudStream Wrapped crash: ${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG)
        }
        null
    }

    private fun generateCard(preview: ImageView, loading: ProgressBar, shareBtn: TextView) {
        val ctx = requireContext().applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            val result = runCatching {
                val stats = WrappedStatsCalculator.compute()
                WrappedCardRenderer.render(ctx, stats)
            }
            val bitmap = result.getOrNull()
            if (bitmap == null) {
                result.exceptionOrNull()?.let {
                    android.util.Log.e(TAG, "generateCard() fallita", it)
                }
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext // il fragment potrebbe essere già chiuso
                loading.visibility = View.GONE
                if (bitmap != null) {
                    lastBitmap = bitmap
                    preview.setImageBitmap(bitmap)
                    shareBtn.isEnabled = true
                    shareBtn.alpha = 1f
                } else {
                    val reason = result.exceptionOrNull()
                    val detail = reason?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "motivo sconosciuto"
                    showToast("Couldn't generate your Wrapped card ($detail)", Toast.LENGTH_LONG)
                }
            }
        }
    }
}
