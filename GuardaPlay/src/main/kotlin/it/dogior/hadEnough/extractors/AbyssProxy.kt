package it.dogior.hadEnough.extractors

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Supporto al player Abyss (mload.cam).
 *
 * 1. `datas` (base64) contiene slug/md5_id/user_id e `media` cifrato con AES-CTR;
 *    la chiave e' md5("user_id:slug:md5_id") in esadecimale (32 caratteri ASCII = chiave AES-256,
 *    i primi 16 byte sono anche il contatore iniziale).
 * 2. `media` elenca le qualita' (size, res_id, label, codec, sub) e i domini dei server.
 * 3. Ogni pezzo da `chunk` byte si scarica da https://{dominio}/sora/{size}/{token}, dove token e'
 *    il percorso "/mp4/{md5_id}/{res_id}/{size}/{chunk}/{n}" cifrato con AES-CTR
 *    (chiave = md5 dei VALORI delle cifre di size, non dei caratteri ASCII: e' un'anomalia
 *    della libreria md5 usata dal player) e codificato due volte in base64 senza padding.
 * 4. ExoPlayer non sa parlare con questi pezzi: un piccolo server HTTP locale traduce le
 *    richieste Range in richieste ai pezzi.
 */
object AbyssProxy {
    private const val TAG = "AbyssProxy"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
    const val CHUNK_MAX = 0x200000L

    class Source(
        val label: String,
        val resId: Int,
        val size: Long,
        val codec: String,
        val domain: String,
        val md5Id: Long,
    )

    // ------------------------------------------------------------ cifratura

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun aesCtr(mode: Int, key32: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(mode, SecretKeySpec(key32, "AES"), IvParameterSpec(key32.copyOfRange(0, 16)))
        return c.doFinal(data)
    }

    /** Legge `datas` della pagina del player e restituisce le sorgenti disponibili. */
    fun parseDatas(datasBase64: String): List<Source> {
        val json = JSONObject(String(Base64.decode(datasBase64, Base64.DEFAULT), Charsets.ISO_8859_1))
        val slug = json.getString("slug")
        val md5Id = json.getLong("md5_id")
        val userId = json.getLong("user_id")
        val encrypted = json.getString("media").toByteArray(Charsets.ISO_8859_1)

        val key = md5Hex("$userId:$slug:$md5Id".toByteArray(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        val media = JSONObject(String(aesCtr(Cipher.DECRYPT_MODE, key, encrypted), Charsets.UTF_8))
        val mp4 = media.getJSONObject("mp4")

        val domains = mp4.getJSONArray("domains").let { a -> (0 until a.length()).map { a.getString(it) } }
        if (domains.isEmpty()) return emptyList()
        val sources = mp4.getJSONArray("sources")
        val out = mutableListOf<Source>()
        for (i in 0 until sources.length()) {
            val s = sources.getJSONObject(i)
            if (!s.optBoolean("status", true)) continue
            val size = s.optLong("size", 0L)
            if (size <= 0L) continue
            val sub = s.optString("sub", "")
            val domain = domains.firstOrNull { sub.isNotEmpty() && it.contains(sub) }
                ?: domains[(size % domains.size).toInt()]
            out += Source(
                label = s.optString("label", ""),
                resId = s.getInt("res_id"),
                size = size,
                codec = s.optString("codec", ""),
                domain = domain,
                md5Id = md5Id,
            )
        }
        return out
    }

    fun token(path: String, size: Long): String {
        // il player calcola md5 su un numero: la libreria lo converte in stringa e usa i VALORI delle cifre
        val digits = size.toString().map { (it - '0').toByte() }.toByteArray()
        val key = md5Hex(digits).toByteArray(Charsets.UTF_8)
        val ct = aesCtr(Cipher.ENCRYPT_MODE, key, path.toByteArray(Charsets.UTF_8))
        val flags = Base64.NO_WRAP or Base64.NO_PADDING
        val first = Base64.encodeToString(ct, flags)
        return Base64.encodeToString(first.toByteArray(Charsets.ISO_8859_1), flags)
    }

    private fun chunkSize(size: Long) = if (size > CHUNK_MAX) CHUNK_MAX else size

    // ------------------------------------------------------- server locale

    private val streams = ConcurrentHashMap<String, Source>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    private fun ensureStarted(): ServerSocket {
        server?.let { if (!it.isClosed) return it }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread {
            while (!s.isClosed) {
                try {
                    val sock = s.accept()
                    pool.execute { handle(sock) }
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; name = "AbyssProxy-accept" }.start()
        Log.d(TAG, "proxy locale su porta ${s.localPort}")
        return s
    }

    /** Registra una sorgente e restituisce l'indirizzo locale da dare al player. */
    fun register(source: Source): String {
        val s = ensureStarted()
        val id = UUID.randomUUID().toString().replace("-", "")
        streams[id] = source
        return "http://127.0.0.1:${s.localPort}/$id.mp4"
    }

    private fun fetchPart(src: Source, index: Long): ByteArray? {
        val chunk = chunkSize(src.size)
        val expected = minOf(chunk, src.size - index * chunk).toInt()
        val path = "/mp4/${src.md5Id}/${src.resId}/${src.size}/$chunk/$index"
        val url = "https://${src.domain}/sora/${src.size}/${token(path, src.size)}"
        repeat(4) { attempt ->
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://mload.cam/")
                    .header("Origin", "https://mload.cam")
                    .header("Accept", "*/*")
                    .build()
                app.baseClient.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val bytes = r.body?.bytes()
                        if (bytes != null && bytes.size == expected) return bytes
                        Log.w(TAG, "pezzo $index: ${bytes?.size} byte, attesi $expected")
                        if (bytes != null && bytes.size > expected) return bytes.copyOf(expected)
                    } else {
                        Log.w(TAG, "pezzo $index: HTTP ${r.code} (${src.domain})")
                        if (r.code == 403 || r.code == 404) return null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pezzo $index tentativo $attempt: ${e.message}")
            }
            try {
                Thread.sleep(400L * (attempt + 1))
            } catch (_: InterruptedException) {
            }
        }
        return null
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 60_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val id = parts[1].substringAfterLast('/').substringBefore('.').substringBefore('?')
                val src = streams[id]
                val out = s.getOutputStream()
                if (src == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }

                val size = src.size
                var start = 0L
                var end = size - 1
                val range = headers["range"]
                val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
                if (m != null) {
                    val a = m.groupValues[1]
                    val b = m.groupValues[2]
                    if (a.isEmpty() && b.isNotEmpty()) {
                        start = maxOf(0L, size - b.toLong())
                    } else {
                        if (a.isNotEmpty()) start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                    }
                }
                if (start >= size || start > end) {
                    out.write(
                        ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    return
                }

                val status = if (m != null) "206 Partial Content" else "200 OK"
                val head = StringBuilder("HTTP/1.1 $status\r\n")
                head.append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n")
                head.append("Content-Length: ${end - start + 1}\r\n")
                if (m != null) head.append("Content-Range: bytes $start-$end/$size\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                out.flush()
                if (method == "HEAD") return

                val chunk = chunkSize(size)
                var pos = start
                var prefetchIndex = -1L
                var prefetch: Future<ByteArray?>? = null
                while (pos <= end) {
                    val idx = pos / chunk
                    val pf = prefetch
                    val loaded: ByteArray? = if (pf != null && prefetchIndex == idx) pf.get() else fetchPart(src, idx)
                    val data = loaded ?: break
                    // scarica in anticipo il pezzo successivo mentre scriviamo questo
                    val nextIdx = idx + 1
                    if (nextIdx * chunk <= end) {
                        prefetchIndex = nextIdx
                        prefetch = pool.submit<ByteArray?> { fetchPart(src, nextIdx) }
                    } else {
                        prefetch = null
                        prefetchIndex = -1L
                    }
                    val off = (pos - idx * chunk).toInt()
                    val n = minOf((data.size - off).toLong(), end - pos + 1).toInt()
                    if (n <= 0) break
                    out.write(data, off, n)
                    pos += n
                }
                out.flush()
            } catch (_: Exception) {
                // il player ha chiuso la connessione (seek): normale
            }
        }
    }
}
