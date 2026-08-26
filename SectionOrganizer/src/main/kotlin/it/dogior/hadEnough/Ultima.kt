package it.dogior.hadEnough

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import it.dogior.hadEnough.UltimaUtils.SectionInfo

/** Provider "virtuale": non scarica nulla da solo, smista ogni richiesta
 *  verso il vero provider indicato in [SectionInfo.pluginName]. La lista
 *  di sezioni ([mainPage]) e' calcolata UNA SOLA VOLTA alla costruzione di
 *  questa classe (limite dell'API mainPage di CloudStream): per questo,
 *  dopo aver cambiato le impostazioni da Configura/Riordina, serve
 *  riavviare l'app perche' le modifiche si vedano in home. */
class Ultima(val plugin: UltimaPlugin) : MainAPI() {
    override var name = "Homepage"
    // Tutti i TvType, non solo Movie/TvSeries/Anime: "Homepage" è un
    // pass-through puro verso qualsiasi provider l'utente abiliti in
    // Configura sezioni. Con un set più stretto, CloudStream può escludere
    // silenziosamente "Homepage" da alcuni filtri/selettori quando i suoi
    // tipi dichiarati non si sovrappongono a quelli attivi nell'app (visto
    // nel codice reale di HomeFragment: it.supportedTypes.any(preSelectedTypes::contains))
    // — capitava proprio con provider come StreamingCommunity che
    // dichiarano anche Cartoon/Documentary, non coperti dal vecchio set.
    override var supportedTypes = TvType.entries.toSet()
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = false
    private val sm = UltimaStorageManager

    private val mapper = jacksonObjectMapper()

    private fun loadSections(): List<MainPageData> {
        val result = mutableListOf<MainPageData>()
        val savedPlugins = sm.currentExtensions

        val enabledSections = savedPlugins
            .flatMap { it.sections?.asList() ?: emptyList() }
            .filter { it.enabled }
            .sortedByDescending { it.priority }

        val nameCounts = mutableMapOf<String, Int>()

        enabledSections.forEach { section ->
            try {
                val sectionKey = mapper.writeValueAsString(section)
                val sectionName = buildSectionName(section, nameCounts)
                result += mainPageOf(sectionKey to sectionName)
            } catch (e: Exception) {
                Log.e("loadSections", "Failed to load section ${section.name}: ${e.message}")
            }
        }

        return if (result.isEmpty()) mainPageOf("" to NO_SECTIONS_LABEL) else result
    }

    private fun buildSectionName(section: SectionInfo, nameCounts: MutableMap<String, Int>): String {
        if (sm.extNameOnHome) return "${section.pluginName}: ${section.name}"

        // Conta per nome ESATTO della sezione originale, non con startsWith:
        // altrimenti "Nuovi" e "Nuovi Episodi" (sezioni diverse) si
        // contaminavano a vicenda nel conteggio dei duplicati.
        val count = (nameCounts[section.name] ?: 0) + 1
        nameCounts[section.name] = count
        return if (count == 1) section.name else "${section.name} $count"
    }

    override val mainPage = loadSections()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data.isEmpty()) {
            throw ErrorLoadingException("Seleziona le sezioni dalle impostazioni dell'estensione per visualizzarle qui.")
        }

        return try {
            val section = AppUtils.parseJson<SectionInfo>(request.data)
            val provider = findProvider(section.pluginName)
                ?: throw ErrorLoadingException("Plugin '${section.pluginName}' non disponibile.")

            provider.getMainPage(
                page,
                MainPageRequest(
                    name = request.name,
                    data = section.url,
                    horizontalImages = request.horizontalImages
                )
            )
        } catch (e: Throwable) {
            Log.e("getMainPage", "Error loading main page: ${e.message}")
            null
        }
    }

    /** Trova il provider per nome, come [allProviders].find, ma avvisa nel
     *  log se il nome e' AMBIGUO (piu' provider installati con lo stesso
     *  nome visualizzato, es. due estensioni chiamate entrambe
     *  "StreamingCommunity" installate da repo diversi). In quel caso
     *  CloudStream/SectionOrganizer possono ritrovarsi ad usare quello
     *  "sbagliato" senza nessun errore esplicito — solo una sezione che
     *  misteriosamente non carica bene. */
    private fun findProvider(pluginName: String): MainAPI? {
        val matches = allProviders.filter { it.name == pluginName }
        if (matches.size > 1) {
            Log.e(
                "Ultima",
                "ATTENZIONE: ${matches.size} provider installati con nome '$pluginName' " +
                    "(forse la stessa estensione installata da due repo diversi?). " +
                    "SectionOrganizer usera' il primo che trova, potrebbe non essere quello giusto."
            )
        }
        return matches.firstOrNull()
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val enabledPluginNames = enabledSections().map { it.pluginName }.distinct()

        val tasks = mutableListOf<suspend () -> List<SearchResponse>>()

        for (pluginName in enabledPluginNames) {
            val provider = findProvider(pluginName) ?: continue
            tasks += suspend {
                try {
                    when (val result = provider.search(query)) {
                        is List<*> -> {
                            result.map { item ->
                                when (item) {
                                    is MovieSearchResponse -> item.copy(name = "[$pluginName] ${item.name}")
                                    is AnimeSearchResponse -> item.copy(name = "[$pluginName] ${item.name}")
                                    is TvSeriesSearchResponse -> item.copy(name = "[$pluginName] ${item.name}")
                                    else -> item
                                }
                            }
                        }
                        else -> emptyList()
                    }
                } catch (e: Exception) {
                    Log.e("search", "Search failed for provider $pluginName: ${e.message}")
                    emptyList()
                }
            }
        }

        if (tasks.isEmpty()) return emptyList()
        return runLimitedParallel(limit = 4, tasks).flatten()
    }

    override suspend fun load(url: String): LoadResponse {
        val enabledPluginNames = enabledSections().map { it.pluginName }.distinct()
        val providersToTry = allProviders.filter { it.name in enabledPluginNames }

        for (provider in providersToTry) {
            try {
                val response = provider.load(url)
                if (response != null && response.name.isNotBlank() && !response.posterUrl.isNullOrBlank()) {
                    return response
                }
            } catch (_: Throwable) {
                Log.e("Ultima load", "Failed loading from ${provider.name}")
            }
        }

        return newMovieLoadResponse("Benvenuto su SectionOrganizer", "", TvType.Others, "")
    }

    /** Sezioni correntemente presenti in [mainPage], escluso il segnaposto
     *  "nessuna sezione". Sostituisce il vecchio confronto sul testo
     *  visualizzato (fragile: si rompeva se cambiava la stringa in un solo
     *  punto) con un controllo basato sui dati, che e' quello che conta
     *  davvero per capire se una entry e' una sezione vera. */
    private fun enabledSections(): List<SectionInfo> =
        mainPage.filter { it.data.isNotEmpty() }.mapNotNull {
            try {
                AppUtils.parseJson<SectionInfo>(it.data)
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        const val NO_SECTIONS_LABEL = "Nessuna sezione selezionata"
    }
}
