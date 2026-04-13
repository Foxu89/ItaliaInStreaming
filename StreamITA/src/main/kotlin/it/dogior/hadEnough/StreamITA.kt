// StreamITA.kt
package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.VidSrcExtractor
import it.dogior.hadEnough.extractors.VixSrcExtractor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class StreamITA : TmdbProvider() {
    override var name = "StreamITA"
    override val hasMainPage = true
    override val instantLinkLoading = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    private val TAG = "StreamITA"

    // Configurazione TMDB
    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val tmdbApiKey = BuildConfig.TMDB_API
    private val authHeaders = mapOf(
        "Authorization" to "Bearer $tmdbApiKey",
        "Accept" to "application/json"
    )

    // ==================== HOME PAGE ====================
    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?language=it-IT" to "🔥 Tendenze di Oggi",
        "$tmdbAPI/movie/popular?language=it-IT" to "🎬 Film Popolari",
        "$tmdbAPI/tv/popular?language=it-IT" to "📺 Serie TV Popolari",
        "$tmdbAPI/trending/movie/week?language=it-IT" to "🎥 Film della Settimana",
        "$tmdbAPI/trending/tv/week?language=it-IT" to "📼 Serie TV della Settimana",
        "$tmdbAPI/movie/top_rated?language=it-IT" to "⭐ Film più Votati",
        "$tmdbAPI/tv/top_rated?language=it-IT" to "🌟 Serie TV più Votate",
        "$tmdbAPI/movie/upcoming?language=it-IT&region=IT" to "📅 Prossime Uscite",
        "$tmdbAPI/tv/on_the_air?language=it-IT" to "📡 Serie TV in Onda",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        Log.d(TAG, "Loading main page: $url")

        val response = app.get(url, headers = authHeaders)
        if (!response.isSuccessful) {
            Log.e(TAG, "Main page error: ${response.code}")
            throw ErrorLoadingException("Errore nel caricamento homepage: ${response.code}")
        }

        val home = response.parsedSafe<Results>()?.results?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: throw ErrorLoadingException("Risposta JSON non valida")

        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        if (mediaType == "person") return null
        val title = title ?: name ?: originalTitle ?: return null
        val mediaType = mediaType ?: "movie"
        val tvType = if (mediaType == "movie") TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(
            title,
            Data(id = id, type = mediaType).toJson(),
            tvType,
        ) {
            this.posterUrl = getImageUrl(posterPath, getOriginal = true)
            this.score = voteAverage?.let { Score.from10(it) }
        }
    }

    // ==================== RICERCA ====================
    override suspend fun search(query: String): List<SearchResponse>? {
        Log.d(TAG, "Searching: $query")

        val url = "$tmdbAPI/search/multi?language=it-IT&query=$query&include_adult=${settingsForProvider.enableAdult}"
        val response = app.get(url, headers = authHeaders)

        if (!response.isSuccessful) {
            Log.e(TAG, "Search error: ${response.code}")
            return null
        }

        return response.parsedSafe<Results>()
            ?.results
            ?.filter { it.mediaType != "person" }
            ?.mapNotNull { media -> media.toSearchResponse() }
    }

    // ==================== DETTAGLI ====================
    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading details: $url")

        val data = parseJson<Data>(url)
        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        val append = "credits,videos,recommendations,external_ids"

        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?language=it-IT&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?language=it-IT&append_to_response=$append"
        }

        Log.d(TAG, "Loading details from: $resUrl")

        val response = app.get(resUrl, headers = authHeaders)
        if (!response.isSuccessful) {
            Log.e(TAG, "Details error: ${response.code}")
            throw ErrorLoadingException("Errore nel caricamento dettagli: ${response.code}")
        }

        val res = response.parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Risposta JSON non valida")

        val title = res.title ?: res.name ?: return null
        val poster = getImageUrl(res.posterPath, getOriginal = true)
        val bgPoster = getImageUrl(res.backdropPath, getOriginal = true)
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val plot = res.overview ?: "Nessuna descrizione disponibile."
        val rating = res.voteAverage?.toString()
        val genres = res.genres?.mapNotNull { it.name } ?: emptyList()
        val imdbId = res.imdbId ?: res.externalIds?.imdbId

        // CAST
        val actors = res.credits?.cast?.take(15)?.mapNotNull { cast ->
            val name = cast.name ?: cast.originalName ?: return@mapNotNull null
            ActorData(
                Actor(name, getImageUrl(cast.profilePath)),
                roleString = cast.character
            )
        } ?: emptyList()

        // RACCOMANDAZIONI
        val recommendations = res.recommendations?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        // TRAILER
        val trailer = res.videos?.results.orEmpty()
            .filter { it.type == "Trailer" || it.type == "Teaser" }
            .map { "https://www.youtube.com/watch?v=${it.key}" }

        return if (type == TvType.Movie) {
            // ========== FILM ==========
            val linkData = LinkData(
                id = data.id,
                title = title,
                year = year,
                season = null,
                episode = null,
                isMovie = true,
                imdbId = imdbId
            )

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                linkData.toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = plot
                this.duration = res.runtime
                this.score = rating?.let { Score.from10(it) }
                this.actors = actors
                this.tags = genres
                this.recommendations = recommendations
                addTrailer(trailer)
                imdbId?.let { addImdbId(it) }
            }
        } else {
            // ========== SERIE TV ==========
            val seasons = res.seasons?.filter { it.seasonNumber != null && it.seasonNumber > 0 } ?: emptyList()
            val episodes = mutableListOf<Episode>()

            seasons.forEach { season ->
                val seasonNumber = season.seasonNumber ?: return@forEach

                try {
                    val seasonUrl = "$tmdbAPI/tv/${data.id}/season/$seasonNumber?language=it-IT"
                    val seasonResponse = app.get(seasonUrl, headers = authHeaders)

                    if (!seasonResponse.isSuccessful) {
                        Log.e(TAG, "Season $seasonNumber error: ${seasonResponse.code}")
                        return@forEach
                    }

                    val seasonDetails = seasonResponse.parsedSafe<MediaDetailEpisodes>()

                    seasonDetails?.episodes?.forEach { eps ->
                        eps.episodeNumber?.let { epNum ->
                            val linkData = LinkData(
                                id = data.id,
                                title = title,
                                year = year,
                                season = seasonNumber,
                                episode = epNum,
                                isMovie = false,
                                episodeTitle = eps.name,
                                episodeOverview = eps.overview,
                                imdbId = imdbId
                            )

                            episodes.add(
                                newEpisode(linkData.toJson()) {
                                    this.name = eps.name ?: "Episodio $epNum"
                                    this.season = seasonNumber
                                    this.episode = epNum
                                    this.posterUrl = getImageUrl(eps.stillPath)
                                    this.description = eps.overview
                                    this.score = eps.voteAverage?.let { Score.from10(it) }
                                    this.runTime = eps.runtime
                                    this.addDate(eps.airDate)
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load season $seasonNumber: ${e.message}")
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = plot
                this.score = rating?.let { Score.from10(it) }
                this.actors = actors
                this.tags = genres
                this.recommendations = recommendations
                this.showStatus = when (res.status) {
                    "Returning Series" -> ShowStatus.Ongoing
                    "Ended" -> ShowStatus.Completed
                    else -> ShowStatus.Completed
                }
                addTrailer(trailer)
                imdbId?.let { addImdbId(it) }
            }
        }
    }

    // ==================== RIPRODUZIONE ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val tmdbId = linkData.id ?: return false

        Log.d(TAG, "Loading links for: ${linkData.title} S${linkData.season}E${linkData.episode}")

        var anySuccess = false

        coroutineScope {
            // Estrattore 1: VixSrc
            launch {
                try {
                    val vixSrcExtractor = VixSrcExtractor()
                    val vixSrcUrl = if (linkData.season == null) {
                        "https://vixsrc.to/embed/movie/$tmdbId"
                    } else {
                        "https://vixsrc.to/embed/tv/$tmdbId/${linkData.season}/${linkData.episode}"
                    }
                    Log.d(TAG, "Trying VixSrc: $vixSrcUrl")
                    vixSrcExtractor.getUrl(vixSrcUrl, "", subtitleCallback, callback)
                    anySuccess = true
                } catch (e: Exception) {
                    Log.e(TAG, "VixSrc failed: ${e.message}")
                }
            }

            // Estrattore 2: VidSrc
            launch {
                try {
                    val vidSrcExtractor = VidSrcExtractor()
                    val vidSrcUrl = if (linkData.season == null) {
                        "https://vidsrc.ru/movie/$tmdbId"
                    } else {
                        "https://vidsrc.ru/tv/$tmdbId/${linkData.season}/${linkData.episode}"
                    }
                    Log.d(TAG, "Trying VidSrc: $vidSrcUrl")
                    vidSrcExtractor.getUrl(vidSrcUrl, "", subtitleCallback, callback)
                    anySuccess = true
                } catch (e: Exception) {
                    Log.e(TAG, "VidSrc failed: ${e.message}")
                }
            }
        }

        return anySuccess
    }

    // ==================== HELPER FUNCTIONS ====================
    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link.isNullOrBlank()) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width$link" else link
    }

    // ==================== DATA CLASSES ====================
    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
        @JsonProperty("total_pages") val totalPages: Int = 1,
    )

    data class Media(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class Data(
        val id: Int,
        val type: String? = null,
    )

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

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
    )

    data class Seasons(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: ArrayList<EpisodeData>? = arrayListOf(),
    )

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

    data class ResultsTrailer(
        @JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class Cast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Genres(
        @JsonProperty("name") val name: String? = null,
    )
}
