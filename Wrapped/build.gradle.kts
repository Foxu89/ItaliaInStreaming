@file:Suppress("UnstableApiUsage")

// Plugin solo per CloudStream (non Nuvio): nessuna delle complicazioni di
// risoluzione pigra delle classi viste in WatchParty, qui possiamo usare
// Material/AppCompat direttamente, sono sempre presenti nel classpath reale.

dependencies {
    // compileOnly: solo per compilare contro le classi dell'app; a runtime
    // arrivano già dal classpath di CloudStream, non vanno bundlate.
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.google.android.material:material:1.4.0")
    compileOnly("androidx.core:core-ktx:1.13.1")
}

version = 4

android {
    buildFeatures {
        // Serve per generare la classe BuildConfig (usata in WrappedFragment.kt
        // per risolvere le risorse del plugin via plugin.resources). Senza
        // questo, AGP non la genera e il riferimento non compila.
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

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/Wrapped/Wrapped_icon.png"
    description = "⚠️ BETA ⚠️ Generate your CloudStream Wrapped: total watch time, movies/series watched, your Nerd Rank, and a shareable card."
    requiresResources = true
}
