package it.dogior.hadEnough

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.VidSrcExtractor
import it.dogior.hadEnough.extractors.VixCloudExtractor
import it.dogior.hadEnough.extractors.VixSrcExtractor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.jsoup.parser.Parser

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
    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val tmdbApiKey = BuildConfig.TMDB_API
    private val authHeaders = mapOf(
        "Authorization" to "Bearer $tmdbApiKey",
        "Accept" to "application/json"
    )
    private val scMainUrl = "https://streamingunity.biz"

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
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val response = app.get(url, headers = authHeaders)
        if (!response.isSuccessful) throw ErrorLoadingException("Errore homepage: ${response.code}")
        val home = response.parsedSafe<Results>()?.results?.mapNotNull { it.toSearchResponse() }
            ?: throw ErrorLoadingException("Risposta JSON non valida")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        if (mediaType == "person") return null
        val title = title ?: name ?: originalTitle ?: return null
        val mediaType = mediaType ?: "movie"
        val tvType = if (mediaType == "movie") TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(title, Data(id = id, type = mediaType).toJson(), tvType) {
            this.posterUrl = getImageUrl(posterPath, true)
            this.score = voteAverage?.let { Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$tmdbAPI/search/multi?language=it-IT&query=$query&include_adult=${settingsForProvider.enableAdult}"
        val response = app.get(url, headers = authHeaders)
        if (!response.isSuccessful) return null
        return response.parsedSafe<Results>()?.results?.filter { it.mediaType != "person" }
            ?.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        val append = "credits,videos,recommendations,external_ids"
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?language=it-IT&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?language=it-IT&append_to_response=$append"
        }

        val response = app.get(resUrl, headers = authHeaders)
        if (!response.isSuccessful) throw ErrorLoadingException("Errore dettagli: ${response.code}")
        val res = response.parsedSafe<MediaDetail>() ?: throw ErrorLoadingException("Risposta JSON non valida")

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
            val episodes = mutableListOf<Episode>()
            seasons.forEach { season ->
                val seasonNumber = season.seasonNumber ?: return@forEach
                try {
                    val seasonUrl = "$tmdbAPI/tv/${data.id}/season/$seasonNumber?language=it-IT"
                    val seasonResponse = app.get(seasonUrl, headers = authHeaders)
                    if (!seasonResponse.isSuccessful) return@forEach
                    val seasonDetails = seasonResponse.parsedSafe<MediaDetailEpisodes>()
                    seasonDetails?.episodes?.forEach { eps ->
                        eps.episodeNumber?.let { epNum ->
                            val linkData = LinkData(id = data.id, title = title, year = year, season = seasonNumber, episode = epNum, isMovie = false, imdbId = imdbId)
                            episodes.add(newEpisode(linkData.toJson()) {
                                this.name = eps.name ?: "Episodio $epNum"
                                this.season = seasonNumber
                                this.episode = epNum
                                this.posterUrl = getImageUrl(eps.stillPath)
                                this.description = eps.overview
                                this.score = eps.voteAverage?.let { Score.from10(it) }
                                this.runTime = eps.runtime
                                this.addDate(eps.airDate)
                            })
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Stagione $seasonNumber fallita: ${e.message}")
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

    private suspend fun getVixCloudFromStreamingCommunity(title: String, year: Int?, season: Int?, episode: Int?): String? {
        try {
            val searchQuery = if (season == null) "$title $year" else title
            val encodedQuery = java.net.URLEncoder.encode(searchQuery, "UTF-8")
            val searchUrl = "$scMainUrl/search?q=$encodedQuery"

            val response = app.get(searchUrl)
            if (!response.isSuccessful) return null

            val html = response.text
            val inertiaJson = org.jsoup.Jsoup.parse(html)
                .select("#app")
                .attr("data-page")
                .takeIf { it.isNotBlank() }
                ?: return null

            val unescapedJson = Parser.unescapeEntities(inertiaJson, true)
            val json = JSONObject(unescapedJson)
            val titles = json.optJSONObject("props")?.optJSONArray("titles") ?: return null

            var matchedTitle: JSONObject? = null
            for (i in 0 until titles.length()) {
                val t = titles.getJSONObject(i)
                val tTitle = t.optString("name", "")
                val tType = t.optString("type", "")
                val tYear = t.optString("release_date", "").substringBefore("-").toIntOrNull()

                val isMatch = when {
                    season != null -> tType == "tv" && tTitle.equals(title, ignoreCase = true)
                    else -> tType == "movie" && tTitle.equals(title, ignoreCase = true) && (tYear == null || tYear == year)
                }

                if (isMatch) {
                    matchedTitle = t
                    break
                }
            }

            if (matchedTitle == null) return null

            val titleId = matchedTitle.optInt("id", 0)
            val titleSlug = matchedTitle.optString("slug", "")
            if (titleId == 0) return null

            val pageUrl = if (season == null) {
                "$scMainUrl/titles/$titleId-$titleSlug"
            } else {
                "$scMainUrl/titles/$titleId-$titleSlug/season-$season"
            }

            val pageResponse = app.get(pageUrl)
            if (!pageResponse.isSuccessful) return null

            val pageHtml = pageResponse.text
            val pageJson = org.jsoup.Jsoup.parse(pageHtml)
                .select("#app")
                .attr("data-page")
                .takeIf { it.isNotBlank() }
                ?: return null

            val pageUnescaped = Parser.unescapeEntities(pageJson, true)
            val pageData = JSONObject(pageUnescaped)
            val props = pageData.optJSONObject("props") ?: return null
            val titleData = props.optJSONObject("title") ?: return null

            val iframeId = if (season == null) {
                titleData.optInt("id", 0)
            } else {
                val loadedSeason = props.optJSONObject("loadedSeason")
                val episodes = loadedSeason?.optJSONArray("episodes")
                var episodeId = 0
                if (episodes != null) {
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.getJSONObject(i)
                        if (ep.optInt("number", 0) == episode) {
                            episodeId = ep.optInt("id", 0)
                            break
                        }
                    }
                }
                episodeId
            }

            if (iframeId == 0) return null

            return "https://vixcloud.co/embed/$iframeId"

        } catch (e: Exception) {
            Log.e(TAG, "VixCloud search failed: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val linkData = parseJson<LinkData>(data)
        val tmdbId = linkData.id ?: return false
        var anySuccess = false

        coroutineScope {
            launch {
                try {
                    val extractor = VixSrcExtractor()
                    val url = if (linkData.season == null) "https://vixsrc.to/movie/$tmdbId" else "https://vixsrc.to/tv/$tmdbId/${linkData.season}/${linkData.episode}"
                    extractor.getUrl(url, "https://vixsrc.to/", subtitleCallback, callback)
                    anySuccess = true
                } catch (_: Exception) {}
            }
            launch {
                try {
                    val extractor = VidSrcExtractor()
                    val url = if (linkData.season == null) "https://vidsrc.ru/movie/$tmdbId" else "https://vidsrc.ru/tv/$tmdbId/${linkData.season}/${linkData.episode}"
                    extractor.getUrl(url, "https://vidsrc.ru/", subtitleCallback, callback)
                    anySuccess = true
                } catch (_: Exception) {}
            }
            launch {
                try {
                    val vixCloudUrl = getVixCloudFromStreamingCommunity(
                        title = linkData.title ?: return@launch,
                        year = linkData.year,
                        season = linkData.season,
                        episode = linkData.episode
                    )
                    if (vixCloudUrl != null) {
                        val extractor = VixCloudExtractor()
                        extractor.getUrl(vixCloudUrl, "$scMainUrl/", subtitleCallback, callback)
                        anySuccess = true
                    }
                } catch (_: Exception) {}
            }
        }
        return anySuccess
    }

    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link.isNullOrBlank()) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width$link" else link
    }

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
        val id: Int? = null, val imdbId: String? = null, val type: String? = null,
        val season: Int? = null, val episode: Int? = null, val title: String? = null,
        val year: Int? = null, val isMovie: Boolean = false, val episodeTitle: String? = null,
        val episodeOverview: String? = null,
    )
    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null, @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null, @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null, @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null, @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null, @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Any? = null, @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
    )
    data class ExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)
    data class Seasons(@JsonProperty("season_number") val seasonNumber: Int? = null, @JsonProperty("air_date") val airDate: String? = null)
    data class MediaDetailEpisodes(@JsonProperty("episodes") val episodes: ArrayList<EpisodeData>? = arrayListOf())
    data class EpisodeData(
        @JsonProperty("id") val id: Int? = null, @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null, @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null, @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null, @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
    )
    data class Trailers(@JsonProperty("key") val key: String? = null, @JsonProperty("type") val type: String? = null)
    data class ResultsTrailer(@JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf())
    data class Cast(
        @JsonProperty("name") val name: String? = null, @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null, @JsonProperty("profile_path") val profilePath: String? = null,
    )
    data class Credits(@JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf())
    data class ResultsRecommendations(@JsonProperty("results") val results: ArrayList<Media>? = arrayListOf())
    data class Genres(@JsonProperty("name") val name: String? = null)
}
