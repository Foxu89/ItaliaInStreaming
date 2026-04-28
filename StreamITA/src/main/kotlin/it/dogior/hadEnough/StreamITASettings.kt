package it.dogior.hadEnough

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StreamITASettings(
    private val plugin: StreamITAPlugin
) : BottomSheetDialogFragment() {

    private val res = plugin.resources ?: error("Resources not available")

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
    ): View {
        val layoutId = res.getIdentifier("settings_streamita", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(layoutId), container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val buildInfoId = res.getIdentifier("header_build_info", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        view.findViewById<TextView>(buildInfoId)?.text =
            "Ultimo aggiornamento: ${BuildConfig.BUILD_COMPLETED_AT_ROME}"
    }
}
