@file:Suppress("UnstableApiUsage")

// CloudStream-only plugin (not Nuvio), same principle as Wrapped: we use
// the app's own internal classes directly (FullScreenPlayer, IPlayer,
// CommonActivity...) because they're always present in the real classpath,
// no lazy reflection-based resolution like on Nuvio.

plugins {
    // Needed to compile the @Composable syntax used by the settings slider
    // (SleepGuardSlider.kt), same Jetpack Compose Slider style as
    // WatchParty's polling slider. Same version as the compiler plugin
    // used there: JetBrains Kotlin compiler plugins share the Kotlin
    // project's version number, they aren't versioned separately like an
    // ordinary library.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

dependencies {
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.google.android.material:material:1.4.0")
    compileOnly("androidx.core:core-ktx:1.13.1")
    // MainActivity (CommonActivity.activity as? MainActivity in
    // SleepGuardWatcher.currentPlayerFragment()) implements
    // ColorPickerDialogListener from this library: without this line
    // Kotlin can't resolve MainActivity's supertypes and the build fails
    // ("Cannot access ColorPickerDialogListener..."), even though the
    // plugin never uses the color picker itself. Same dependency already
    // present in WatchParty for the exact same reason (see PlayerAccess.kt).
    compileOnly("com.jaredrummler:colorpicker:1.1.0")
    // Only to compile against androidx.compose.material3.Slider/ComposeView
    // (used for the settings sliders, see SleepGuardSlider.kt). CloudStream
    // ships Compose Multiplatform and already has these classes in its real
    // classpath at runtime, same coordinates/versions WatchParty uses for
    // its own slider.
    compileOnly("androidx.compose.material3:material3:1.2.1")
    compileOnly("androidx.compose.ui:ui:1.6.7")
    compileOnly("androidx.compose.runtime:runtime:1.6.7")
}

version = 6

android {
    buildFeatures {
        // Needed to generate BuildConfig, used to resolve the plugin's own
        // resources via plugin.resources (same reason as Wrapped).
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

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/SleepGuard/SleepGuard_icon.png"
    description = "⚠️ BETA ⚠️ Detects inactivity during playback (no touch, no input) and pauses to ask if you're still watching, just like Netflix/YouTube."
    requiresResources = true
}
