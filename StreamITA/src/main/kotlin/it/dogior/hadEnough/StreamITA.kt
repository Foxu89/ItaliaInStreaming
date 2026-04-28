package it.dogior.hadEnough

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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope

class StreamITA : TmdbProvider() {
    override var name = "StreamITA"
    override var lang: String = "it"
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
    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val tmdbApiKey = BuildConfig.TMDB_API
    private val authHeaders = mapOf(
        "Authorization" to "Bearer $tmdbApiKey",
        "Accept" to "application/json"
    )

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?language=it-IT" to "Tendenze di Oggi",
        "$tmdbAPI/movie/popular?language=it-IT" to "Film Popolari",
        "$tmdbAPI/tv/popular?language=it-IT" to "Serie TV Popolari",
        "$tmdbAPI/trending/movie/week?language=it-IT" to "Film della Settimana",
        "$tmdbAPI/trending/tv/week?language=it-IT" to "Serie TV della Settimana",
        "$tmdbAPI/movie/top_rated?language=it-IT" to "Film più Votati",
        "$tmdbAPI/tv/top_rated?language=it-IT" to "Serie TV più Votate",
        "$tmdbAPI/movie/upcoming?language=it-IT&region=IT" to "Prossime Uscite",
        "$tmdbAPI/tv/on_the_air?language=it-IT" to "Serie TV in Onda",
        "$tmdbAPI/tv/airing_today?language=it-IT" to "In Onda Oggi",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=213" to "Netflix",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=1024" to "Amazon Prime",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=2739" to "Disney+",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=453" to "Hulu",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=2552" to "Apple TV+",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=49" to "HBO",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=4330" to "Paramount+",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=3353" to "Peacock",
        "$tmdbAPI/discover/movie?language=it-IT&with_keywords=210024|222243&sort_by=popularity.desc" to "Anime Film",
        "$tmdbAPI/discover/tv?language=it-IT&with_keywords=210024|222243&sort_by=popularity.desc" to "Anime TV",
        "$tmdbAPI/discover/tv?language=it-IT&with_original_language=ko" to "Serie Coreane",
        "$tmdbAPI/discover/tv?language=it-IT&with_genres=99" to "Documentari",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp = app.get("${request.data}&page=$page", headers = authHeaders).body.string()
        val type = if (request.data.contains("tv")) "tv" else "movie"
        val parsedResponse = parseJson<Results>(resp).results?.mapNotNull { media ->
            media.toSearchResponse(type = type)
        }

        val home = parsedResponse ?: throw ErrorLoadingException("Invalid Json response")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String = "tv"): SearchResponse? {
        if (mediaType == "person") return null
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath, true)
            this.score = voteAverage?.let { Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$tmdbAPI/search/multi?language=it-IT&query=$query&include_adult=${settingsForProvider.enableAdult}"
        val response = app.get(url, headers = authHeaders)
        if (!response.isSuccessful) return null
        return response.parsedSafe<Results>()?.results
            ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            ?.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load() URL: $url")

        val data = parseJson<Data>(url)
        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        val append = "credits,videos,recommendations,external_ids"
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?language=it-IT&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?language=it-IT&append_to_response=$append"
        }

        var res = try {
            withTimeoutOrNull(10000) {
                app.get(resUrl, headers = authHeaders).parsedSafe<MediaDetail>()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore IT: ${e.message}")
            null
        }

        if (res == null) {
            Log.d(TAG, "Fallback EN per ID: ${data.id}")
            val enUrl = if (type == TvType.Movie) {
                "$tmdbAPI/movie/${data.id}?language=en-US&append_to_response=$append"
            } else {
                "$tmdbAPI/tv/${data.id}?language=en-US&append_to_response=$append"
            }
            res = try {
                withTimeoutOrNull(8000) {
                    app.get(enUrl, headers = authHeaders).parsedSafe<MediaDetail>()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore EN: ${e.message}")
                null
            }
        }

        if (res == null) throw ErrorLoadingException("Contenuto non disponibile")

        val title = res.title ?: res.name ?: return null
        val poster = getImageUrl(res.posterPath, true)
        val bgPoster = getImageUrl(res.backdropPath, true)
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val plot = res.overview ?: "Nessuna descrizione disponibile."
        val rating = res.voteAverage?.toString()
        val genres = res.genres?.mapNotNull { it.name } ?: emptyList()
        val imdbId = res.imdbId ?: res.externalIds?.imdbId

        val actors = res.credits?.cast?.take(15)?.mapNotNull { cast ->
            val name = cast.name ?: cast.originalName ?: return@mapNotNull null
            ActorData(Actor(name, getImageUrl(cast.profilePath)), roleString = cast.character)
        } ?: emptyList()

        val recommendations = res.recommendations?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val trailer = res.videos?.results.orEmpty().filter { it.type == "Trailer" || it.type == "Teaser" }
            .map { "https://www.youtube.com/watch?v=${it.key}" }

        return if (type == TvType.Movie) {
            val linkData = LinkData(id = data.id, title = title, year = year, isMovie = true, imdbId = imdbId)
            newMovieLoadResponse(title, url, TvType.Movie, linkData.toJson()) {
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
            val seasons = res.seasons?.filter { it.seasonNumber != null && it.seasonNumber > 0 } ?: emptyList()
            val episodes = seasons.mapNotNull { season ->
                app.get(
                    "$tmdbAPI/tv/${data.id}/season/${season.seasonNumber}?language=it-IT",
                    headers = authHeaders
                ).parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                    val linkData = LinkData(
                        id = data.id, title = title, year = year,
                        season = eps.seasonNumber, episode = eps.episodeNumber,
                        isMovie = false, imdbId = imdbId
                    )
                    newEpisode(linkData.toJson()) {
                        this.name = eps.name ?: "Episodio ${eps.episodeNumber}"
                        this.season = eps.seasonNumber
                        this.episode = eps.episodeNumber
                        this.posterUrl = getImageUrl(eps.stillPath)
                        this.description = eps.overview
                        this.score = eps.voteAverage?.let { Score.from10(it) }
                        this.runTime = eps.runtime
                        this.addDate(eps.airDate)
                    }
                }
            }.flatten()

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val tmdbId = linkData.id ?: return false
        var anySuccess = false

        coroutineScope {
            val extractors = StreamITAExtractors(
                scope = this,
                subtitleCallback = subtitleCallback,
                callback = callback,
                onSuccess = { anySuccess = true }
            )

            if (linkData.isMovie && linkData.imdbId != null) {
                extractors.loadMovieExtractors(linkData.imdbId)
            }

            extractors.loadCommonExtractors(tmdbId, linkData.season, linkData.episode)
        }

        return anySuccess
    }

    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link.isNullOrBlank()) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width$link" else link
    }
}
