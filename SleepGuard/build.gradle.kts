@file:Suppress("UnstableApiUsage")

// Plugin solo per CloudStream (non Nuvio), stesso principio di Wrapped:
// usiamo direttamente le classi interne dell'app (FullScreenPlayer, IPlayer,
// CommonActivity...) perché sono sempre presenti nel classpath reale, niente
// risoluzione pigra via reflection come su Nuvio.

dependencies {
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.google.android.material:material:1.4.0")
    compileOnly("androidx.core:core-ktx:1.13.1")
}

version = 3

android {
    buildFeatures {
        // Serve per generare BuildConfig, usata per risolvere le risorse
        // del plugin via plugin.resources (stesso motivo di Wrapped).
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
    description = "⚠️ BETA ⚠️ Rileva l'inattività durante la riproduzione (nessun tocco, nessun input) e mette in pausa chiedendo se stai ancora guardando, come Netflix/YouTube."
    requiresResources = true
}
