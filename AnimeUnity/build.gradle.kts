// use an integer for version numbers
version = 18


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Anime da AnimeSaturn"
    authors = listOf("doGior","DieGon")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 3
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    language = "it"
    requiresResources = false

    iconUrl = "https://raw.githubusercontent.com/Foxu89/ItaliaInStreaming/master/AnimeUnity/animesaturn_icon.png"
}
