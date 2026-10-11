package it.dogior.hadEnough.wrapped

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("wrapped_screen", inflater, container)

        val preview = root.findView<ImageView>("wrapped_preview")
        val loading = root.findView<ProgressBar>("wrapped_loading")
        val shareBtn = root.findView<TextView>("wrapped_share")
        val closeBtn = root.findView<TextView>("wrapped_close")

        shareBtn.isEnabled = false
        shareBtn.alpha = 0.5f
        closeBtn.setOnClickListener { dismiss() }
        shareBtn.setOnClickListener {
            lastBitmap?.let { bmp -> WrappedShare.share(requireContext(), bmp) }
        }

        generateCard(preview, loading, shareBtn)

        root
    } catch (e: Exception) {
        android.util.Log.e("WrappedFragment", "Eccezione in onCreateView()", e)
        null
    }

    private fun generateCard(preview: ImageView, loading: ProgressBar, shareBtn: TextView) {
        val ctx = requireContext().applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            val bitmap = runCatching {
                val stats = WrappedStatsCalculator.compute()
                WrappedCardRenderer.render(ctx, stats)
            }.getOrNull()

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext // il fragment potrebbe essere già chiuso
                loading.visibility = View.GONE
                if (bitmap != null) {
                    lastBitmap = bitmap
                    preview.setImageBitmap(bitmap)
                    shareBtn.isEnabled = true
                    shareBtn.alpha = 1f
                } else {
                    showToast("Couldn't generate your Wrapped card")
                }
            }
        }
    }
}
