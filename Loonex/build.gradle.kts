// use an integer for version numbers
version = 4


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Movies and Shows from CB01"
    authors = listOf("DieGon")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Cartoon")

    requiresResources = false
    language = "it"

    iconUrl = "https://raw.githubusercontent.com/Foxu89/ItaliaInStreaming/master/Loonex/loonex_icon.png"
}
dependencies{
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
