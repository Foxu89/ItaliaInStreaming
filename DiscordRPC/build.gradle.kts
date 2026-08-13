@file:Suppress("UnstableApiUsage")

plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
}

dependencies {
    implementation("com.google.android.material:material:1.4.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // WebSocket per il gateway Discord

    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7") // per NavHostFragment/ViewModelProvider (a runtime vengono dall'app)
    compileOnly("com.jaredrummler:colorpicker:1.1.0") // MainActivity implementa ColorPickerDialogListener
}

version = 4

android {
    buildFeatures {
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
    status = 3 // sperimentale: si appoggia a dettagli interni non ufficiali dell'app

    tvTypes = listOf(
        "Others",
    )

    iconUrl = "https://raw.githubusercontent.com/Foxu89/ItaliaInStreaming/master/DiscordRPC/DiscordRPC_icon.png"
    description = "Shows what you are watching on CloudStream as a Discord Rich Presence. Needs your Discord user token, handled only on-device."
    requiresResources = true
}