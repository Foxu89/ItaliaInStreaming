package it.dogior.hadEnough

import android.content.SharedPreferences
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.VidSrcExtractor
import it.dogior.hadEnough.extractors.VixSrcExtractor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class StreamITA(sharedPref: SharedPreferences? = null) : TmdbProvider() {
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
    private val tmdbHeaders = mapOf(
        "Authorization" to "Bearer $tmdbApiKey",
        "Accept" to "application/json"
    )
    private val langCode = sharedPref?.getString("tmdb_language_code", "it-IT") ?: "it-IT"

    // ==================== HOME PAGE ====================
    override val mainPage = mainPageOf(
        "/trending/all/day?language=$langCode" to "🔥 Tendenze di Oggi",
        "/movie/popular?language=$langCode" to "🎬 Film Popolari",
        "/tv/popular?language=$langCode" to "📺 Serie TV Popolari",
        "/trending/movie/week?language=$langCode" to "🎥 Film della Settimana",
        "/trending/tv/week?language=$langCode" to "📼 Serie TV della Settimana",
        "/movie/top_rated?language=$langCode" to "⭐ Film più Votati",
        "/tv/top_rated?language=$langCode" to "🌟 Serie TV più Votate",
        "/movie/upcoming?language=$langCode" to "📅 Prossime Uscite",
        "/tv/on_the_air?language=$langCode" to "📡 Serie TV in Onda",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$tmdbAPI${request.data}&page=$page"
        Log.d(TAG, "Loading main page: $url")

        val home = app.get(url, headers = tmdbHeaders).parsedSafe<Results>()?.results?.mapNotNull {
            it.toSearchResponse()
        } ?: throw ErrorLoadingException("Risposta JSON non valida")

        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        val title = title ?: name ?: originalTitle ?: return null
        val mediaType = mediaType ?: "movie"
        val tvType = if (mediaType == "movie") TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(
            title,
            Data(id = id, type = mediaType).toJson(),
            tvType,
        ) {
            this.posterUrl = getImageUrl(posterPath)
            this.score = voteAverage?.let { Score.from10(it) }
        }
    }

    // ==================== RICERCA ====================
    override suspend fun search(query: String): List<SearchResponse>? {
        Log.d(TAG, "Searching: $query")

        val url = "$tmdbAPI/search/multi?language=$langCode&query=$query&include_adult=${settingsForProvider.enableAdult}"
        return app.get(url, headers = tmdbHeaders)
            .parsedSafe<Results>()
            ?.results
            ?.mapNotNull { media -> media.toSearchResponse() }
    }

    // ==================== DETTAGLI ====================
    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading details: $url")

        val data = parseJson<Data>(url)
        val type = data.type ?: "movie"
        val isMovie = type == "movie"

        val resUrl = if (isMovie) {
            "$tmdbAPI/movie/${data.id}?language=$langCode&append_to_response=credits,videos,recommendations"
        } else {
            "$tmdbAPI/tv/${data.id}?language=$langCode&append_to_response=credits,videos,recommendations"
        }

        val res = app.get(resUrl, headers = tmdbHeaders).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Risposta JSON non valida")

        val title = res.title ?: res.name ?: return null
        val poster = getImageUrl(res.posterPath)
        val bgPoster = getImageUrl(res.backdropPath)
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val plot = res.overview ?: "Nessuna descrizione disponibile."
        val rating = res.voteAverage?.toString()?.toDoubleOrNull()
        val genres = res.genres?.mapNotNull { it.name } ?: emptyList()

        val actors = res.credits?.cast?.take(10)?.mapNotNull { cast ->
            cast.name?.let { name ->
                ActorData(
                    Actor(name, getImageUrl(cast.profilePath)),
                    roleString = cast.character
                )
            }
        } ?: emptyList()

        val recommendations = res.recommendations?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        val trailer = res.videos?.results.orEmpty()
            .filter { it.type == "Trailer" || it.type == "Teaser" }
            .map { "https://www.youtube.com/watch?v=${it.key}" }

        return if (isMovie) {
            // ========== FILM ==========
            val linkData = LinkData(
                id = data.id,
                title = title,
                year = year,
                season = null,
                episode = null,
                isMovie = true
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
            }
        } else {
            // ========== SERIE TV ==========
            val seasons = res.seasons?.filter { it.seasonNumber != null && it.seasonNumber > 0 } ?: emptyList()

            val episodes = mutableListOf<Episode>()

            seasons.forEach { season ->
                val seasonNumber = season.seasonNumber ?: return@forEach

                try {
                    val seasonUrl = "$tmdbAPI/tv/${data.id}/season/$seasonNumber?language=$langCode"
                    val seasonDetails = app.get(seasonUrl, headers = tmdbHeaders)
                        .parsedSafe<MediaDetailEpisodes>()

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
                                episodeOverview = eps.overview
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

        // Avvia entrambi gli estrattori in parallelo
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
                } catch (e: Exception) {
                    Log.e(TAG, "VidSrc failed: ${e.message}")
                }
            }
        }

        return true
    }

    // ==================== HELPER FUNCTIONS ====================
    private fun getImageUrl(path: String?): String? {
        return if (!path.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$path" else null
    }

    private fun getOriginalImageUrl(path: String?): String? {
        return if (!path.isNullOrBlank()) "https://image.tmdb.org/t/p/original$path" else null
    }

    // ==================== DATA CLASSES ====================
    data class Data(
        val id: Int? = null,
        val type: String? = null,
    )

    data class LinkData(
        val id: Int? = null,
        val title: String? = null,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val isMovie: Boolean = false,
        val episodeTitle: String? = null,
        val episodeOverview: String? = null,
    )

    data class Results(
        val results: List<Media>? = emptyList(),
    )

    data class Media(
        val id: Int? = null,
        val name: String? = null,
        val title: String? = null,
        val originalTitle: String? = null,
        val mediaType: String? = null,
        val posterPath: String? = null,
        val backdropPath: String? = null,
        val voteAverage: Double? = null,
        val overview: String? = null,
    )

    data class Genres(
        val id: Int? = null,
        val name: String? = null,
    )

    data class MediaDetail(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val posterPath: String? = null,
        val backdropPath: String? = null,
        val releaseDate: String? = null,
        val firstAirDate: String? = null,
        val overview: String? = null,
        val runtime: Int? = null,
        val voteAverage: Any? = null,
        val status: String? = null,
        val genres: List<Genres>? = emptyList(),
        val seasons: List<Seasons>? = emptyList(),
        val videos: ResultsTrailer? = null,
        val credits: Credits? = null,
        val recommendations: Recommendations? = null,
    )

    data class Seasons(
        val seasonNumber: Int? = null,
        val name: String? = null,
        val episodeCount: Int? = null,
        val airDate: String? = null,
        val posterPath: String? = null,
    )

    data class MediaDetailEpisodes(
        val episodes: List<Episodes>? = emptyList(),
    )

    data class Episodes(
        val id: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val airDate: String? = null,
        val stillPath: String? = null,
        val voteAverage: Double? = null,
        val episodeNumber: Int? = null,
        val seasonNumber: Int? = null,
        val runtime: Int? = null,
    )

    data class Trailers(
        val key: String? = null,
        val type: String? = null,
        val name: String? = null,
    )

    data class ResultsTrailer(
        val results: List<Trailers>? = emptyList(),
    )

    data class Cast(
        val id: Int? = null,
        val name: String? = null,
        val character: String? = null,
        val profilePath: String? = null,
    )

    data class Credits(
        val cast: List<Cast>? = emptyList(),
    )

    data class Recommendations(
        val results: List<Media>? = emptyList(),
    )
}
