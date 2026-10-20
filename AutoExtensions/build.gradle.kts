@file:Suppress("UnstableApiUsage")

// CloudStream-only plugin: reads the user's configured repositories and the
// app's built-in ones, and downloads any extension that isn't installed yet
// — recreating the "auto-download missing extensions" behavior from
// Settings > Updates & Backup > Extensions, in case a future CloudStream
// release removes it. Built entirely on top of PUBLIC, non-internal
// PluginManager/RepositoryManager functions (the same ones the Extensions
// screen itself uses for a manual "download" tap): it never calls any of
// the host's "___DO_NOT_CALL_FROM_A_PLUGIN_..." functions, which are
// explicitly guarded against reentrant/plugin use.

plugins {
    // Needed to compile the @Composable syntax used by the settings toggles
    // (AutoExtensionsToggle.kt), same Material3 Switch style as
    // WatchParty/SleepGuard.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

dependencies {
    compileOnly("com.google.android.material:material:1.4.0")
    compileOnly("androidx.compose.material3:material3:1.2.1")
    compileOnly("androidx.compose.ui:ui:1.6.7")
    compileOnly("androidx.compose.runtime:runtime:1.6.7")
}

version = 1

android {
    buildFeatures {
        // Needed to generate BuildConfig, used to resolve the plugin's own
        // resources via plugin.resources (same pattern as Wrapped/WatchParty/SleepGuard).
        buildConfig = true
    }
}

cloudstream {
    authors = listOf("DieGon")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3

    tvTypes = listOf(
        "Others",
    )

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/AutoExtensions/AutoExtensions_icon.png"
    description = "⚠️ BETA ⚠️ Automatically downloads extensions from your repositories that aren't installed yet, in case CloudStream removes its own auto-download feature."
    requiresResources = true
}
