package it.dogior.hadEnough.extractors

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

class VOEExtractor : ExtractorApi() {
    override val name = "VOE"
    override val mainUrl = "jessicaclearout.com"
    override val requiresReferer = false

    companion object {
        private const val TAG = "VOEExtractor"
        
        private fun rot13(text: String): String {
            return text.map { c ->
                when {
                    c in 'A'..'Z' -> ((c.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
                    c in 'a'..'z' -> ((c.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
                    else -> c
                }
            }.joinToString("")
        }
        
        private fun removePatterns(text: String): String {
            val patterns = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
            var result = text
            patterns.forEach { pattern ->
                result = result.replace(pattern, "")
            }
            return result
        }
        
        private fun shiftChars(text: String, shift: Int): String {
            return text.map { (it.code - shift).toChar() }.joinToString("")
        }
        
        private fun safeBase64Decode(str: String): String {
            var padded = str
            val pad = padded.length % 4
            if (pad > 0) {
                padded += "=".repeat(4 - pad)
            }
            return String(Base64.decode(padded, Base64.DEFAULT))
        }
        
        private fun deobfuscateJson(rawJson: String): JSONObject? {
            return try {
                val arr = JSONArray(rawJson)
                if (arr.length() == 0) return null
                val obf = arr.getString(0)
                
                val step1 = rot13(obf)
                val step2 = removePatterns(step1)
                val step3 = safeBase64Decode(step2)
                val step4 = shiftChars(step3, 3)
                val step5 = step4.reversed()
                val step6 = safeBase64Decode(step5)
                
                JSONObject(step6)
            } catch (e: Exception) {
                Log.e(TAG, "Deobfuscation error: ${e.message}")
                null
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "Getting video from: $url")
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to "https://toonitalia.xyz/"
            )
            
            val response = app.get(url, headers = headers)
            val html = response.body.string()
            
            // Cerca il JSON offuscato - usa Pattern.DOTALL
            val jsonPattern = Pattern.compile("<script type=\"application/json\">(.*?)</script>", Pattern.DOTALL)
            val jsonMatcher = jsonPattern.matcher(html)
            
            if (!jsonMatcher.find()) {
                Log.e(TAG, "No obfuscated JSON found")
                return
            }
            
            val rawJson = jsonMatcher.group(1)
            Log.d(TAG, "Obfuscated JSON found, length: ${rawJson.length}")
            
            val decoded = deobfuscateJson(rawJson)
            if (decoded == null) {
                Log.e(TAG, "Failed to decode JSON")
                return
            }
            
            Log.d(TAG, "Decoded JSON: $decoded")
            
            var videoUrl = decoded.optString("direct_access_url")
            if (videoUrl.isEmpty()) {
                videoUrl = decoded.optString("source")
            }
            
            if (videoUrl.isEmpty()) {
                val fallback = decoded.optJSONArray("fallback")
                if (fallback != null && fallback.length() > 0) {
                    val firstFallback = fallback.getJSONObject(0)
                    videoUrl = firstFallback.optString("file")
                }
            }
            
            if (videoUrl.isEmpty()) {
                Log.e(TAG, "No video URL found in decoded JSON")
                return
            }
            
            Log.d(TAG, "Video URL found: $videoUrl")
            
            val videoHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to "https://jessicaclearout.com/",
                "Origin" to "https://jessicaclearout.com"
            )
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "VOE",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = videoHeaders
                    this.referer = "https://jessicaclearout.com/"
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
