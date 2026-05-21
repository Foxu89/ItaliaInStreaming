package it.dogior.hadEnough

import android.content.SharedPreferences
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.random.Random
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Semaphore

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

    private val apiLang: String get() = sharedPref?.getString(StreamITAPlugin.PREF_LANG, "it-IT") ?: "it-IT"
    private val showLogo: Boolean get() = sharedPref?.getBoolean(StreamITAPlugin.PREF_SHOW_LOGO, false) ?: false
    private val showRating: Boolean get() = sharedPref?.getBoolean(StreamITAPlugin.PREF_SHOW_RATING, false) ?: false

    // ==================== CACHE HELPER ====================

    private suspend fun cachedApiGet(
        url: String,
        cacheKey: String,
        profile: StreamITACache.CacheProfile,
        headers: Map<String, String> = authHeaders
    ): String {
        StreamITACache.get(cacheKey)?.let { return it }
        return try {
            val text = app.get(url, timeout = 15000, headers = headers).text
            StreamITACache.put(cacheKey, text, profile)
            text
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "cachedApiGet fallisce, provo cache scaduta: ${e.message}")
            StreamITACache.get(cacheKey, allowExpired = true) ?: run {
                StreamITALogger.log(TAG, "cachedApiGet riprovo con timeout maggiore")
                val text = app.get(url, timeout = 30000, headers = headers).text
                StreamITACache.put(cacheKey, text, profile)
                text
            }
        }
    }

    // ==================== SEZIONE NOMI ====================

    private fun getSectionName(key: String): String {
        val langCode = apiLang.substringBefore("-")
        return when (key) {
            "trending" -> when (langCode) { "en" -> "Trending Today"; "es" -> "Tendencias de Hoy"; "fr" -> "Tendances du Jour"; "de" -> "Trends von Heute"; else -> "Tendenze di Oggi" }
            "popular_movies" -> when (langCode) { "en" -> "Popular Movies"; "es" -> "Películas Populares"; "fr" -> "Films Populaires"; "de" -> "Beliebte Filme"; else -> "Film Popolari" }
            "popular_tv" -> when (langCode) { "en" -> "Popular TV Shows"; "es" -> "Series Populares"; "fr" -> "Séries Populaires"; "de" -> "Beliebte Serien"; else -> "Serie TV Popolari" }
            "trending_movies" -> when (langCode) { "en" -> "Trending Movies"; "es" -> "Películas del Momento"; "fr" -> "Films du Moment"; "de" -> "Filme der Woche"; else -> "Film della Settimana" }
            "trending_tv" -> when (langCode) { "en" -> "Trending TV Shows"; "es" -> "Series del Momento"; "fr" -> "Séries du Moment"; "de" -> "Serien der Woche"; else -> "Serie TV della Settimana" }
            "top_movies" -> when (langCode) { "en" -> "Top Rated Movies"; "es" -> "Películas Mejor Valoradas"; "fr" -> "Films les Mieux Notés"; "de" -> "Bestbewertete Filme"; else -> "Film più Votati" }
            "top_tv" -> when (langCode) { "en" -> "Top Rated TV Shows"; "es" -> "Series Mejor Valoradas"; "fr" -> "Séries les Mieux Notées"; "de" -> "Bestbewertete Serien"; else -> "Serie TV più Votate" }
            "upcoming" -> when (langCode) { "en" -> "Upcoming"; "es" -> "Próximamente"; "fr" -> "Prochainement"; "de" -> "Demnächst"; else -> "Prossime Uscite" }
            "on_air" -> when (langCode) { "en" -> "On TV"; "es" -> "En TV"; "fr" -> "À la Télé"; "de" -> "Im Fernsehen"; else -> "Serie TV in Onda" }
            "airing_today" -> when (langCode) { "en" -> "Airing Today"; "es" -> "Se Emite Hoy"; "fr" -> "Diffusé Aujourd'hui"; "de" -> "Heute ausgestrahlt"; else -> "In Onda Oggi" }
            "netflix" -> "Netflix"; "amazon" -> "Amazon Prime"; "disney" -> "Disney+"; "hulu" -> "Hulu"; "apple" -> "Apple TV+"; "hbo" -> "HBO"; "paramount" -> "Paramount+"; "peacock" -> "Peacock"
            "anime_movies" -> when (langCode) { "en" -> "Anime Movies"; "es" -> "Películas de Anime"; "fr" -> "Films d'Anime"; "de" -> "Anime Filme"; else -> "Anime Film" }
            "anime_tv" -> when (langCode) { "en" -> "Anime TV"; "es" -> "Anime TV"; "fr" -> "Anime TV"; "de" -> "Anime TV"; else -> "Anime TV" }
            "korean" -> when (langCode) { "en" -> "Korean Shows"; "es" -> "Series Coreanas"; "fr" -> "Séries Coréennes"; "de" -> "Koreanische Serien"; else -> "Serie Coreane" }
            "documentaries" -> when (langCode) { "en" -> "Documentaries"; "es" -> "Documentales"; "fr" -> "Documentaires"; "de" -> "Dokumentationen"; else -> "Documentari" }
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
        StreamITALogger.log(TAG, "Caricamento homepage: ${request.name} (pagina $page, lingua=$apiLang)")
        val cacheKey = "TMDB:HOME:${URLEncoder.encode(request.data, "UTF-8")}:$page:$apiLang"
        val resp = cachedApiGet("${request.data}&page=$page&random=${Random.nextInt()}", cacheKey, StreamITACache.CacheProfile.TMDB_HOME)
        val type = if (request.data.contains("tv")) "tv" else "movie"
        val parsedResponse = parseJson<Results>(resp).results?.mapNotNull { media -> media.toSearchResponse(type = type) }
        val home = parsedResponse ?: throw ErrorLoadingException("Invalid Json response")
        StreamITALogger.log(TAG, "Homepage caricata: ${home.size} elementi in ${request.name}")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String = "tv"): SearchResponse? {
        if (mediaType == "person") return null
        val title = title ?: name ?: originalTitle ?: return null
        return newMovieSearchResponse(title, Data(id = id, type = mediaType ?: type).toJson(), TvType.Movie) {
            this.posterUrl = getImageUrl(posterPath, true)
            this.score = if (showRating) voteAverage?.let { Score.from10(it) } else null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        StreamITALogger.log(TAG, "Ricerca: '$query' (lingua=$apiLang)")
        val url = "$tmdbAPI/search/multi?language=$apiLang&query=$query&include_adult=${settingsForProvider.enableAdult}"
        val cacheKey = "TMDB:SEARCH:${URLEncoder.encode(query, "UTF-8")}:$apiLang"
        val response = try {
            cachedApiGet(url, cacheKey, StreamITACache.CacheProfile.TMDB_SEARCH)
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "Ricerca fallita: ${e.message}")
            return null
        }
        val results = parseJson<Results>(response)?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }?.mapNotNull { it.toSearchResponse() }
        StreamITALogger.log(TAG, "Ricerca completata: ${results?.size ?: 0} risultati per '$query'")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        StreamITALogger.log(TAG, "Load chiamato con URL: $url")
        val data: Data = try { parseJson<Data>(url) } catch (_: Exception) {
            val tmdbRegex = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
            val match = tmdbRegex.find(url)
            if (match != null) { val type = match.groupValues[1]; val id = match.groupValues[2].toInt(); StreamITALogger.log(TAG, "URL TMDB diretto: type=$type, id=$id"); Data(id, type) }
            else { StreamITALogger.log(TAG, "URL non riconosciuto: $url"); throw ErrorLoadingException("URL non valido") }
        }

        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        StreamITALogger.log(TAG, "Caricamento dettagli: tipo=${data.type}, id=${data.id}, lingua=$apiLang")
        val append = "credits,videos,recommendations,external_ids"
        val resUrl = if (type == TvType.Movie) "$tmdbAPI/movie/${data.id}?language=$apiLang&append_to_response=$append" else "$tmdbAPI/tv/${data.id}?language=$apiLang&append_to_response=$append"

        val detailCacheKey = "TMDB:DETAIL:${data.id}:${data.type}:$apiLang"
        var res = StreamITACache.get(detailCacheKey)?.let { json ->
            try { parseJson<MediaDetail>(json) } catch (_: Exception) { null }
        }

        if (res == null) {
            res = try { 
                withTimeoutOrNull(10000) { 
                    app.get(resUrl, headers = authHeaders).parsedSafe<MediaDetail>() 
                } 
            } catch (e: Exception) { 
                StreamITALogger.log(TAG, "Errore richiesta dettagli (${apiLang}): ${e.message}")
                null 
            }
        }

        if (res == null) {
            StreamITALogger.log(TAG, "Fallback a EN per id=${data.id}")
            val enUrl = if (type == TvType.Movie) "$tmdbAPI/movie/${data.id}?language=en-US&append_to_response=$append" else "$tmdbAPI/tv/${data.id}?language=en-US&append_to_response=$append"
            res = try { 
                withTimeoutOrNull(8000) { 
                    app.get(enUrl, headers = authHeaders).parsedSafe<MediaDetail>() 
                } 
            } catch (e: Exception) { 
                StreamITALogger.log(TAG, "Errore richiesta EN: ${e.message}")
                null 
            }
            if (res != null) {
                val enCacheKey = "TMDB:DETAIL:${data.id}:${data.type}:en-US"
                StreamITACache.put(enCacheKey, res.toJson(), StreamITACache.CacheProfile.TMDB_DETAIL)
            }
        } else {
            StreamITACache.put(detailCacheKey, res.toJson(), StreamITACache.CacheProfile.TMDB_DETAIL)
        }
        if (res == null) { StreamITALogger.log(TAG, "Contenuto non disponibile per id=${data.id}"); throw ErrorLoadingException("Contenuto non disponibile") }

        val title = res.title ?: res.name ?: return null
        StreamITALogger.log(TAG, "Dettagli caricati: '$title' (${data.type})")
        val poster = getImageUrl(res.posterPath, true)
        val bgPoster = getImageUrl(res.backdropPath, true)
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val plot = res.overview ?: "Nessuna descrizione disponibile."
        val rating = res.voteAverage?.toString()
        val genres = res.genres?.mapNotNull { it.name } ?: emptyList()
        val imdbId = res.imdbId ?: res.externalIds?.imdbId
        val logoUrl = if (showLogo && data.id != null) { StreamITALogger.log(TAG, "Caricamento logo per id=${data.id}"); fetchTmdbLogoUrl(type, data.id, apiLang) } else null
        if (logoUrl != null) StreamITALogger.log(TAG, "Logo trovato per '$title'")
        val comingSoonFlag = when (res.status?.lowercase()) {
            "released" -> false
            "post production", "in production", "planned" -> true
            else -> isUpcoming(res.releaseDate ?: res.firstAirDate)
        }

        val actors = res.credits?.cast?.take(15)?.mapNotNull { cast ->
            val name = cast.name ?: cast.originalName ?: return@mapNotNull null
            ActorData(Actor(name, getImageUrl(cast.profilePath)), roleString = cast.character)
        } ?: emptyList()
        val recommendations = res.recommendations?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val trailer = res.videos?.results.orEmpty().filter { it.type == "Trailer" || it.type == "Teaser" }.map { "https://www.youtube.com/watch?v=${it.key}" }

        return if (type == TvType.Movie) {
            val linkData = LinkData(id = data.id, title = title, year = year, isMovie = true, imdbId = imdbId)
            StreamITALogger.log(TAG, "Film creato: '$title'")
            newMovieLoadResponse(title, url, TvType.Movie, linkData.toJson()) {
                this.posterUrl = poster; this.backgroundPosterUrl = bgPoster; this.year = year; this.plot = plot; this.duration = res.runtime
                this.score = if (showRating) rating?.let { Score.from10(it) } else null; this.actors = actors; this.tags = genres; this.recommendations = recommendations
                addTrailer(trailer); imdbId?.let { addImdbId(it) }; if (logoUrl != null) this.logoUrl = logoUrl
                this.comingSoon = comingSoonFlag
            }
        } else {
            val seasons = res.seasons?.filter { it.seasonNumber != null && it.seasonNumber > 0 } ?: emptyList()
            StreamITALogger.log(TAG, "Serie TV: '$title', ${seasons.size} stagioni")
            val episodes = seasons.mapNotNull { season ->
                val seasonNumber = season.seasonNumber ?: return@mapNotNull null
                try {
                    val seasonCacheKey = "TMDB:SEASON:${data.id}:$seasonNumber:$apiLang"
                    val seasonJson = cachedApiGet(
                        "$tmdbAPI/tv/${data.id}/season/$seasonNumber?language=$apiLang",
                        seasonCacheKey,
                        StreamITACache.CacheProfile.TMDB_SEASONS
                    )
                    parseJson<MediaDetailEpisodes>(seasonJson)?.episodes?.map { eps ->
                        val linkData = LinkData(id = data.id, title = title, year = year, season = eps.seasonNumber, episode = eps.episodeNumber, isMovie = false, imdbId = imdbId)
                        newEpisode(linkData.toJson()) {
                            this.name = eps.name ?: "Episodio ${eps.episodeNumber}"; this.season = eps.seasonNumber; this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath); this.description = eps.overview
                            this.score = if (showRating) eps.voteAverage?.let { Score.from10(it) } else null; this.runTime = eps.runtime; this.addDate(eps.airDate)
                        }
                    }
                } catch (e: Exception) {
                    StreamITALogger.log(TAG, "Errore caricamento stagione $seasonNumber: ${e.message}")
                    null
                }
            }.flatten()
            StreamITALogger.log(TAG, "Episodi caricati: ${episodes.size} totali")
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.backgroundPosterUrl = bgPoster; this.year = year; this.plot = plot
                this.score = if (showRating) rating?.let { Score.from10(it) } else null; this.actors = actors; this.tags = genres; this.recommendations = recommendations
                addTrailer(trailer); imdbId?.let { addImdbId(it) }; if (logoUrl != null) this.logoUrl = logoUrl
                this.showStatus = when (res.status) {
                    "Returning Series" -> ShowStatus.Ongoing
                    else -> ShowStatus.Completed
                }
                this.comingSoon = comingSoonFlag
            }
        }
    }

    private val defaultExtractorOrder = listOf(
        "vixsrc", "vidxgo", "cinemacity", "guardahd", "vidsrc",
        "animeunity", "animeworld", "animesaturn", "subtitle"
    )

    private fun readExtractorOrder(): List<String> {
        val saved = sharedPref?.getString(StreamITAPlugin.PREF_EXTRACTOR_ORDER, null)
        if (saved != null) {
            try { return parseJson<List<String>>(saved) } catch (_: Exception) {}
        }
        return defaultExtractorOrder
    }

    private fun readExtractorConcurrency(): Int {
        return sharedPref?.getInt(StreamITAPlugin.PREF_EXTRACTOR_CONCURRENCY, 3) ?: 3
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

        StreamITALogger.log(TAG, "Ricerca link: tmdbId=$tmdbId, isMovie=${linkData.isMovie}")

        val order = readExtractorOrder()
        val concurrency = readExtractorConcurrency()
        val semaphore = Semaphore(concurrency)

        val extractors = StreamITAExtractors(
            subtitleCallback = subtitleCallback,
            callback = callback,
            onSuccess = { anySuccess = true },
            sharedPref = sharedPref
        )

        coroutineScope {
            for (key in order) {
                if (key == "subtitle") {
                    launch {
                        try { runSubtitles(linkData, subtitleCallback) }
                        catch (e: Exception) { StreamITALogger.log(TAG, "Sottotitoli falliti: ${e.message}") }
                    }
                    continue
                }

                launch {
                    try {
                        semaphore.acquire()
                    } catch (_: InterruptedException) {
                        return@launch
                    }
                    try {
                        val success = executeOrderedExtractor(key, linkData, tmdbId, extractors, subtitleCallback, callback)
                        if (success) {
                            anySuccess = true
                            StreamITALogger.log(TAG, "Link trovato da $key per tmdbId=$tmdbId")
                        }
                    } catch (e: Exception) {
                        StreamITALogger.log(TAG, "$key fallito: ${e.message}")
                    } finally {
                        semaphore.release()
                    }
                }
            }

            // Always run subtitle if not already in order list
            if ("subtitle" !in order) {
                launch {
                    try { runSubtitles(linkData, subtitleCallback) }
                    catch (e: Exception) { StreamITALogger.log(TAG, "Sottotitoli falliti: ${e.message}") }
                }
            }
        }

        StreamITALogger.log(TAG, "Risultato ricerca link: successo=$anySuccess")
        return anySuccess
    }

    private suspend fun executeOrderedExtractor(
        key: String,
        linkData: LinkData,
        tmdbId: Int,
        extractors: StreamITAExtractors,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = when (key) {
        "guardahd" -> {
            if (linkData.isMovie && linkData.imdbId != null)
                extractors.tryGuardahd(linkData.imdbId)
            else false
        }
        "vixsrc" -> extractors.tryVixSrc(tmdbId, linkData.season, linkData.episode)
        "vidsrc" -> extractors.tryVidSrc(tmdbId, linkData.season, linkData.episode)
        "vidxgo" -> {
            if (linkData.imdbId != null)
                extractors.tryVidxGo(linkData.imdbId, linkData.season, linkData.episode)
            else false
        }
        "cinemacity" -> runCinemaCity(linkData, subtitleCallback, callback)
        "animeunity" -> runAnimeUnity(linkData, subtitleCallback, callback)
        "animeworld" -> runAnimeWorld(linkData, subtitleCallback, callback)
        "animesaturn" -> runAnimeSaturn(linkData, callback)
        else -> false
    }

    private suspend fun runSubtitles(linkData: LinkData, subtitleCallback: (SubtitleFile) -> Unit) {
        if (sharedPref?.getBoolean(StreamITAPlugin.extractorEnabledKey("subtitle"), true) != true) return
        linkData.imdbId?.let { imdbId ->
            StreamITASubtitles.loadWyzieSubs(imdbId, linkData.season, linkData.episode, subtitleCallback)
            StreamITASubtitles.loadOpenSubtitles(imdbId, linkData.season, linkData.episode, subtitleCallback)
        }
    }

    private suspend fun runCinemaCity(
        linkData: LinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isExtractorEnabled("cinemacity", true)) return false
        val imdbId = linkData.imdbId ?: return false
        val timeout = extractorTimeoutMs("cinemacity", 60)
        return withTimeoutOrNull(timeout) {
            CinemaCityScraper.loadLinks(
                imdbId = imdbId,
                season = linkData.season,
                episode = linkData.episode,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        } ?: false
    }

    private suspend fun runAnimeUnity(
        linkData: LinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isExtractorEnabled("animeunity", true)) return false
        val title = linkData.title ?: return false
        val timeout = extractorTimeoutMs("animeunity", 30)
        return withTimeoutOrNull(timeout) {
            AnimeUnityScraper.loadLinks(
                title = title,
                tmdbId = linkData.id,
                isMovie = linkData.isMovie,
                season = linkData.season,
                episode = linkData.episode,
                subtitleCallback = subtitleCallback,
                callback = callback,
                fetchEnglishTitle = { id, isMov -> fetchEnglishTitle(id, isMov) }
            )
        } ?: false
    }

    private suspend fun runAnimeWorld(
        linkData: LinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isExtractorEnabled("animeworld", true)) return false
        val title = linkData.title ?: return false
        val timeout = extractorTimeoutMs("animeworld", 30)
        return withTimeoutOrNull(timeout) {
            val tmdbIdLocal = linkData.id
            val enTitle = if (tmdbIdLocal != null) fetchEnglishTitle(tmdbIdLocal, linkData.isMovie) else null

            val sources = AnimeWorldScraper.searchWithSources(
                title = title,
                tmdbId = tmdbIdLocal,
                englishTitle = enTitle
            )

            val animeToTry = listOfNotNull(sources.sub, sources.dub)
            var any = false

            for (anime in animeToTry) {
                val episodes = AnimeWorldScraper.loadEpisodes(anime.url)

                val targetEp = if (linkData.season == null) {
                    episodes.firstOrNull()
                } else {
                    episodes.find { it.number == linkData.episode.toString() }
                }

                if (targetEp != null) {
                    val info = AnimeWorldScraper.getEpisodeInfo(anime.url, targetEp.token, anime.isDub)
                    if (info != null) {
                        val success = AnimeWorldScraper.loadLinks(info, subtitleCallback, callback)
                        if (success) {
                            any = true
                            StreamITALogger.log(TAG, "AnimeWorld OK: link trovato per ep.${targetEp.number} (${if (anime.isDub) "DUB" else "SUB"})")
                        }
                    }
                }
            }

            any
        } ?: false
    }

    private suspend fun runAnimeSaturn(
        linkData: LinkData,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isExtractorEnabled("animesaturn", true)) return false
        val title = linkData.title ?: return false
        val timeout = extractorTimeoutMs("animesaturn", 30)
        return withTimeoutOrNull(timeout) {
            val tmdbIdLocal = linkData.id
            val enTitle = if (tmdbIdLocal != null) fetchEnglishTitle(tmdbIdLocal, linkData.isMovie) else null

            val sources = AnimeSaturnScraper.searchWithSources(
                title = title,
                tmdbId = tmdbIdLocal,
                englishTitle = enTitle
            )

            val animeToTry = listOfNotNull(sources.sub, sources.dub)
            var any = false

            for (anime in animeToTry) {
                val episodes = AnimeSaturnScraper.loadEpisodes(anime.url)

                val targetEp = if (linkData.season == null) {
                    episodes.firstOrNull()
                } else {
                    episodes.find { it.number == linkData.episode.toString() }
                }

                if (targetEp != null) {
                    val videoUrl = AnimeSaturnScraper.getEpisodeVideoUrl(targetEp.episodeUrl)
                    if (videoUrl != null) {
                        val label = if (anime.isDub) "[DUB]" else "[SUB]"
                        val success = AnimeSaturnScraper.loadLinks(videoUrl, label, callback)
                        if (success) {
                            any = true
                            StreamITALogger.log(TAG, "AnimeSaturn OK: link trovato per ep.${targetEp.number} ($label)")
                        }
                    }
                }
            }

            any
        } ?: false
    }

    private suspend fun fetchEnglishTitle(tmdbId: Int, isMovie: Boolean): String? {
        return try {
            val type = if (isMovie) "movie" else "tv"
            val url = "$tmdbAPI/$type/$tmdbId?language=en-US"
            val cacheKey = "TMDB:EN:$type:$tmdbId"
            val response = cachedApiGet(url, cacheKey, StreamITACache.CacheProfile.TMDB_ENGLISH_TITLE)
            val json = JSONObject(response)
            json.optString("title").takeIf { it.isNotBlank() }
                ?: json.optString("name").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            StreamITALogger.log(TAG, "Errore recupero titolo inglese: ${e.message}")
            null
        }
    }

    private fun isUpcoming(dateString: String?): Boolean {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
            unixTimeMS < dateTime
        } catch (t: Throwable) {
            false
        }
    }

    private suspend fun fetchTmdbLogoUrl(type: TvType, tmdbId: Int?, appLangCode: String?): String? {
        if (tmdbId == null) return null
        return try {
            val appLang = appLangCode?.substringBefore("-")?.lowercase()
            val url = if (type == TvType.Movie) "$tmdbAPI/movie/$tmdbId/images" else "$tmdbAPI/tv/$tmdbId/images"
            val logoCacheKey = "TMDB:LOGO:$type:$tmdbId"
            val response = cachedApiGet(url, logoCacheKey, StreamITACache.CacheProfile.TMDB_LOGO)
            val json = JSONObject(response)
            val logos = json.optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null

            fun logoUrlAt(i: Int): String { val logo = logos.getJSONObject(i); return "https://image.tmdb.org/t/p/w500${logo.optString("file_path", "")}" }
            fun isSvg(i: Int): Boolean { val logo = logos.getJSONObject(i); return logo.optString("file_path", "").endsWith(".svg", ignoreCase = true) }

            if (!appLang.isNullOrBlank()) {
                var svgFallback: String? = null
                for (i in 0 until logos.length()) { val logo = logos.getJSONObject(i); if (logo.optString("iso_639_1") == appLang) { if (isSvg(i)) { if (svgFallback == null) svgFallback = logoUrlAt(i) } else return logoUrlAt(i) } }
                if (svgFallback != null) return svgFallback
            }
            var enSvgFallback: String? = null
            for (i in 0 until logos.length()) { val logo = logos.getJSONObject(i); if (logo.optString("iso_639_1") == "en") { if (isSvg(i)) { if (enSvgFallback == null) enSvgFallback = logoUrlAt(i) } else return logoUrlAt(i) } }
            if (enSvgFallback != null) return enSvgFallback
            for (i in 0 until logos.length()) { if (!isSvg(i)) return logoUrlAt(i) }
            if (logos.length() > 0) logoUrlAt(0) else null
        } catch (e: Exception) { StreamITALogger.log(TAG, "Errore caricamento logo: ${e.message}"); null }
    }

    private fun isExtractorEnabled(name: String, default: Boolean): Boolean {
        return sharedPref?.getBoolean(
            StreamITAPlugin.extractorEnabledKey(name), default
        ) ?: default
    }

    private fun extractorTimeoutMs(name: String, defaultSeconds: Int): Long {
        val saved = sharedPref?.getString(
            StreamITAPlugin.extractorTimeoutKey(name), null
        )
        val seconds = saved?.toLongOrNull()?.takeIf { it > 0 } ?: defaultSeconds.toLong()
        return seconds * 1000L
    }

    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link.isNullOrBlank()) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width$link" else link
    }
}
