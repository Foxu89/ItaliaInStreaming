@file:Suppress("UnstableApiUsage")

plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
}

dependencies {
    implementation("com.google.android.material:material:1.4.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // WebSocket puro Kotlin, nessuna libreria nativa
    // compileOnly: solo per compilare contro le classi app; a runtime vengono dall'app
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.jaredrummler:colorpicker:1.1.0")
}

// usa un intero per il numero di versione
version = 7

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
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
    status = 3 // sperimentale: si appoggia a dettagli interni non ufficiali dell'app

    tvTypes = listOf(
        "Others",
    )

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/WatchParty/WatchParty_icon.png"
    description = "Guarda in sincrono con un amico: play/pausa/seek/episodio replicati in tempo reale."
    requiresResources = true
}
