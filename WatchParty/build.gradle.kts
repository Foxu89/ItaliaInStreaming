@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    // Necessario per compilare la sintassi @Composable in
    // WatchPartyComposeSwitch.kt (i toggle Material3 di CloudStream).
    // Stessa versione del plugin serialization qui sopra: i plugin
    // compilatore Kotlin di JetBrains condividono il numero di versione
    // con la versione di Kotlin del progetto, non sono versionati a parte
    // come una libreria qualunque.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

dependencies {
    // Material Components rimossa: non più usata da nessun file (FAB, dialog e
    // bottom sheet ora usano solo Android/AndroidX standard, vedi FabButton.kt
    // e NUVIO_COMPATIBILITY_NOTES.md). Non era comunque affidabile a runtime
    // su ogni host dei plugin CloudStream: su Nuvio Enhanced causava
    // NoClassDefFoundError perché la libreria non è inclusa lì.
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // WebSocket puro Kotlin, nessuna libreria nativa
    // compileOnly: solo per compilare contro le classi app; a runtime vengono dall'app
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.jaredrummler:colorpicker:1.1.0")
    // Solo per compilare contro androidx.media3.ui.PlayerView/Player (bridge
    // Nuvio Enhanced, vedi NuvioPlaybackBridge.kt). Nuvio la ha già nel suo
    // vero classpath (ci fa girare il player), non serve bundlarla — stessa
    // versione che usa Nuvio (gradle/libs.versions.toml del suo repository).
    compileOnly("androidx.media3:media3-ui:1.8.0")
    compileOnly("androidx.media3:media3-common:1.8.0")
    // Solo per compilare contro MaterialAlertDialogBuilder/FloatingActionButton
    // (usate solo su CloudStream, che le ha già nel suo classpath essendo
    // un'app Material vera — vedi FabButton.kt e WatchPartyDialogStyle.kt).
    // Su Nuvio quel codice non viene mai eseguito (host-detection), quindi
    // non serve che sia bundlata: se la mettessimo "implementation" e per
    // qualche motivo finisse comunque nel dex del plugin non farebbe danno,
    // ma non è necessario.
    compileOnly("com.google.android.material:material:1.4.0")
    // Solo per compilare contro androidx.compose.material3.Switch e
    // ComposeView (usati solo su CloudStream, che porta con sé Compose
    // Multiplatform — vedi WatchPartyComposeSwitch.kt). CloudStream
    // pubblica queste classi sotto org.jetbrains.compose.* su Maven, ma
    // per il target Android il codice compilato usa lo stesso namespace
    // androidx.compose.* delle librerie AndroidX Compose "vere" (Compose
    // Multiplatform per Android si appoggia a quelle, non le reimplementa):
    // ecco perché qui possiamo usare le coordinate AndroidX standard pur
    // sapendo che l'host userà la sua build Compose Multiplatform.
    compileOnly("androidx.compose.material3:material3:1.2.1")
    compileOnly("androidx.compose.ui:ui:1.6.7")
    compileOnly("androidx.compose.runtime:runtime:1.6.7")
}


version = 18

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("secrets.properties").inputStream())
        buildConfigField("String", "WATCHPARTY_RELAY", "\"${properties.getProperty("WATCHPARTY_RELAY").orEmpty()}\"")
    }

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
    status = 3 

    tvTypes = listOf(
        "Others",
    )

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/WatchParty/WatchParty_icon.png"
    description = "⚠️ BETA ⚠️ Watch movies and TV series together in real-time with live chat (Up to 5 users)."
    requiresResources = true
}
