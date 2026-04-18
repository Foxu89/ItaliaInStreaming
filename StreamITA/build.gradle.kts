import org.jetbrains.kotlin.konan.properties.Properties

version = 15

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
    iconUrl = "https://www.themoviedb.org/assets/2/v4/logos/v2/blue_square_2-d537fb228cf3ded904ef09b136fe3fec72548ebc1fea11890a74b3666c911d92.svg"
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
