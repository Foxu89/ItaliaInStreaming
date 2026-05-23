package it.dogior.hadEnough

import android.content.SharedPreferences
import androidx.core.content.edit
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.StreamITAStremioAddonSettings.getDynamicStremioMap
import org.json.JSONArray
import org.json.JSONObject

data class StreamITAStremioCatelogAddon(
    val id: Long,
    val name: String,
    val url: String
)

object StreamITAStremioCatelogSettings {
    private const val PREF_CATELOG_KEY = "streamita_stremio_catelog_addons"
    private const val PREF_DISABLED_KEY = "streamita_stremio_catelog_disabled"

    fun getAddons(sharedPref: SharedPreferences?): List<StreamITAStremioCatelogAddon> {
        val json = sharedPref?.getString(PREF_CATELOG_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val link = obj.optString("url", "").trim()
                if (link.isEmpty()) return@mapNotNull null
                StreamITAStremioCatelogAddon(
                    id = obj.optLong("id", System.currentTimeMillis()),
                    name = obj.optString("name", link).ifBlank { link },
                    url = link.trimEnd('/')
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveAddons(sharedPref: SharedPreferences?, addons: List<StreamITAStremioCatelogAddon>) {
        val arr = JSONArray()
        for (item in addons) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("url", item.url)
            })
        }
        sharedPref?.edit { putString(PREF_CATELOG_KEY, arr.toString()) }
    }

    private fun catelogKey(name: String): String {
        return "catelog_" + name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "catelog" }
    }

    fun isEnabled(sharedPref: SharedPreferences?, name: String): Boolean {
        val disabled = sharedPref?.getStringSet(PREF_DISABLED_KEY, emptySet()) ?: emptySet()
        return catelogKey(name) !in disabled
    }

    fun setEnabled(sharedPref: SharedPreferences?, name: String, enabled: Boolean) {
        val key = catelogKey(name)
        val disabled = sharedPref?.getStringSet(PREF_DISABLED_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) disabled.remove(key) else disabled.add(key)
        sharedPref?.edit { putStringSet(PREF_DISABLED_KEY, disabled) }
    }
}

class StreamITAStremioCatelog(
    override var mainUrl: String,
    override var name: String,
    val sharedPref: SharedPreferences? = null
) : MainAPI() {
    override val supportedTypes = setOf(TvType.Others, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (mainUrl.isEmpty()) throw IllegalArgumentException("Configure catalog addon URL in settings")
        mainUrl = mainUrl.trimEnd('/')

        val pageSize = 100
        val skip = (page - 1) * pageSize

        val manifest = app.get("$mainUrl/manifest.json").parsedSafe<CatelogManifest>()
        val lists = manifest?.catalogs?.amap { catalog ->
            catalog.toHomePageList(provider = this, skip = skip)
        }?.filter { it.list.isNotEmpty() } ?: emptyList()

        return newHomePageResponse(lists, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = mainUrl.trimEnd('/')
        val manifest = app.get("$mainUrl/manifest.json").parsedSafe<CatelogManifest>()
        val results = manifest?.catalogs?.amap { catalog ->
            catalog.search(query, this)
        }?.flatten() ?: emptyList()
        return results.distinct()
    }

    override suspend fun load(url: String): LoadResponse {
        val res: CatelogEntry = if (url.startsWith("{")) {
            parseJson(url)
        } else {
            val json = app.get(url).text
            val metaJson = JSONObject(json).getJSONObject("meta").toString()
            parseJson(metaJson)
        }

        var response = app.get("$mainUrl/meta/${res.type}/${res.id}.json")
            .parsedSafe<CatelogResponse>()

        if (response == null) {
            response = app.get("https://v3-cinemeta.strem.io/meta/${res.type}/${res.id}.json")
                .parsedSafe<CatelogResponse>()
        }

        val entry = response?.meta
            ?: response?.metas?.firstOrNull { it.id == res.id }
            ?: response?.metas?.firstOrNull()
            ?: throw RuntimeException("Meta not found")

        return entry.toLoadResponse(this, res.id)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<CatelogLoadData>(data)
        val imdb = res.id

        val stremioAddons = getDynamicStremioMap(
            sharedPref, imdb, res.season, res.episode, subtitleCallback, callback
        ).values

        kotlinx.coroutines.coroutineScope {
            stremioAddons.forEach { action ->
                kotlinx.coroutines.launch {
                    try { action() } catch (_: Throwable) {}
                }
            }
        }
        return true
    }

    private data class CatelogManifest(val catalogs: List<CatelogCatalog>)

    private data class CatelogCatalog(
        var name: String?,
        val id: String,
        val type: String?,
        val types: MutableList<String> = mutableListOf()
    ) {
        init { if (type != null) types.add(type) }

        suspend fun search(query: String, provider: StreamITAStremioCatelog): List<SearchResponse> {
            return types.flatMap { t ->
                val res = app.get(
                    "${provider.mainUrl}/catalog/$t/$id/search=${query}.json",
                    timeout = 120L
                ).parsedSafe<CatelogResponse>()
                res?.metas?.map { it.toSearchResponse(provider) } ?: emptyList()
            }
        }

        suspend fun toHomePageList(provider: StreamITAStremioCatelog, skip: Int): HomePageList {
            val entries = mutableMapOf<String, SearchResponse>()
            types.forEach { t ->
                val url = if (skip > 0)
                    "${provider.mainUrl}/catalog/$t/$id/skip=$skip.json"
                else
                    "${provider.mainUrl}/catalog/$t/$id.json"
                val res = app.get(url, timeout = 120L).parsedSafe<CatelogResponse>()
                res?.metas?.forEach { entry ->
                    if (!entries.containsKey(entry.id)) {
                        entries[entry.id] = entry.toSearchResponse(provider)
                    }
                }
            }
            return HomePageList(name ?: id, entries.values.toList())
        }
    }

    private data class CatelogResponse(val metas: List<CatelogEntry>?, val meta: CatelogEntry?)

    private data class CatelogEntry(
        @param:JsonProperty("name") val name: String,
        @param:JsonProperty("id") val id: String,
        @param:JsonProperty("poster") val poster: String?,
        @param:JsonProperty("background") val background: String?,
        @param:JsonProperty("description") val description: String?,
        @param:JsonProperty("imdbRating") val imdbRating: String?,
        @param:JsonProperty("type") val type: String?,
        @param:JsonProperty("videos") val videos: List<CatelogVideo>?,
        @param:JsonProperty("genre") val genre: List<String>?,
        @param:JsonProperty("genres") val genres: List<String> = emptyList(),
        @param:JsonProperty("cast") val cast: List<String> = emptyList(),
        @param:JsonProperty("trailers") val trailersSources: List<CatelogTrailer> = emptyList(),
        @param:JsonProperty("year") val yearNum: String? = null
    ) {
        fun toSearchResponse(provider: StreamITAStremioCatelog): SearchResponse {
            return provider.newMovieSearchResponse(name, toJson(), TvType.Others) {
                posterUrl = poster
            }
        }

        suspend fun toLoadResponse(provider: StreamITAStremioCatelog, imdbId: String?): LoadResponse {
            if (videos.isNullOrEmpty()) {
                return provider.newMovieLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/$type/$id.json",
                    TvType.Movie,
                    CatelogLoadData(type, id, imdbId = imdbId, year = yearNum?.toIntOrNull())
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    score = Score.from10(imdbRating)
                    plot = description
                    year = yearNum?.toIntOrNull()
                    tags = genre ?: genres
                    addActors(cast)
                    addTrailer(trailersSources.map { "https://www.youtube.com/watch?v=${it.source}" })
                    addImdbId(imdbId)
                }
            }
            return provider.newTvSeriesLoadResponse(
                name,
                "${provider.mainUrl}/meta/$type/$id.json",
                TvType.TvSeries,
                videos.map { it.toEpisode(provider, type, imdbId) }
            ) {
                posterUrl = poster
                backgroundPosterUrl = background
                score = Score.from10(imdbRating)
                plot = description
                year = yearNum?.toIntOrNull()
                tags = genre ?: genres
                addActors(cast)
                addTrailer(trailersSources.map { "https://www.youtube.com/watch?v=${it.source}" }.randomOrNull())
                addImdbId(imdbId)
            }
        }
    }

    private data class CatelogVideo(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("season") val seasonNumber: Int? = null,
        @param:JsonProperty("number") val number: Int? = null,
        @param:JsonProperty("episode") val episode: Int? = null,
        @param:JsonProperty("thumbnail") val thumbnail: String? = null,
        @param:JsonProperty("overview") val overview: String? = null,
        @param:JsonProperty("description") val description: String? = null,
    ) {
        fun toEpisode(provider: StreamITAStremioCatelog, type: String?, imdbId: String?): Episode {
            return provider.newEpisode(
                CatelogLoadData(type, id, seasonNumber, episode ?: number, imdbId)
            ) {
                this.name = this@CatelogVideo.name ?: title
                this.posterUrl = thumbnail
                this.description = overview ?: this@CatelogVideo.description
                this.season = seasonNumber
                this.episode = this@CatelogVideo.episode ?: number
            }
        }
    }

    private data class CatelogTrailer(
        val source: String?,
        val type: String?
    )

    data class CatelogLoadData(
        val type: String? = null,
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val imdbId: String? = null,
        val year: Int? = null
    )
}
