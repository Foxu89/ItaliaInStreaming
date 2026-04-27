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

import it.dogior.hadEnough.extractors.CinemaCityExtractor
import it.dogior.hadEnough.extractors.VixSrcExtractor
import it.dogior.hadEnough.extractors.DropLoadExtractor
import it.dogior.hadEnough.extractors.MixDropExtractor
import it.dogior.hadEnough.extractors.StreamHGExtractor
import it.dogior.hadEnough.extractors.VidSrcExtractor

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=453&with_original_language=en" to "Hulu",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=2552&with_original_language=en" to "Apple TV+",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=49&with_original_language=en" to "HBO",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=4330" to "Paramount+",
        "$tmdbAPI/discover/tv?language=it-IT&with_networks=3353" to "Peacock",
        "$tmdbAPI/discover/movie?language=it-IT&with_keywords=210024|222243&sort_by=popularity.desc" to "Anime Film",
        "$tmdbAPI/discover/tv?language=it-IT&with_keywords=210024|222243&sort_by=popularity.desc" to "Anime TV",
        "$tmdbAPI/discover/tv?language=it-IT&with_original_language=ko" to "Serie Coreane",
        "$tmdbAPI/discover/tv?language=it-IT&with_genres=99" to "Documentari",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val response = try {
            withTimeoutOrNull(10000) {
                app.get(url, headers = authHeaders)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore homepage: ${e.message}")
            null
        }
        
        if (response == null || !response.isSuccessful) 
            throw ErrorLoadingException("Errore homepage: ${response?.code ?: "timeout"}")
            
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
        val data: Data = try {
            parseJson<Data>(url)
        } catch (_: Exception) {
            val tmdbRegex = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
            val match = tmdbRegex.find(url)
            if (match != null) {
                val type = match.groupValues[1]
                val id = match.groupValues[2].toInt()
                Data(id, type)
            } else {
                Log.e(TAG, "URL non riconosciuto: $url")
                return null
            }
        }

        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        val append = "credits,videos,recommendations,external_ids"
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?language=it-IT&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?language=it-IT&append_to_response=$append"
        }

        // Prima prova in italiano con timeout
        var res = try {
            withTimeoutOrNull(10000) {
                val response = app.get(resUrl, headers = authHeaders)
                if (response.isSuccessful) response.parsedSafe<MediaDetail>() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore richiesta IT: ${e.message}")
            null
        }

        // Fallback in inglese se fallisce
        if (res == null) {
            Log.d(TAG, "Fallback a lingua inglese per ID: ${data.id}")
            val enUrl = if (type == TvType.Movie) {
                "$tmdbAPI/movie/${data.id}?language=en-US&append_to_response=$append"
            } else {
                "$tmdbAPI/tv/${data.id}?language=en-US&append_to_response=$append"
            }
            res = try {
                withTimeoutOrNull(8000) {
                    val response = app.get(enUrl, headers = authHeaders)
                    if (response.isSuccessful) response.parsedSafe<MediaDetail>() else null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore richiesta EN: ${e.message}")
                null
            }
        }

        if (res == null) throw ErrorLoadingException("Contenuto non disponibile (404)")

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
                    // Prova prima in italiano
                    var seasonDetails: MediaDetailEpisodes? = null
                    val itUrl = "$tmdbAPI/tv/${data.id}/season/$seasonNumber?language=it-IT"
                    
                    val itResponse = withTimeoutOrNull(8000) {
                        app.get(itUrl, headers = authHeaders)
                    }
                    
                    if (itResponse?.isSuccessful == true) {
                        seasonDetails = itResponse.parsedSafe<MediaDetailEpisodes>()
                    }
                    
                    // Fallback inglese per le stagioni
                    if (seasonDetails == null) {
                        val enUrl = "$tmdbAPI/tv/${data.id}/season/$seasonNumber?language=en-US"
                        val enResponse = withTimeoutOrNull(5000) {
                            app.get(enUrl, headers = authHeaders)
                        }
                        if (enResponse?.isSuccessful == true) {
                            seasonDetails = enResponse.parsedSafe<MediaDetailEpisodes>()
                        }
                    }
                    
                    seasonDetails?.episodes?.forEach { eps ->
                        eps.episodeNumber?.let { epNum ->
                            val linkData = LinkData(
                                id = data.id,
                                title = title,
                                year = year,
                                season = seasonNumber,
                                episode = epNum,
                                isMovie = false,
                                imdbId = imdbId
                            )
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
            // =============================================
            // SOLO FILM: DropLoad + MixDrop + StreamHG
            // =============================================
            if (linkData.isMovie && linkData.imdbId != null) {
                // --- DropLoad ---
                launch {
                    try {
                        val guardahdUrl =
                            "https://guardahd.stream/index.php?task=set-movie-u&id_imdb=${linkData.imdbId}"
                        Log.d(TAG, "Film: cerco link DropLoad da $guardahdUrl")

                        val response = app.get(guardahdUrl)
                        if (response.isSuccessful) {
                            val html = response.text

                            val dropRegex = Regex(
                                """data-link\s*=\s*"(//[^"]*dr0pstream[^"]*|https?://[^"]*dr0pstream[^"]*)""",
                                RegexOption.IGNORE_CASE
                            )
                            val dropMatch = dropRegex.find(html)
                            val droploadLink = dropMatch?.groupValues?.get(1)?.trim()

                            if (droploadLink != null) {
                                val fullLink = if (droploadLink.startsWith("//")) {
                                    "https:$droploadLink"
                                } else {
                                    droploadLink
                                }
                                Log.d(TAG, "DropLoad link trovato: $fullLink")

                                val extractor = DropLoadExtractor()
                                extractor.getUrl(
                                    fullLink,
                                    "https://guardahd.stream/",
                                    subtitleCallback,
                                    callback
                                )
                                anySuccess = true
                            } else {
                                Log.w(TAG, "Nessun link DropLoad trovato in guardahd.stream")
                            }
                        } else {
                            Log.w(TAG, "guardahd.stream non raggiungibile per DropLoad: ${response.code}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore DropLoad: ${e.message}")
                    }
                }

                // --- MixDrop ---
                launch {
                    try {
                        val guardahdUrl =
                            "https://guardahd.stream/index.php?task=set-movie-u&id_imdb=${linkData.imdbId}"
                        Log.d(TAG, "Film: cerco link MixDrop da $guardahdUrl")

                        val response = app.get(guardahdUrl)
                        if (response.isSuccessful) {
                            val html = response.text

                            val mixDropRegex = Regex(
                                """data-link\s*=\s*"(//[^"]*m1xdrop[^"]*|https?://[^"]*m1xdrop[^"]*)""",
                                RegexOption.IGNORE_CASE
                            )
                            val match = mixDropRegex.find(html)
                            val mixdropLink = match?.groupValues?.get(1)?.trim()

                            if (mixdropLink != null) {
                                val fullLink = if (mixdropLink.startsWith("//")) {
                                    "https:$mixdropLink"
                                } else {
                                    mixdropLink
                                }
                                Log.d(TAG, "MixDrop link trovato: $fullLink")

                                val extractor = MixDropExtractor()
                                extractor.getUrl(
                                    fullLink,
                                    "https://guardahd.stream/",
                                    subtitleCallback,
                                    callback
                                )
                                anySuccess = true
                            } else {
                                Log.w(TAG, "Nessun link MixDrop trovato in guardahd.stream")
                            }
                        } else {
                            Log.w(TAG, "guardahd.stream non raggiungibile per MixDrop: ${response.code}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore MixDrop: ${e.message}")
                    }
                }

                // --- StreamHG ---
                launch {
                    try {
                        val guardahdUrl =
                            "https://guardahd.stream/index.php?task=set-movie-u&id_imdb=${linkData.imdbId}"
                        Log.d(TAG, "Film: cerco link StreamHG da $guardahdUrl")

                        val response = app.get(guardahdUrl)
                        if (response.isSuccessful) {
                            val html = response.text

                            val hgRegex = Regex(
                                """data-link\s*=\s*"(//[^"]*dhcplay[^"]*|https?://[^"]*dhcplay[^"]*)""",
                                RegexOption.IGNORE_CASE
                            )
                            val hgMatch = hgRegex.find(html)
                            val streamhgLink = hgMatch?.groupValues?.get(1)?.trim()

                            if (streamhgLink != null) {
                                val fullLink = if (streamhgLink.startsWith("//")) {
                                    "https:$streamhgLink"
                                } else {
                                    streamhgLink
                                }
                                Log.d(TAG, "StreamHG link trovato: $fullLink")

                                val extractor = StreamHGExtractor()
                                extractor.getUrl(
                                    fullLink,
                                    "https://guardahd.stream/",
                                    subtitleCallback,
                                    callback
                                )
                                anySuccess = true
                            } else {
                                Log.w(TAG, "Nessun link StreamHG trovato in guardahd.stream")
                            }
                        } else {
                            Log.w(TAG, "guardahd.stream non raggiungibile per StreamHG: ${response.code}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore StreamHG: ${e.message}")
                    }
                }
            }

            // =============================================
            // FILM + SERIE TV: VixSrc (sempre)
            // =============================================
            launch {
                try {
                    val extractor = VixSrcExtractor()
                    val url = if (linkData.season == null) {
                        "https://vixsrc.to/movie/$tmdbId"
                    } else {
                        "https://vixsrc.to/tv/$tmdbId/${linkData.season}/${linkData.episode}"
                    }
                    extractor.getUrl(url, "https://vixsrc.to/", subtitleCallback, callback)
                    anySuccess = true
                } catch (_: Exception) {
                }
            }

            // =============================================
            // FILM + SERIE TV: VidSrc (sempre, solo inglese)
            // =============================================
            launch {
                try {
                    val extractor = VidSrcExtractor()
                    val url = if (linkData.season == null) {
                        "https://vidsrc.ru/movie/$tmdbId"
                    } else {
                        "https://vidsrc.ru/tv/$tmdbId/${linkData.season}/${linkData.episode}"
                    }
                    extractor.getUrl(url, "https://vidsrc.ru/", subtitleCallback, callback)
                    anySuccess = true
                } catch (_: Exception) {
                }
            }

            // =============================================
            // FILM + SERIE TV: CinemaCity
            // =============================================
            if (linkData.imdbId != null) {
                launch {
                    try {
                        val extractor = CinemaCityExtractor()
                        val ccReferer = if (linkData.season != null) {
                            "season=${linkData.season}&episode=${linkData.episode}"
                        } else {
                            ""
                        }
                        extractor.getUrl(
                            linkData.imdbId,
                            ccReferer,
                            subtitleCallback,
                            callback
                        )
                        anySuccess = true
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return anySuccess
    }

    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link.isNullOrBlank()) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width$link" else link
    }
}
