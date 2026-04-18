package it.dogior.hadEnough.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "https://vixsrc.to"
    override val name = "VixSrc"
    override val requiresReferer = true
    private val TAG = "VixSrcExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "getUrl called")
        Log.d(TAG, "Input URL: $url")
        Log.d(TAG, "Referer: $referer")
        Log.d(TAG, "========================================")
        
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
        Log.d(TAG, "Extracted TMDB ID: $tmdbId")
        
        val seasonEpisode = Regex("""/tv/\d+/(\d+)/(\d+)""").find(url)
        val season = seasonEpisode?.groupValues?.get(1)
        val episode = seasonEpisode?.groupValues?.get(2)
        Log.d(TAG, "Season: $season, Episode: $episode")
        
        if (tmdbId == null) {
            Log.e(TAG, "Cannot extract TMDB ID from URL, aborting")
            return
        }
        
        val apiUrl = if (season != null && episode != null) {
            "$mainUrl/api/tv/$tmdbId/$season/$episode?lang=it"
        } else {
            "$mainUrl/api/movie/$tmdbId?lang=it"
        }
        
        Log.d(TAG, "API URL: $apiUrl")
        
        try {
            Log.d(TAG, "Making API request...")
            val apiResponse = app.get(apiUrl, referer = mainUrl)
            Log.d(TAG, "API response code: ${apiResponse.code}")
            Log.d(TAG, "API response length: ${apiResponse.text.length}")
            Log.d(TAG, "API response preview: ${apiResponse.text.take(300)}")
            
            val srcPattern = Regex(""""src"\s*:\s*"([^"]+)"""")
            val srcMatch = srcPattern.find(apiResponse.text)
            
            if (srcMatch != null) {
                Log.d(TAG, "Src pattern MATCHED")
                var embedUrl = srcMatch.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "Raw embed path: $embedUrl")
                
                if (!embedUrl.startsWith("http")) {
                    embedUrl = "$mainUrl$embedUrl"
                }
                Log.d(TAG, "Final embed URL: $embedUrl")
                Log.d(TAG, "Calling loadExtractor with embed URL...")
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                Log.d(TAG, "loadExtractor called successfully")
                return
            } else {
                Log.w(TAG, "Src pattern NOT MATCHED in API response")
                Log.w(TAG, "Full API response: ${apiResponse.text}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "API request exception: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
        }
        
        Log.d(TAG, "Falling back to direct URL: $url")
        Log.d(TAG, "Calling loadExtractor with direct URL...")
        loadExtractor(url, mainUrl, subtitleCallback, callback)
        Log.d(TAG, "Fallback loadExtractor called")
        Log.d(TAG, "========================================")
    }
}
