package it.dogior.hadEnough.sleepguard

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.SeekBar
import kotlin.math.abs

private const val TAG = "SleepGuardSlider"

/**
 * Mounts a real Material3 Compose Slider (same exact mechanism as
 * mountSlider() in WatchPartySlider.kt, already shipping in production
 * there): a discrete-step slider, visually matching CloudStream's own
 * style. SleepGuard is CloudStream-only (no Nuvio branch needed), but the
 * try/catch fallback to a native SeekBar is kept anyway, in case Compose
 * fails to resolve for any reason on some build — same defensive style
 * used throughout this plugin.
 *
 * [steps] is the ordered list of selectable values (e.g. [5,10,15,...]);
 * [onValueChange] receives the VALUE (not the index) closest to where the
 * user left the slider.
 */
fun mountSlider(host: FrameLayout, steps: List<Int>, initialValue: Int, onValueChange: (Int) -> Unit) {
    host.removeAllViews()
    require(steps.size >= 2) { "mountSlider needs at least 2 values" }
    val accentColorInt = themeAccentColor(host.context)
    val closestIndex = steps.indices.minByOrNull { abs(steps[it] - initialValue) } ?: 0

    val mounted = runCatching { mountComposeSlider(host, steps, closestIndex, accentColorInt, onValueChange) }
        .onFailure { Log.w(TAG, "Compose Slider not mounted, falling back to the native one", it) }
        .getOrDefault(false)

    if (!mounted) {
        val seekBar = SeekBar(host.context).apply {
            max = steps.size - 1
            progress = closestIndex
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onValueChange(steps[progress.coerceIn(0, steps.size - 1)])
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        host.addView(
            seekBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
    }
}

/**
 * Kept in its own function, same as WatchParty's mountComposeSlider: never
 * loaded/verified on a host without Compose, thanks to Android's lazy
 * class resolution (the runCatching above never even touches these
 * Compose classes if they fail to resolve at classload time).
 */
private fun mountComposeSlider(
    host: FrameLayout,
    steps: List<Int>,
    initialIndex: Int,
    accentColorInt: Int,
    onValueChange: (Int) -> Unit,
): Boolean {
    val composeView = androidx.compose.ui.platform.ComposeView(host.context)
    val state = androidx.compose.runtime.mutableStateOf(initialIndex.toFloat())
    val accent = androidx.compose.ui.graphics.Color(accentColorInt)
    composeView.setContent {
        androidx.compose.material3.MaterialTheme {
            androidx.compose.material3.Slider(
                value = state.value,
                onValueChange = { state.value = it },
                onValueChangeFinished = {
                    val idx = state.value.toInt().coerceIn(0, steps.size - 1)
                    onValueChange(steps[idx])
                },
                valueRange = 0f..(steps.size - 1).toFloat(),
                steps = steps.size - 2, // M3 "steps" = INTERMEDIATE ticks, excluding both endpoints
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                ),
            )
        }
    }
    host.addView(
        composeView,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
    )
    return true
}

/** Same helper as WatchParty's themeAccentColor (WatchPartyComposeSwitch.kt). */
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

    return 0xFF3B65F5.toInt() // same fallback blue used elsewhere in the plugin
}
