import org.jetbrains.kotlin.konan.properties.Properties

version = 1

cloudstream {
    language = "it"
    description = "Il Migliore plugin Italiano"
    authors = listOf("DieGon")
    
    status = 1  // 1 = OK, 0 = Down, 2 = Slow, 3 = Beta only
    
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Cartoon"
    )
    
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/doGior/StreamITA/master/icon.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        // Carica dal file secrets.properties
        properties.load(project.rootProject.file("secrets.properties").inputStream())
        android.buildFeatures.buildConfig = true
        buildConfigField("String", "TMDB_API", "\"${properties.getProperty("TMDB_API")}\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}
