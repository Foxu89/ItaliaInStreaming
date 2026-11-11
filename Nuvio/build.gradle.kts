import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 1

android {
    defaultConfig {
        // Same as the other modules: the TMDB key comes from secrets.properties (TMDB_API3)
        val properties = Properties()
        properties.load(project.rootProject.file("secrets.properties").inputStream())
        buildConfigField("String", "TMDB_API3", "\"${properties.getProperty("TMDB_API3")}\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "it"
    description = "Usa gli scraper dei repository Nuvio (JavaScript) dentro CloudStream"
    authors = listOf("DieGon")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3

    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )

    // The settings dialog is built in code, no XML resources needed
    requiresResources = false
}
