package it.dogior.hadEnough

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import org.json.JSONObject

object StreamITALinks {
    private const val DOMAINS_URL = "https://pastebin.com/raw/tdPvYPkJ"
    private var cachedDomains: JSONObject? = null
    private var lastFetchMs: Long = 0
    private val cacheTTL = 1 * 3600 * 1000L // 1 ora

    suspend fun get(name: String): String {
        // Se cache scaduta o assente, riscarica
        val now = System.currentTimeMillis()
        if (cachedDomains == null || (now - lastFetchMs) >= cacheTTL) {
            cachedDomains = try {
                JSONObject(app.get(DOMAINS_URL).text)
            } catch (e: Exception) {
                cachedDomains ?: throw ErrorLoadingException("Impossibile scaricare i link. Riprova più tardi.")
            }
            lastFetchMs = now
        }

        return cachedDomains!!.optString(name).takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Link '$name' non trovato nel JSON")
    }
}
