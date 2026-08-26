package it.dogior.hadEnough

import it.dogior.hadEnough.UltimaUtils.ExtensionInfo
import it.dogior.hadEnough.UltimaUtils.SectionInfo
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey

object UltimaStorageManager {
    var extNameOnHome: Boolean
        get() = getKey("ULTIMA_EXT_NAME_ON_HOME") ?: true
        set(value) { setKey("ULTIMA_EXT_NAME_ON_HOME", value) }

    /** Preferenze salvate, cosi' come sono in storage: possono contenere
     *  ancora estensioni non piu' installate. Per la lista "viva", allineata
     *  ai provider realmente disponibili ora, usa [fetchExtensions]. */
    var currentExtensions: Array<ExtensionInfo>
        get() = getKey("ULTIMA_EXTENSIONS_LIST") ?: emptyArray()
        set(value) { setKey("ULTIMA_EXTENSIONS_LIST", value) }

    fun deleteAllData() {
        listOf("ULTIMA_EXT_NAME_ON_HOME", "ULTIMA_EXTENSIONS_LIST")
            .forEach { removeKey(it) }
    }

    /** Estensioni effettivamente installate ORA, con le preferenze salvate
     *  (se presenti) applicate sopra. Le estensioni salvate ma non piu'
     *  installate vengono scartate qui, cosi' lo storage non accumula
     *  all'infinito voci orfane di plugin disinstallati: il salvataggio
     *  successivo (Configura/Riordina) riscrive uno storage gia' pulito. */
    fun fetchExtensions(): Array<ExtensionInfo> = synchronized(allProviders) {
        val cachedExtensions = getKey<Array<ExtensionInfo>>("ULTIMA_EXTENSIONS_LIST")
        val providers = allProviders.filter { it.name != "Homepage" }

        providers.map { provider ->
            val cached = cachedExtensions?.find { it.name == provider.name }
            val liveSections = provider.mainPage.map { section ->
                SectionInfo(section.name, section.data, provider.name, false)
            }

            if (cached?.sections == null) {
                ExtensionInfo(name = provider.name, sections = liveSections.toTypedArray())
            } else {
                // Il provider potrebbe aver aggiunto/tolto sezioni dall'ultima
                // volta: teniamo le preferenze (enabled/priority) per quelle che
                // esistono ancora, aggiungiamo le nuove come non abilitate,
                // scartiamo quelle sparite (altrimenti si accumulano per sempre).
                val mergedSections = liveSections.map { live ->
                    cached.sections?.find { it.url == live.url } ?: live
                }
                ExtensionInfo(name = provider.name, sections = mergedSections.toTypedArray())
            }
        }.toTypedArray()
    }
}
