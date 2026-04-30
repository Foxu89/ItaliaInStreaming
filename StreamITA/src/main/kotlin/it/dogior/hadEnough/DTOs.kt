package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty

data class Results(@JsonProperty("results") val results: ArrayList<Media>? = arrayListOf())

data class Media(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
)

data class Data(val id: Int, val type: String? = null)

data class LinkData(
    val id: Int? = null,
    val imdbId: String? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val year: Int? = null,
    val isMovie: Boolean = false,
    val episodeTitle: String? = null,
    val episodeOverview: String? = null,
    val streamingCommunityIframeUrl: String? = null,
)

data class MediaDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Any? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
    @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
    @JsonProperty("videos") val videos: ResultsTrailer? = null,
    @JsonProperty("credits") val credits: Credits? = null,
    @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
    @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
)

data class ExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)

data class Seasons(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("air_date") val airDate: String? = null,
)

data class MediaDetailEpisodes(@JsonProperty("episodes") val episodes: ArrayList<EpisodeData>? = arrayListOf())

data class EpisodeData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
)

data class Trailers(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class ResultsTrailer(@JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf())

data class Cast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
)

data class Credits(@JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf())

data class ResultsRecommendations(@JsonProperty("results") val results: ArrayList<Media>? = arrayListOf())

data class Genres(@JsonProperty("name") val name: String? = null)
