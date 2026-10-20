package it.dogior.hadEnough.sleepguard

import android.content.Context
import android.content.SharedPreferences

/**
 * Plugin settings, stored locally (SharedPreferences, no account/network
 * involved). Lightweight reads/writes, never called from a thread other
 * than the UI thread, so no synchronization is needed.
 */
object SleepGuardPrefs {
    private const val FILE = "sleepguard_prefs"

    private const val KEY_INACTIVITY_MINUTES = "inactivity_minutes"
    private const val KEY_EPISODE_THRESHOLD = "episode_threshold"
    private const val KEY_EPISODE_COUNTER_ENABLED = "episode_counter_enabled"
    private const val KEY_VOLUME_KEY_RESET_ENABLED = "volume_key_reset_enabled"

    const val DEFAULT_INACTIVITY_MINUTES = 60
    // TEMPORARY for testing: 5 instead of 15, so there's no need to wait a
    // quarter of an hour to see the popup trigger. Put back to 15 later.
    const val MIN_INACTIVITY_MINUTES = 5
    const val MAX_INACTIVITY_MINUTES = 120

    const val DEFAULT_EPISODE_THRESHOLD = 3
    const val MIN_EPISODE_THRESHOLD = 1
    const val MAX_EPISODE_THRESHOLD = 5

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun inactivityMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INACTIVITY_MINUTES, DEFAULT_INACTIVITY_MINUTES)

    fun setInactivityMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INACTIVITY_MINUTES, minutes).apply()
    }

    fun episodeThreshold(context: Context): Int =
        prefs(context).getInt(KEY_EPISODE_THRESHOLD, DEFAULT_EPISODE_THRESHOLD)

    fun setEpisodeThreshold(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_EPISODE_THRESHOLD, count).apply()
    }

    /** Beta: Auto-Next episode counting, via reading the on-screen title (no official hook). */
    fun isEpisodeCounterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EPISODE_COUNTER_ENABLED, true)

    fun setEpisodeCounterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EPISODE_COUNTER_ENABLED, enabled).apply()
    }

    /** Experimental: reset on volume/remote keys, shares a slot with CloudStream itself. */
    fun isVolumeKeyResetEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOLUME_KEY_RESET_ENABLED, true)

    fun setVolumeKeyResetEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOLUME_KEY_RESET_ENABLED, enabled).apply()
    }
}
