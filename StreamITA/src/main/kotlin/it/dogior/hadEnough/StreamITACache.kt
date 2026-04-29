package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.json.JSONObject

object StreamITACache {
    // SOSTITUISCI con il tuo URL Cloudflare Worker
    private const val WORKER_URL = "https://lingering-truth-455c.appbeta870.workers.dev"
    private const val TAG = "StreamITACache"

    data class CachedLinks(
        val tmdbId: Int,
        val name: String,
        val imdbId: String?,
        val mixdropLink: String?,
        val droploadLink: String?,
        val streamhgLink: String?,
        val lastUpdated: Long
    ) {
        fun hasLinks(): Boolean {
            return mixdropLink != null || droploadLink != null || streamhgLink != null
        }

        fun isExpired(maxAgeHours: Int = 168): Boolean {
            val maxAgeMs = maxAgeHours * 3600 * 1000L
            return System.currentTimeMillis() - lastUpdated > maxAgeMs
        }
    }

    suspend fun getCachedLinks(tmdbId: Int): CachedLinks? {
        return try {
            val response = app.get("$WORKER_URL/get?tmdbId=$tmdbId")
            if (response.isSuccessful) {
                val text = response.text
                val json = JSONObject(text)
                if (json.optBoolean("found", false)) {
                    parseJson<CachedLinks>(json.getJSONObject("data").toString())
                } else {
                    Log.d(TAG, "Nessuna cache trovata per TMDB ID: $tmdbId")
                    null
                }
            } else {
                Log.w(TAG, "Errore server cache: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore nel recupero cache: ${e.message}")
            null
        }
    }

    suspend fun saveCachedLinks(links: CachedLinks): Boolean {
        return try {
            val json = JSONObject()
            json.put("tmdbId", links.tmdbId)
            json.put("name", links.name)
            json.put("imdbId", links.imdbId ?: "")
            json.put("mixdropLink", links.mixdropLink ?: "")
            json.put("droploadLink", links.droploadLink ?: "")
            json.put("streamhgLink", links.streamhgLink ?: "")
            json.put("lastUpdated", System.currentTimeMillis())

            val payload = mapOf("data" to json.toString())
            val response = app.post(
                "$WORKER_URL/save",
                headers = mapOf("Content-Type" to "application/json"),
                data = payload
            )
            val success = response.isSuccessful
            if (success) {
                Log.d(TAG, "Link salvati nella cache per TMDB ID: ${links.tmdbId}")
            } else {
                Log.w(TAG, "Errore salvataggio cache: ${response.code}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Errore nel salvataggio cache: ${e.message}")
            false
        }
    }
}
