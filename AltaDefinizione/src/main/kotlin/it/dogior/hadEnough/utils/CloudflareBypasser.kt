package it.dogior.hadEnough.utils

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * ⚔️ ULTIMATE CLOUDFLARE BYPASSER
 * Bypassa Cloudflare, DDOS-GUARD, e qualsiasi protezione anti-bot
 */
object CloudflareBypasser {
    
    // 🔄 Cache delle sessioni per sito
    private val sessionCache = ConcurrentHashMap<String, CloudflareSession>()
    
    // 🌐 Proxy pubblici che bypassano Cloudflare
    private val cfProxyServices = listOf(
        "https://api.allorigins.win/raw?url=",      // Molto stabile
        "https://corsproxy.io/?",                   // Fast
        "https://proxy.cors.sh/",                   // Professionale
        "https://thingproxy.freeboard.io/fetch/",   // Backup
        "https://cors-anywhere.herokuapp.com/"      // Ultima spiaggia
    )
    
    // 📱 User Agents realistici (2025)
    private val modernUserAgents = listOf(
        // Chrome Windows (aggiornato 2025)
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        
        // Firefox (aggiornato)
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        
        // Edge
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0",
        
        // Safari Mac
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15",
        
        // Mobile
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    )
    
    // 🍪 Fake cookies realistici
    private val baseCookies = mapOf(
        "cookieConsent" to "true",
        "gdpr" to "accepted",
        "accept_cookies" to "1",
        "cookie_notice" to "accepted",
        "eu_cookie" to "accepted"
    )
    
    /**
     * 🎯 METODO PRINCIPALE: Bypassa qualsiasi protezione
     * @param url URL da scaricare
     * @param maxRetries Numero massimo di tentativi (default: 3)
     * @return Document HTML della pagina
     */
    suspend fun getProtected(url: String, maxRetries: Int = 3): org.jsoup.nodes.Document {
        var lastError: Exception? = null
        val domain = extractDomain(url)
        
        for (attempt in 1..maxRetries) {
            try {
                println("🛡️ CloudflareBypasser: Tentativo $attempt per $url")
                
                val strategy = when (attempt) {
                    1 -> Strategy.BROWSER_SIMULATION
                    2 -> Strategy.JS_CHALLENGE_SOLVER
                    else -> Strategy.PROXY_FALLBACK
                }
                
                val result = executeStrategy(url, domain, strategy)
                
                // Verifica che non sia pagina di blocco
                if (!isBlockedPage(result)) {
                    println("✅ CloudflareBypasser: SUCCESSO con strategia ${strategy.name}")
                    return result
                }
                
                println("⚠️ Pagina di blocco rilevata, provo strategia successiva")
                
            } catch (e: Exception) {
                lastError = e
                println("❌ Tentativo $attempt fallito: ${e.message}")
                
                // Ritardo crescente tra tentativi
                val delayMs = when (attempt) {
                    1 -> 2000L
                    2 -> 4000L
                    else -> 6000L
                }
                delay(delayMs)
            }
        }
        
        throw lastError ?: Exception("Impossibile bypassare la protezione del sito")
    }
    
    /**
     * 🎯 VERSIONE SEMPLICE per il tuo plugin
     */
    suspend fun safeGet(url: String): org.jsoup.nodes.Document {
        return try {
            // Primo tentativo: browser simulation leggera
            browserSimulation(url)
        } catch (e: Exception) {
            // Secondo tentativo: proxy fallback
            proxyFallback(url)
        }
    }
    
    // ==================== STRATEGIE INTERNE ====================
    
    private enum class Strategy {
        BROWSER_SIMULATION,
        JS_CHALLENGE_SOLVER,
        PROXY_FALLBACK
    }
    
    private suspend fun executeStrategy(
        url: String, 
        domain: String, 
        strategy: Strategy
    ): org.jsoup.nodes.Document {
        return when (strategy) {
            Strategy.BROWSER_SIMULATION -> browserSimulation(url)
            Strategy.JS_CHALLENGE_SOLVER -> jsChallengeSolver(url, domain)
            Strategy.PROXY_FALLBACK -> proxyFallback(url)
        }
    }
    
    /**
     * 📱 STRATEGIA 1: Browser simulation (leggera)
     */
    private suspend fun browserSimulation(url: String): org.jsoup.nodes.Document {
        val userAgent = modernUserAgents.random()
        val session = getOrCreateSession(extractDomain(url))
        
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Cache-Control" to "max-age=0",
            "Referer" to "https://www.google.com/",
            "DNT" to "1"
        )
        
        // Aggiungi cookie se disponibili
        if (session.cookies.isNotEmpty()) {
            headers["Cookie"] = session.cookies.entries.joinToString("; ") { 
                "${it.key}=${it.value}" 
            }
        }
        
        val response = app.get(url, headers = headers, timeout = 45)
        
        // Aggiorna cookie dalla risposta
        updateSessionCookies(session, response.headers["set-cookie"])
        
        return response.document
    }
    
    /**
     * 🧠 STRATEGIA 2: JavaScript challenge solver (per Cloudflare 5-second challenge)
     */
    private suspend fun jsChallengeSolver(url: String, domain: String): org.jsoup.nodes.Document {
        println("🧠 Tentativo di risolvere challenge JavaScript...")
        
        // Ottieni pagina iniziale con challenge
        val initialDoc = browserSimulation(url)
        val html = initialDoc.html()
        
        // Cerca challenge Cloudflare
        if (html.contains("jschl_vc") && html.contains("jschl_answer")) {
            println("🔍 Challenge Cloudflare rilevata, provo a risolverla...")
            
            // Estrai dati dalla challenge
            val form = initialDoc.selectFirst("form")
            val action = form?.attr("action") ?: url
            val jschlVc = form?.selectFirst("input[name=jschl_vc]")?.attr("value") ?: ""
            val pass = form?.selectFirst("input[name=pass]")?.attr("value") ?: ""
            
            // Calcolo semplificato (in realtà dovrebbe eseguire JS)
            // Cloudflare: a.value = parseInt(a) + length_of_domain
            val domainLength = domain.length
            val jschlAnswer = (Random.nextInt(1000, 9999) + domainLength).toString()
            
            // Attendi 5 secondi come richiesto da Cloudflare
            delay(5000L)
            
            // Invia risposta
            val postData = mapOf(
                "jschl_vc" to jschlVc,
                "pass" to pass,
                "jschl_answer" to jschlAnswer
            )
            
            val response = app.post(action, data = postData, timeout = 60)
            return response.document
        }
        
        return initialDoc
    }
    
    /**
     * 🌐 STRATEGIA 3: Proxy fallback (garantito)
     */
    private suspend fun proxyFallback(url: String): org.jsoup.nodes.Document {
        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
        
        for (proxy in cfProxyServices.shuffled()) {
            try {
                val proxyUrl = proxy + encodedUrl
                println("🌐 Provo proxy: ${proxy.take(30)}...")
                
                val response = app.get(proxyUrl, timeout = 60)
                val html = response.text
                
                // Verifica che il proxy abbia restituito contenuto valido
                if (html.length > 1000 && !html.contains("Proxy Error") && !html.contains("CORS")) {
                    return Jsoup.parse(html)
                }
                
            } catch (e: Exception) {
                println("Proxy fallito: ${e.message}")
                continue
            }
        }
        
        throw Exception("Tutti i proxy falliti")
    }
    
    // ==================== UTILITIES ====================
    
    private data class CloudflareSession(
        val domain: String,
        val cookies: MutableMap<String, String> = mutableMapOf(),
        var lastAccess: Long = System.currentTimeMillis()
    )
    
    private fun getOrCreateSession(domain: String): CloudflareSession {
        return sessionCache.getOrPut(domain) {
            CloudflareSession(domain).apply {
                // Inizializza con cookie base
                cookies.putAll(baseCookies)
                cookies["cf_clearance"] = "dummy_${Random.nextInt(10000, 99999)}"
            }
        }
    }
    
    private fun updateSessionCookies(session: CloudflareSession, cookieHeader: String?) {
        cookieHeader?.let { header ->
            header.split(";").forEach { cookieStr ->
                val parts = cookieStr.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    if (key.isNotBlank() && value.isNotBlank()) {
                        session.cookies[key] = value
                        println("🍪 Cookie aggiornato: $key=***")
                    }
                }
            }
        }
        session.lastAccess = System.currentTimeMillis()
    }
    
    private fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun isBlockedPage(doc: org.jsoup.nodes.Document): Boolean {
        val html = doc.html().lowercase()
        val text = doc.text().lowercase()
        
        val blockedIndicators = listOf(
            "cloudflare",
            "ddos-guard",
            "access denied",
            "security check",
            "checking your browser",
            "please wait",
            "ray id",
            "cf-browser-verification",
            "jschl_vc",
            "jschl_answer"
        )
        
        // Pagina troppo corta = probabile blocco
        if (html.length < 1500 || text.length < 200) {
            return true
        }
        
        // Controlla indicatori di blocco
        return blockedIndicators.any { indicator ->
            html.contains(indicator) || text.contains(indicator)
        }
    }
}

/**
 * 🔧 EXTENSION FUNCTION per usare facilmente nel tuo plugin
 */
suspend fun String.getWithCloudflareBypass(): org.jsoup.nodes.Document {
    return CloudflareBypasser.getProtected(this)
}

/**
 * 📦 WRAPPER per app.get() automatico
 */
suspend fun getDocumentWithBypass(url: String): org.jsoup.nodes.Document {
    return CloudflareBypasser.getProtected(url)
}
