package it.dogior.hadEnough

import android.content.SharedPreferences
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
import org.json.JSONObject

class StreamITA(
    private val sharedPref: SharedPreferences?
) : TmdbProvider() {
    override var name = "StreamITA"
    override var lang: String = sharedPref?.getString(StreamITAPlugin.PREF_LANG, "it-IT") ?: "it-IT"
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

    private val apiLang: String
        get() = sharedPref?.getString(StreamITAPlugin.PREF_LANG, "it-IT") ?: "it-IT"

    private val showLogo: Boolean
        get() = sharedPref?.getBoolean(StreamITAPlugin.PREF_SHOW_LOGO, false) ?: false

    private val cacheSeconds: Int
        get() = (sharedPref?.getInt(StreamITAPlugin.PREF_CACHE_HOURS, 24) ?: 24) * 3600

    private fun getSectionName(key: String): String {
        val langCode = apiLang.substringBefore("-")
        return when (key) {
            "trending" -> when (langCode) {
                "en" -> "Trending Today"
                "es" -> "Tendencias de Hoy"
                "fr" -> "Tendances du Jour"
                "de" -> "Trends von Heute"
                else -> "Tendenze di Oggi"
            }
            "popular_movies" -> when (langCode) {
                "en" -> "Popular Movies"
                "es" -> "Películas Populares"
                "fr" -> "Films Populaires"
                "de" -> "Beliebte Filme"
                else -> "Film Popolari"
            }
            "popular_tv" -> when (langCode) {
                "en" -> "Popular TV Shows"
                "es" -> "Series Populares"
                "fr" -> "Séries Populaires"
                "de" -> "Beliebte Serien"
                else -> "Serie TV Popolari"
            }
            "trending_movies" -> when (langCode) {
                "en" -> "Trending Movies"
                "es" -> "Películas del Momento"
                "fr" -> "Films du Moment"
                "de" -> "Filme der Woche"
                else -> "Film della Settimana"
            }
            "trending_tv" -> when (langCode) {
                "en" -> "Trending TV Shows"
                "es" -> "Series del Momento"
                "fr" -> "Séries du Moment"
                "de" -> "Serien der Woche"
                else -> "Serie TV della Settimana"
            }
            "top_movies" -> when (langCode) {
                "en" -> "Top Rated Movies"
                "es" -> "Películas Mejor Valoradas"
                "fr" -> "Films les Mieux Notés"
                "de" -> "Bestbewertete Filme"
                else -> "Film più Votati"
            }
            "top_tv" -> when (langCode) {
                "en" -> "Top Rated TV Shows"
                "es" -> "Series Mejor Valoradas"
                "fr" -> "Séries les Mieux Notées"
                "de" -> "Bestbewertete Serien"
                else -> "Serie TV più Votate"
            }
            "upcoming" -> when (langCode) {
                "en" -> "Upcoming"
                "es" -> "Próximamente"
                "fr" -> "Prochainement"
                "de" -> "Demnächst"
                else -> "Prossime Uscite"
            }
            "on_air" -> when (langCode) {
                "en" -> "On TV"
                "es" -> "En TV"
                "fr" -> "À la Télé"
                "de" -> "Im Fernsehen"
                else -> "Serie TV in Onda"
            }
            "airing_today" -> when (langCode) {
                "en" -> "Airing Today"
                "es" -> "Se Emite Hoy"
                "fr" -> "Diffusé Aujourd'hui"
                "de" -> "Heute ausgestrahlt"
                else -> "In Onda Oggi"
            }
            "netflix" -> "Netflix"
            "amazon" -> "Amazon Prime"
            "disney" -> "Disney+"
            "hulu" -> "Hulu"
            "apple" -> "Apple TV+"
            "hbo" -> "HBO"
            "paramount" -> "Paramount+"
            "peacock" -> "Peacock"
            "anime_movies" -> when (langCode) {
                "en" -> "Anime Movies"
                "es" -> "Películas de Anime"
                "fr" -> "Films d'Anime"
                "de" -> "Anime Filme"
                else -> "Anime Film"
            }
            "anime_tv" -> when (langCode) {
                "en" -> "Anime TV"
                "es" -> "Anime TV"
                "fr" -> "Anime TV"
                "de" -> "Anime TV"
                else -> "Anime TV"
            }
            "korean" -> when (langCode) {
                "en" -> "Korean Shows"
                "es" -> "Series Coreanas"
                "fr" -> "Séries Coréennes"
                "de" -> "Koreanische Serien"
                else -> "Serie Coreane"
            }
            "documentaries" -> when (langCode) {
                "en" -> "Documentaries"
                "es" -> "Documentales"
                "fr" -> "Documentaires"
                "de" -> "Dokumentationen"
                else -> "Documentari"
            }
            else -> key
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?language=$apiLang" to getSectionName("trending"),
        "$tmdbAPI/movie/popular?language=$apiLang" to getSectionName("popular_movies"),
        "$tmdbAPI/tv/popular?language=$apiLang" to getSectionName("popular_tv"),
        "$tmdbAPI/trending/movie/week?language=$apiLang" to getSectionName("trending_movies"),
        "$tmdbAPI/trending/tv/week?language=$apiLang" to getSectionName("trending_tv"),
        "$tmdbAPI/movie/top_rated?language=$apiLang" to getSectionName("top_movies"),
        "$tmdbAPI/tv/top_rated?language=$apiLang" to getSectionName("top_tv"),
        "$tmdbAPI/movie/upcoming?language=$apiLang&region=IT" to getSectionName("upcoming"),
        "$tmdbAPI/tv/on_the_air?language=$apiLang" to getSectionName("on_air"),
        "$tmdbAPI/tv/airing_today?language=$apiLang" to getSectionName("airing_today"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_networks=213" to getSectionName("netflix"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_networks=1024" to getSectionName("amazon"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_networks=2739" to getSectionName("disney"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_networks=453" to getSectionName("hulu"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_networks=2552" to getSectionName("apple"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_networks=49" to getSectionName("hbo"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_networks=4330" to getSectionName("paramount"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_networks=3353" to getSectionName("peacock"),
        "$tmdbAPI/discover/movie?language=$apiLang&with_keywords=210024|222243&sort_by=popularity.desc" to getSectionName("anime_movies"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_keywords=210024|222243&sort_by=popularity.desc" to getSectionName("anime_tv"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_original_language=ko" to getSectionName("korean"),
        "$tmdbAPI/discover/tv?language=$apiLang&with_genres=99" to getSectionName("documentaries"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp = app.get("${request.data}&page=$page", headers = authHeaders, cacheTime = cacheSeconds).body.string()
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
        val url = "$tmdbAPI/search/multi?language=$apiLang&query=$query&include_adult=${settingsForProvider.enableAdult}"
        val response = app.get(url, headers = authHeaders, cacheTime = cacheSeconds)
        if (!response.isSuccessful) return null
        return response.parsedSafe<Results>()?.results
            ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            ?.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load() URL: $url")

        val data: Data = try {
            parseJson<Data>(url)
        } catch (_: Exception) {
            val tmdbRegex = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
            val match = tmdbRegex.find(url)
            if (match != null) {
                val type = match.groupValues[1]
                val id = match.groupValues[2].toInt()
                Log.d(TAG, "Estratto da URL TMDB: type=$type, id=$id")
                Data(id, type)
            } else {
                Log.e(TAG, "URL non riconosciuto: $url")
                throw ErrorLoadingException("URL non valido")
            }
        }

        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        val append = "credits,videos,recommendations,external_ids"
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?language=$apiLang&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?language=$apiLang&append_to_response=$append"
        }

        var res = try {
            withTimeoutOrNull(10000) {
                app.get(resUrl, headers = authHeaders, cacheTime = cacheSeconds).parsedSafe<MediaDetail>()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore richiesta: ${e.message}")
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
                    app.get(enUrl, headers = authHeaders, cacheTime = cacheSeconds).parsedSafe<MediaDetail>()
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
        val logoUrl = if (showLogo && data.id != null) fetchTmdbLogoUrl(type, data.id, apiLang) else null

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
                if (logoUrl != null) this.logoUrl = logoUrl
            }
        } else {
            val seasons = res.seasons?.filter { it.seasonNumber != null && it.seasonNumber > 0 } ?: emptyList()
            val episodes = seasons.mapNotNull { season ->
                app.get(
                    "$tmdbAPI/tv/${data.id}/season/${season.seasonNumber}?language=$apiLang",
                    headers = authHeaders,
                    cacheTime = cacheSeconds
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
                if (logoUrl != null) this.logoUrl = logoUrl
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

    private suspend fun fetchTmdbLogoUrl(
        type: TvType,
        tmdbId: Int?,
        appLangCode: String?
    ): String? {
        if (tmdbId == null) return null
        return try {
            val appLang = appLangCode?.substringBefore("-")?.lowercase()
            val url = if (type == TvType.Movie) {
                "$tmdbAPI/movie/$tmdbId/images"
            } else {
                "$tmdbAPI/tv/$tmdbId/images"
            }
            val response = app.get(url, headers = authHeaders, cacheTime = cacheSeconds)
            if (!response.isSuccessful) return null
            val jsonText = response.body?.string() ?: return null
            val json = JSONObject(jsonText)
            val logos = json.optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null

            fun logoUrlAt(i: Int): String {
                val logo = logos.getJSONObject(i)
                val filePath = logo.optString("file_path", "")
                return "https://image.tmdb.org/t/p/w500$filePath"
            }

            fun isSvg(i: Int): Boolean {
                val logo = logos.getJSONObject(i)
                val filePath = logo.optString("file_path", "")
                return filePath.endsWith(".svg", ignoreCase = true)
            }

            if (!appLang.isNullOrBlank()) {
                var svgFallback: String? = null
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    if (logo.optString("iso_639_1") == appLang) {
                        if (isSvg(i)) {
                            if (svgFallback == null) svgFallback = logoUrlAt(i)
                        } else {
                            return logoUrlAt(i)
                        }
                    }
                }
                if (svgFallback != null) return svgFallback
            }

            var enSvgFallback: String? = null
            for (i in 0 until logos.length()) {
                val logo = logos.getJSONObject(i)
                if (logo.optString("iso_639_1") == "en") {
                    if (isSvg(i)) {
                        if (enSvgFallback == null) enSvgFallback = logoUrlAt(i)
                    } else {
                        return logoUrlAt(i)
                    }
                }
            }
            if (enSvgFallback != null) return enSvgFallback

            for (i in 0 until logos.length()) {
                if (!isSvg(i)) return logoUrlAt(i)
            }

            if (logos.length() > 0) logoUrlAt(0) else null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching TMDB logo: ${e.message}")
            null
        }
    }

    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link.isNullOrBlank()) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width$link" else link
    }
}
