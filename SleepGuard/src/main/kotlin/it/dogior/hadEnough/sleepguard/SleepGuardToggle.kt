package it.dogior.hadEnough.sleepguard

import android.util.Log
import android.widget.FrameLayout
import androidx.appcompat.widget.SwitchCompat

private const val TAG = "SleepGuardToggle"

/**
 * Mounts a toggle inside [host] (an empty FrameLayout in the XML): a real
 * Material3 Compose Switch (same exact mechanism and colors as
 * mountToggle() in WatchParty's WatchPartyComposeSwitch.kt, already
 * shipping there), falling back to a plain SwitchCompat if Compose fails
 * to resolve for any reason — same defensive style used throughout this
 * plugin. SleepGuard is CloudStream-only, so (unlike WatchParty, which
 * also targets Nuvio) there's no host check: Compose is always attempted
 * first.
 *
 * [initialChecked]/[onCheckedChange] have the same semantics as a plain
 * Switch.setChecked/setOnCheckedChangeListener: the caller doesn't need to
 * know whether Compose or the fallback is mounted underneath.
 */
fun mountToggle(host: FrameLayout, initialChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    host.removeAllViews()
    val accent = themeAccentColor(host.context)

    val mounted = runCatching { mountComposeSwitch(host, initialChecked, accent, onCheckedChange) }
        .onFailure { Log.w(TAG, "Compose Switch not mounted, falling back to the native one", it) }
        .getOrDefault(false)

    if (!mounted) {
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
 * Kept in its own function on purpose, same as mountComposeSlider: never
 * loaded/verified on a host without Compose, thanks to Android's lazy
 * class resolution.
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
                    // on: outline and track in the theme's accent color, pure white thumb
                    checkedThumbColor = white,
                    checkedTrackColor = accent,
                    checkedBorderColor = accent,
                    // off: track #35343A, outline and thumb #928F98 (same exact values WatchParty uses)
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
