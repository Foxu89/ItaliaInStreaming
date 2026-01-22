// use an integer for version numbers
version = 35


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Film e SerieTV da StreamingCommunity"
    authors = listOf("doGior","DieGon")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Documentary",
        "Cartoon"
    )

    requiresResources = true
    language = "it"

    iconUrl = "https://streamingunity.tv/apple-touch-icon.png?v=2"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}
