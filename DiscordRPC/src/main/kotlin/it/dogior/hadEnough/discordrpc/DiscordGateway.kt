package it.dogior.hadEnough.discordrpc

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "DiscordRPC"
private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

private const val OP_DISPATCH = 0
private const val OP_HEARTBEAT = 1
private const val OP_IDENTIFY = 2
private const val OP_PRESENCE = 3
private const val OP_RESUME = 6
private const val OP_INVALID_SESSION = 9
private const val OP_HELLO = 10

/**
 * Client del Gateway WebSocket di Discord (protocollo v10).
 *
 * Annotazione privacy: usa il token DELL'UTENTE (self-bot). Discord permette
 * ai bot di aggiornare la presenza, ma l'automazione dell'account personale è
 * contro i Terms of Service: il rischio è il ban dell'account.
 *
 * Il token viene inviato SOLO a Discord (op 2 IDENTIFY / op 6 RESUME), mai
 * altrove. Nessun dato di riproduzione viene condiviso con terze parti.
 */
class DiscordGateway(
    private val onReady: (username: String) -> Unit,
    private val onDispatch: (JsonObject) -> Unit,
    private val onClosed: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "discord-rpc-scheduler").apply { isDaemon = true }
    }

    private var socket: WebSocket? = null
    private var token: String? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var sessionId: String? = null
    private var lastSequence: Int? = null

    /**
     * Differenza (ms) tra l'orologio di Discord (header HTTP "Date" dell'handshake)
     * e quello del device. Se il device ha ora/data imprecise, sommarlo a
     * System.currentTimeMillis() rende i timestamps della presenza corretti.
     */
    @Volatile
    var serverOffsetMs: Long = 0L
        private set

    val isOpen: Boolean
        get() = socket != null

    fun connect(accessToken: String) {
        if (isOpen) return
        token = accessToken
        openSocket()
    }

    private fun openSocket() {
        val request = Request.Builder().url(GATEWAY_URL).build()
        try {
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "🔌 socket aperto")
                    readServerOffset(response)
                    // il gateway manderà op 10 HELLO con l'intervallo di heartbeat
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        handleFrame(json.parseToJsonElement(text) as JsonObject)
                    } catch (t: Throwable) {
                        Log.e(TAG, "onMessage: parse error", t)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onClosed()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                    onFailure(t)
                }
            })
        } catch (t: Throwable) {
            onFailure(t)
        }
    }

    private fun handleFrame(frame: JsonObject) {
        val op = frame["op"]?.jsonPrimitive?.int ?: return

        when (op) {
            OP_HELLO -> {
                val d = frame["d"] as? JsonObject
                val interval = d?.get("heartbeat_interval")?.let { runCatching { it.jsonPrimitive.long }.getOrNull() } ?: 41250
                Log.i(TAG, "👋 HELLO ricevuto (heartbeat ${interval}ms) -> IDENTIFY")
                sendIdentify()
                startHeartbeat(interval)
            }

            OP_HEARTBEAT -> { // server ci chiede esplicitamente un heartbeat
                sendHeartbeat()
            }

            OP_INVALID_SESSION -> {
                // sessione scaduta/rifiutata: riparte da zero con IDENTIFY
                Log.i(TAG, "♻️ INVALID_SESSION, riautenticazione da zero")
                stopHeartbeat()
                sessionId = null
                lastSequence = null
                runCatching { socket?.cancel() }
                socket = null
                openSocket()
            }

            OP_DISPATCH -> {
                // d può essere oggetto, array o null a seconda dell'evento: niente crash
                val data = frame["d"] as? JsonObject ?: JsonObject(emptyMap())
                lastSequence = frame["s"]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() } ?: lastSequence

                when (frame["t"]?.jsonPrimitive?.content) {
                    "READY" -> {
                        sessionId = data["session_id"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                        val user = data["user"] as? JsonObject ?: JsonObject(emptyMap())
                        val username = user["username"]?.jsonPrimitive?.content ?: "User"
                        val discriminator = user["discriminator"]?.jsonPrimitive?.content
                        val uId = user["id"]?.jsonPrimitive?.content
                        val label = if (discriminator == "0" || discriminator.isNullOrEmpty()) username else "$username#$discriminator"
                        onReady(label)
                        RPCSettings.username = label
                        Log.i(TAG, "✅ READY, loggato come $label (id=$uId)")
                    }
                }
                onDispatch(data)
            }
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        stopHeartbeat()
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            { runCatching { sendHeartbeat() } },
            intervalMs, intervalMs, TimeUnit.MILLISECONDS
        )
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(true)
        heartbeatFuture = null
    }

    private fun sendHeartbeat() {
        val payload = buildJsonObject {
            put("op", OP_HEARTBEAT)
            put("d", lastSequence?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        socket?.send(payload.toString())
    }

    /** Sincronizza l'orologio con quello di Discord usando l'header "Date" dell'handshake. */
    private fun readServerOffset(response: Response) {
        val header = response.header("Date") ?: run {
            Log.w(TAG, "🕰️ nessun header Date, offset=0")
            return
        }
        val serverMs = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .parse(header)?.time
        }.getOrNull()
        if (serverMs != null) {
            serverOffsetMs = serverMs - System.currentTimeMillis()
            Log.i(TAG, "🕰️ server=$header offset=${serverOffsetMs}ms")
        } else {
            Log.w(TAG, "🕰️ header Date non parsabile: $header")
        }
    }

    private fun sendIdentify() {
        val tokenValue = token ?: return
        val payload = buildJsonObject {
            put("op", OP_IDENTIFY)
            put("d", buildJsonObject {
                put("token", tokenValue)
                put("intents", 0)
                put("properties", buildJsonObject {
                    put("os", "android")
                    put("browser", "cloudstream")
                    put("device", "cloudstream")
                })
            })
        }
        socket?.send(payload.toString())
    }

    /** Aggiornamento presenza (op 3): attività [activities] o lista vuota per pulire. */
    fun sendPresence(activities: JsonArray, status: String = "online") {
        if (!isOpen) return
        val payload = buildJsonObject {
            put("op", OP_PRESENCE)
            put("d", buildJsonObject {
                put("since", JsonNull)
                put("activities", activities)
                put("status", status)
                put("afk", false)
            })
        }
        Log.i(TAG, "📡 op3 payload: $payload")
        runCatching {
            socket?.send(payload.toString())
        }
    }

    /**
     * Riconnessione con RESUME: inviando session_id e ultima sequenza ricetraino
     * lo stato senza dover ri-autenticare. Se il server rifiuta (INVALID_SESSION)
     * il chiamante dovrà riaprire da zero.
     */
    fun resumeOrReopen(accessToken: String) {
        token = accessToken
        if (sessionId != null && lastSequence != null) {
            val payload = buildJsonObject {
                put("op", OP_RESUME)
                put("d", buildJsonObject {
                    put("token", token.orEmpty())
                    put("session_id", sessionId.orEmpty())
                    put("seq", lastSequence!!)
                })
            }
            try {
                socket?.send(payload.toString())
                return
            } catch (t: Throwable) {
                Log.w(TAG, "resume failed, reopening", t)
            }
        }
        // fallback: chiudi e riparti da zero
        stopHeartbeat()
        socket?.cancel()
        socket = null
        openSocket()
    }

    fun close() {
        stopHeartbeat()
        scheduler.shutdown()
        socket?.close(1000, "bye")
        socket = null
        sessionId = null
        lastSequence = null
    }
}

/**
 * Risolve le immagini esterne in asset path di Discord ("mp:external/...").
 *
 * Rich Presence NON accetta URL HTTP diretti in large_image: un URL esterno
 * va prima registrato con l'endpoint
 * POST /applications/{app}/external-assets {"urls": [...]} (stesso metodo di
 * Navidrome/Navicord, l'unica via che funziona in 2026). Se il poster non è
 * registrabile restituisce null e l'attività viene inviata senza immagine
 * (così un URL invalido non fa scartare tutta la presenza).
 */
object DiscordAssets {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val cache = ConcurrentHashMap<String, String>()
    // il chiamante (buildActivity) gira sul main thread: le chiamate di rete
    // vanno spinte fuori dal main per non far lanciare NetworkOnMainThreadException
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "discord-rpc-assets").apply { isDaemon = true }
    }

    fun externalPath(url: String): String? {
        Log.i(TAG, "🖼️ richiesto poster: $url")
        // l'SDK può appendere un suffisso di risoluzione tipo " 2x": va tolto
        val clean = url.replace(Regex("\\s+\\dx$"), "").trim()
        val httpsUrl = when {
            clean.startsWith("https://") -> clean
            clean.startsWith("http://") -> {
                Log.i(TAG, "🖼️ poster http:// normalizzato a https://")
                "https://" + clean.removePrefix("http://")
            }
            else -> {
                Log.i(TAG, "🖼️ poster NON http(s), saltato")
                return null
            }
        }
        cache[httpsUrl]?.let { return it }

        val token = RPCSettings.token
        val appId = RPCSettings.applicationId
        if (token.isBlank() || appId.isBlank()) {
            Log.i(TAG, "🖼️ poster saltato: niente token o app id")
            return null
        }

        val result = runCatching {
            attempt(httpsUrl, token, appId) ?: attemptLegacy(httpsUrl, token, appId)
        }.getOrNull()

        if (result != null) {
            Log.i(TAG, "🖼️ poster registrato -> $result")
            cache[httpsUrl] = result
        } else {
            Log.i(TAG, "🖼️ poster NON registrabile: $httpsUrl")
        }
        return result
    }

    /** Endpoint di Navidrome/Navicord: POST /applications/{app}/external-assets. */
    private fun attempt(url: String, token: String, appId: String): String? {
        val body = buildJsonObject {
            put("urls", buildJsonArray { add(url) })
        }.toString()
        val request = Request.Builder()
            .url("https://discord.com/api/v9/applications/$appId/external-assets")
            .header("Authorization", token)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody())
            .build()
        return execute(request)
    }

    /** Endpoint documentato (discord.js-selfbot-v13): GET oauth2 .../assets/external. */
    private fun attemptLegacy(url: String, token: String, appId: String): String? {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url("https://discord.com/api/v9/oauth2/applications/$appId/assets/external?url=$encoded")
            .header("Authorization", token)
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String? {
        val future = executor.submit<String?> {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                Log.i(TAG, "🖼️ ${request.method} ${request.url} -> ${resp.code}: ${text.take(200)}")
                if (!resp.isSuccessful) return@use null

                val el = Json { ignoreUnknownKeys = true }.parseToJsonElement(text)
                val raw = when (el) {
                    is JsonArray -> el.firstOrNull()?.jsonObject?.get("external_asset_path")
                    is JsonObject -> el["external_asset_path"]
                    else -> null
                }?.let { safe -> runCatching { safe.jsonPrimitive.content }.getOrNull() }
                raw?.let { path -> if (path.startsWith("mp:")) path else "mp:$path" }
            }
        }
        return try {
            future.get(6, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            null
        }
    }
}