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
import it.dogior.hadEnough.extractors.StreamingCommunityExtractor
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

    private val showRating: Boolean
        get() = sharedPref?.getBoolean(StreamITAPlugin.PREF_SHOW_RATING, false) ?: false

    private val cacheSeconds: Int
        get() = (sharedPref?.getInt(StreamITAPlugin.PREF_CACHE_HOURS, 24) ?: 24) * 3600

    // ==================== STREAMING COMMUNITY DATA CLASSES ====================

    private data class StreamingCommunityTitle(
        val id: Int,
        val type: String,
        val seasons: List<StreamingCommunitySeason> = emptyList(),
    )

    private data class StreamingCommunitySeason(
        val number: Int,
        val episodes: List<StreamingCommunityEpisode> = emptyList(),
    )

    private data class StreamingCommunityEpisode(
        val id: Int,
        val number: Int,
    )

    // ==================== SEZIONE NOMI ====================

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

    // ==================== MAIN PAGE ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        StreamITALogger.log(TAG, "Caricamento homepage: ${request.name} (pagina $page, lingua=$apiLang)")
        val resp = app.get("${request.data}&page=$page", headers = authHeaders, cacheTime = cacheSeconds).body.string()
        val type = if (request.data.contains("tv")) "tv" else "movie"
        val parsedResponse = parseJson<Results>(resp).results?.mapNotNull { media ->
            media.toSearchResponse(type = type)
        }
        val home = parsedResponse ?: throw ErrorLoadingException("Invalid Json response")
        StreamITALogger.log(TAG, "Homepage caricata: ${home.size} elementi in ${request.name}")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String = "tv"): SearchResponse? {
        if (mediaType == "person") return null
        val title = title ?: name ?: originalTitle ?: return null
        return newMovieSearchResponse(
            title,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath, true)
            this.score = if (showRating) voteAverage?.let { Score.from10(it) } else null
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse>? {
        StreamITALogger.log(TAG, "Ricerca: '$query' (lingua=$apiLang)")
        val url = "$tmdbAPI/search/multi?language=$apiLang&query=$query&include_adult=${settingsForProvider.enableAdult}"
        val response = app.get(url, headers = authHeaders, cacheTime = cacheSeconds)
        if (!response.isSuccessful) {
            StreamITALogger.log(TAG, "Ricerca fallita: HTTP ${response.code}")
            return null
        }
        val results = response.parsedSafe<Results>()?.results
            ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            ?.mapNotNull { it.toSearchResponse() }
        StreamITALogger.log(TAG, "Ricerca completata: ${results?.size ?: 0} risultati per '$query'")
        return results
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse? {
        StreamITALogger.log(TAG, "Load chiamato con URL: $url")

        val data: Data = try {
            parseJson<Data>(url)
        } catch (_: Exception) {
            val tmdbRegex = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
            val match = tmdbRegex.find(url)
            if (match != null) {
                val type = match.groupValues[1]
                val id = match.groupValues[2].toInt()
                StreamITALogger.log(TAG, "URL TMDB diretto rilevato: type=$type, id=$id")
                Data(id, type)
            } else {
                StreamITALogger.log(TAG, "URL non riconosciuto: $url")
                throw ErrorLoadingException("URL non valido")
            }
        }

        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        StreamITALogger.log(TAG, "Caricamento dettagli: tipo=${data.type}, id=${data.id}, lingua=$apiLang")
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
            StreamITALogger.log(TAG, "Errore richiesta dettagli (${apiLang}): ${e.message}")
            null
        }

        if (res == null) {
            StreamITALogger.log(TAG, "Fallback a EN per id=${data.id}")
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
                StreamITALogger.log(TAG, "Errore richiesta EN: ${e.message}")
                null
            }
        }

        if (res == null) {
            StreamITALogger.log(TAG, "Contenuto non disponibile per id=${data.id}")
            throw ErrorLoadingException("Contenuto non disponibile")
        }

        val title = res.title ?: res.name ?: return null
        StreamITALogger.log(TAG, "Dettagli caricati: '$title' (${data.type}, anno=${(res.releaseDate ?: res.firstAirDate)?.split("-")?.firstOrNull()})")

        val poster = getImageUrl(res.posterPath, true)
        val bgPoster = getImageUrl(res.backdropPath, true)
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val plot = res.overview ?: "Nessuna descrizione disponibile."
        val rating = res.voteAverage?.toString()
        val genres = res.genres?.mapNotNull { it.name } ?: emptyList()
        val imdbId = res.imdbId ?: res.externalIds?.imdbId
        val logoUrl = if (showLogo && data.id != null) {
            StreamITALogger.log(TAG, "Caricamento logo per id=${data.id}")
            fetchTmdbLogoUrl(type, data.id, apiLang)
        } else null

        if (logoUrl != null) {
            StreamITALogger.log(TAG, "Logo trovato per '$title'")
        }

        val actors = res.credits?.cast?.take(15)?.mapNotNull { cast ->
            val name = cast.name ?: cast.originalName ?: return@mapNotNull null
            ActorData(Actor(name, getImageUrl(cast.profilePath)), roleString = cast.character)
        } ?: emptyList()

        val recommendations = res.recommendations?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val trailer = res.videos?.results.orEmpty().filter { it.type == "Trailer" || it.type == "Teaser" }
            .map { "https://www.youtube.com/watch?v=${it.key}" }

        // ==================== STREAMING COMMUNITY ====================

        if (type == TvType.Movie) {
            // Cerca StreamingCommunity per i film
            val scIframeUrl = try {
                StreamITALogger.log(TAG, "Cercando '$title' su StreamingCommunity...")
                val scTitle = searchStreamingCommunity(title, year, isTvSeries = false)
                if (scTitle != null) {
                    StreamITALogger.log(TAG, "Trovato su StreamingCommunity: id=${scTitle.id}")
                    "https://streamingunity.dog/it/iframe/${scTitle.id}&canPlayFHD=1"
                } else {
                    StreamITALogger.log(TAG, "Non trovato su StreamingCommunity")
                    null
                }
            } catch (e: Exception) {
                StreamITALogger.log(TAG, "Errore StreamingCommunity film: ${e.message}")
                null
            }

            val linkData = LinkData(id = data.id, title = title, year = year, isMovie = true, imdbId = imdbId, scIframeUrl = scIframeUrl)
            StreamITALogger.log(TAG, "Film creato: '$title' (${year ?: "N/A"}), IMDb: ${imdbId ?: "N/A"}, SC: ${scIframeUrl != null}")
            return newMovieLoadResponse(title, url, TvType.Movie, linkData.toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = plot
                this.duration = res.runtime
                this.score = if (showRating) rating?.let { Score.from10(it) } else null
                this.actors = actors
                this.tags = genres
                this.recommendations = recommendations
                addTrailer(trailer)
                imdbId?.let { addImdbId(it) }
                if (logoUrl != null) this.logoUrl = logoUrl
            }
        } else {
            // Serie TV - cerca StreamingCommunity
            val scTitle = try {
                searchStreamingCommunity(title, year, isTvSeries = true)
            } catch (e: Exception) {
                StreamITALogger.log(TAG, "Errore StreamingCommunity serie: ${e.message}")
                null
            }
            if (scTitle != null) {
                StreamITALogger.log(TAG, "Serie trovata su StreamingCommunity: id=${scTitle.id}")
            }

            val seasons = res.seasons?.filter { it.seasonNumber != null && it.seasonNumber > 0 } ?: emptyList()
            StreamITALogger.log(TAG, "Serie TV: '$title', ${seasons.size} stagioni")
            val episodes = seasons.mapNotNull { season ->
                val seasonNumber = season.seasonNumber ?: return@mapNotNull null
                app.get(
                    "$tmdbAPI/tv/${data.id}/season/$seasonNumber?language=$apiLang",
                    headers = authHeaders,
                    cacheTime = cacheSeconds
                ).parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                    val scEpisodeUrl = if (scTitle != null) {
                        try {
                            fetchScEpisodeIframe(scTitle, seasonNumber, eps.episodeNumber ?: 0)
                        } catch (e: Exception) {
                            null
                        }
                    } else null

                    val linkData = LinkData(
                        id = data.id, title = title, year = year,
                        season = eps.seasonNumber, episode = eps.episodeNumber,
                        isMovie = false, imdbId = imdbId,
                        scIframeUrl = scEpisodeUrl
                    )
                    newEpisode(linkData.toJson()) {
                        this.name = eps.name ?: "Episodio ${eps.episodeNumber}"
                        this.season = eps.seasonNumber
                        this.episode = eps.episodeNumber
                        this.posterUrl = getImageUrl(eps.stillPath)
                        this.description = eps.overview
                        this.score = if (showRating) eps.voteAverage?.let { Score.from10(it) } else null
                        this.runTime = eps.runtime
                        this.addDate(eps.airDate)
                    }
                }
            }.flatten()

            StreamITALogger.log(TAG, "Episodi caricati: ${episodes.size} totali")
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = plot
                this.score = if (showRating) rating?.let { Score.from10(it) } else null
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

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val tmdbId = linkData.id ?: return false
        var anySuccess = false

        StreamITALogger.log(TAG, "Ricerca link streaming: tmdbId=$tmdbId, isMovie=${linkData.isMovie}, imdbId=${linkData.imdbId}")

        coroutineScope {
            // ==================== STREAMING COMMUNITY ====================
            if (linkData.scIframeUrl != null) {
                launch {
                    try {
                        StreamITALogger.log(TAG, "Provando StreamingCommunity: ${linkData.scIframeUrl}")
                        StreamingCommunityExtractor().getUrl(
                            linkData.scIframeUrl,
                            "https://streamingunity.dog/",
                            subtitleCallback,
                            callback
                        )
                        anySuccess = true
                        StreamITALogger.log(TAG, "StreamingCommunity OK")
                    } catch (e: Exception) {
                        StreamITALogger.log(TAG, "StreamingCommunity fallito: ${e.message}")
                    }
                }
            }

            val extractors = StreamITAExtractors(
                scope = this,
                subtitleCallback = subtitleCallback,
                callback = callback,
                onSuccess = {
                    anySuccess = true
                    StreamITALogger.log(TAG, "Link streaming trovato per tmdbId=$tmdbId")
                }
            )

            if (linkData.isMovie && linkData.imdbId != null) {
                StreamITALogger.log(TAG, "Avvio estrattori film (guardahd) per imdbId=${linkData.imdbId}")
                extractors.loadMovieExtractors(linkData.imdbId)
            }

            StreamITALogger.log(TAG, "Avvio estrattori comuni (VixSrc + VidSrc) per tmdbId=$tmdbId")
            extractors.loadCommonExtractors(tmdbId, linkData.season, linkData.episode)
        }

        StreamITALogger.log(TAG, "Risultato ricerca link: successo=$anySuccess")
        return anySuccess
    }

    // ==================== STREAMING COMMUNITY METHODS ====================

    private suspend fun searchStreamingCommunity(title: String, year: Int?, isTvSeries: Boolean): StreamingCommunityTitle? {
        val searchUrl = "https://streamingunity.dog/it/search?q=${java.net.URLEncoder.encode(title, "UTF-8")}"
        val text = app.get(searchUrl, cacheTime = cacheSeconds).text
        val json = extractScPageJson(text) ?: return null
        return parseScSearchResults(json, title, year, isTvSeries)
    }

    private suspend fun fetchScEpisodeIframe(title: StreamingCommunityTitle, seasonNum: Int, episodeNum: Int): String? {
        val url = "https://streamingunity.dog/it/titles/${title.id}/season-$seasonNum"
        val text = app.get(url, cacheTime = cacheSeconds).text
        val json = extractScPageJson(text) ?: return null

        val episodes = JSONObject(json).optJSONObject("props")?.optJSONObject("loadedSeason")
            ?.optJSONArray("episodes") ?: return null

        for (i in 0 until episodes.length()) {
            val ep = episodes.optJSONObject(i) ?: continue
            if (ep.optInt("number") == episodeNum) {
                val epId = ep.optInt("id")
                return "https://streamingunity.dog/it/iframe/${title.id}?episode_id=$epId&canPlayFHD=1"
            }
        }
        return null
    }

    private fun extractScPageJson(text: String): String? {
        if (!text.trimStart().startsWith("<")) return text
        val dataPage = org.jsoup.Jsoup.parse(text).selectFirst("#app")?.attr("data-page")
        return dataPage?.let { org.jsoup.parser.Parser.unescapeEntities(it, true) }
    }

    private fun parseScSearchResults(jsonText: String, title: String, year: Int?, isTvSeries: Boolean): StreamingCommunityTitle? {
        val json = JSONObject(jsonText)
        val titles = json.optJSONObject("props")?.optJSONArray("titles") ?: json.optJSONArray("data") ?: return null

        val expectedType = if (isTvSeries) "tv" else "movie"

        for (i in 0 until titles.length()) {
            val item = titles.optJSONObject(i) ?: continue
            if (item.optString("type") != expectedType) continue
            val itemName = item.optString("name")
            if (itemName.isBlank()) continue

            val titleMatch = normalizeTitle(itemName) == normalizeTitle(title)
            val yearMatch = year == null || item.optString("release_date").substringBefore("-").toIntOrNull() == year

            if (titleMatch && yearMatch) {
                return StreamingCommunityTitle(
                    id = item.optInt("id"),
                    type = expectedType,
                )
            }
        }
        return null
    }

    private fun normalizeTitle(input: String): String {
        return input.lowercase()
            .replace(Regex("""\(\d{4}\)"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    // ==================== TMDB LOGO ====================

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
            StreamITALogger.log(TAG, "Errore caricamento logo: ${e.message}")
            null
        }
    }

    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link.isNullOrBlank()) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width$link" else link
    }
}
