package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log
import java.net.URLDecoder

class MySupervideoExtractor : ExtractorApi() {
    override var name = "Supervideo"
    override var mainUrl = "https://supervideo.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i("MySupervideoExtractor", "🔎 Starting extraction for: $url")
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Pragma" to "no-cache",
                "Cache-Control" to "no-cache"
            )

            // Passo 1: Ottieni la pagina iniziale
            val response = app.get(url, headers = headers)
            val html = response.text
            Log.i("MySupervideoExtractor", "✅ Page loaded, size: ${html.length}")
            
            // Passo 2: Cerca script con eval (comune in supervideo)
            val scriptRegex = Regex("""<script[^>]*>(eval\(function\(p,a,c,k,e,[^<]+)</script>""")
            val scriptMatch = scriptRegex.find(html)
            
            if (scriptMatch != null) {
                Log.i("MySupervideoExtractor", "✅ Found eval script")
                val packedScript = scriptMatch.groupValues[1]
                
                // Prova a decodificare lo script packer
                val unpacked = try {
                    app.getAndUnpack(packedScript)
                } catch (e: Exception) {
                    Log.e("MySupervideoExtractor", "❌ Failed to unpack script: ${e.message}")
                    return
                }
                
                Log.d("MySupervideoExtractor", "Unpacked (first 500 chars): ${unpacked.take(500)}...")
                
                // Cerca URL video nell'unpacked
                val videoRegexes = listOf(
                    Regex("""sources:\s*\[\s*\{\s*src:\s*"([^"]+)"""),
                    Regex("""file\s*:\s*"([^"]+)"""),
                    Regex("""src:\s*"([^"]+)"""),
                    Regex("""hls:\s*"([^"]+)"""),
                    Regex("""https?://[^"\s]+\.m3u8[^\s"]*""")
                )
                
                for (regex in videoRegexes) {
                    val match = regex.find(unpacked)
                    if (match != null) {
                        var videoUrl = match.groupValues[1]
                        Log.i("MySupervideoExtractor", "✅ Found video URL with regex: $videoUrl")
                        
                        // Decodifica se necessario
                        videoUrl = if (videoUrl.contains("%")) {
                            URLDecoder.decode(videoUrl, "UTF-8")
                        } else {
                            videoUrl
                        }
                        
                        // Assicurati che sia un URL completo
                        if (!videoUrl.startsWith("http")) {
                            videoUrl = if (videoUrl.startsWith("//")) {
                                "https:$videoUrl"
                            } else {
                                "$mainUrl$videoUrl"
                            }
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Supervideo",
                                url = videoUrl,
                                type = ExtractorLinkType.M3U8
                            ){
                                this.referer = referer ?: url
                                quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                }
            }
            
            // Passo 3: Se non trova lo script eval, cerca pattern alternativi
            val directVideoRegexes = listOf(
                Regex("""player\.load\({[^}]+file:\s*"([^"]+)"""),
                Regex("""jwplayer\("[^"]+"\)\.setup\({[^}]+file:\s*"([^"]+)"""),
                Regex("""src\s*=\s*"([^"]+\.m3u8)""")
            )
            
            for (regex in directVideoRegexes) {
                val match = regex.find(html)
                if (match != null) {
                    var videoUrl = match.groupValues[1]
                    Log.i("MySupervideoExtractor", "✅ Found direct video URL: $videoUrl")
                    
                    videoUrl = if (videoUrl.contains("%")) {
                        URLDecoder.decode(videoUrl, "UTF-8")
                    } else {
                        videoUrl
                    }
                    
                    if (!videoUrl.startsWith("http")) {
                        videoUrl = if (videoUrl.startsWith("//")) {
                            "https:$videoUrl"
                        } else {
                            "$mainUrl$videoUrl"
                        }
                    }
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Supervideo",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ){
                            this.referer = referer ?: url
                            quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            }
            
            Log.e("MySupervideoExtractor", "❌ No video URL found in page")
            
        } catch (e: Exception) {
            Log.e("MySupervideoExtractor", "❌ Error during extraction: ${e.message}")
            e.printStackTrace()
        }
    }
}
