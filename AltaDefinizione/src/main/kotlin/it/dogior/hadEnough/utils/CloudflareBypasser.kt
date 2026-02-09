package it.dogior.hadEnough.utils

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay

/**
 * 🎯 BYPASSER SEMPLICE MA EFFICACE
 */
object CloudflareBypasser {
    
    suspend fun getWithRetry(url: String, maxRetries: Int = 3): org.jsoup.nodes.Document {
        var lastError: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                // Ritardo crescente tra tentativi
                if (attempt > 1) {
                    delay(attempt * 1000L) // 1s, 2s, 3s
                }
                
                println("🔄 Tentativo $attempt per: ${url.take(50)}...")
                
                // Prova con headers diversi ogni volta
                val headers = when (attempt) {
                    1 -> getHeadersSet1()
                    2 -> getHeadersSet2()
                    else -> getHeadersSet3()
                }
                
                val response = app.get(url, headers = headers, timeout = 30 + (attempt * 10))
                val doc = response.document
                
                // Controlla se è una pagina valida
                if (doc.text().length > 200 && !doc.text().lowercase().contains("cloudflare")) {
                    println("✅ Successo al tentativo $attempt")
                    return doc
                }
                
                throw Exception("Pagina bloccata o vuota")
                
            } catch (e: Exception) {
                lastError = e
                println("❌ Tentativo $attempt fallito: ${e.message}")
            }
        }
        
        throw lastError ?: Exception("Tutti i tentativi falliti")
    }
    
    private fun getHeadersSet1(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9",
            "Referer" to "https://www.google.com/"
        )
    }
    
    private fun getHeadersSet2(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9",
            "Referer" to "https://www.bing.com/"
        )
    }
    
    private fun getHeadersSet3(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )
    }
}
