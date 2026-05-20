package it.dogior.hadEnough

import java.io.File
import java.security.MessageDigest

object StreamITACache {
    private const val TAG = "StreamITACache"
    private const val MAX_MEMORY_ENTRIES = 512
    private const val MAX_DISK_ENTRIES = 1024

    private data class CacheEntry(
        val text: String,
        val expiresAtMs: Long,
    )

    // ==================== Cache in memoria (LRU) ====================
    private val memoryCache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > MAX_MEMORY_ENTRIES
        }
    }

    // ==================== Cache su disco ====================
    private var diskDirectory: File? = null

    fun setDiskDirectory(directory: File) {
        diskDirectory = directory
        runCatching { directory.mkdirs() }
    }

    enum class CacheProfile(val ttlMs: Long) {
        TMDB_HOME(12 * 3600 * 1000L),
        TMDB_SEARCH(6 * 3600 * 1000L),
        TMDB_DETAIL(24 * 3600 * 1000L),
        TMDB_SEASONS(12 * 3600 * 1000L),
        TMDB_ENGLISH_TITLE(24 * 3600 * 1000L),
        ANIME_MAPPING(7 * 24 * 3600 * 1000L),
        ANIME_UNITY_SEARCH(12 * 3600 * 1000L),
        ANIME_UNITY_DETAIL(6 * 3600 * 1000L),
        ANIME_UNITY_EMBED(30 * 60 * 1000L),
        ANIME_WORLD_SEARCH(12 * 3600 * 1000L),
        ANIME_WORLD_DETAIL(6 * 3600 * 1000L),
        ANIME_WORLD_PLAYER(30 * 60 * 1000L),
        ANIME_SATURN_SEARCH(12 * 3600 * 1000L),
        ANIME_SATURN_DETAIL(6 * 3600 * 1000L),
        ANIME_SATURN_PLAYER(30 * 60 * 1000L),
        SUBTITLES(3 * 3600 * 1000L),
        TMDB_LOGO(24 * 3600 * 1000L),
    }

    // ==================== Metodi pubblici ====================
    @Synchronized
    fun get(key: String, allowExpired: Boolean = false): String? {
        // 1. Cerca in memoria
        val entry = memoryCache[key]
        if (entry != null) {
            if (entry.expiresAtMs > System.currentTimeMillis()) {
                return entry.text
            }
            if (allowExpired) return entry.text
            memoryCache.remove(key)
        }

        // 2. Cerca su disco
        return readDisk(key, allowExpired)
    }

    @Synchronized
    fun put(key: String, text: String, profile: CacheProfile) {
        if (text.isBlank()) return
        val expiresAtMs = System.currentTimeMillis() + profile.ttlMs
        val entry = CacheEntry(text = text, expiresAtMs = expiresAtMs)

        // Salva in memoria
        memoryCache[key] = entry

        // Salva su disco
        writeDisk(key, text, expiresAtMs)
    }

    @Synchronized
    fun remove(key: String) {
        memoryCache.remove(key)
        runCatching { cacheFile(key)?.delete() }
    }

    @Synchronized
    fun clear() {
        memoryCache.clear()
        val directory = diskDirectory ?: return
        directory.listFiles { file -> file.isFile && file.extension == "html" }
            .orEmpty()
            .forEach { runCatching { it.delete() } }
    }

    @Synchronized
    fun stats(): String {
        val valid = memoryCache.count { it.value.expiresAtMs > System.currentTimeMillis() }
        val totalMem = memoryCache.size
        val files = diskDirectory
            ?.listFiles { file -> file.isFile && file.extension == "html" }
            .orEmpty()
        val diskBytes = files.sumOf { it.length() }
        val sizeLabel = when {
            diskBytes >= 1024L * 1024L -> String.format("%.1f MB", diskBytes / 1024.0 / 1024.0)
            diskBytes >= 1024L -> String.format("%.1f KB", diskBytes / 1024.0)
            else -> "$diskBytes B"
        }
        return "RAM: $valid/$MAX_MEMORY_ENTRIES elementi\nMemoria: ${files.size} elementi $sizeLabel"
    }

    // ==================== Disco ====================
    private fun readDisk(key: String, allowExpired: Boolean = false): String? {
        val file = cacheFile(key) ?: return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val separator = raw.indexOf('\n')
        if (separator <= 0) {
            runCatching { file.delete() }
            return null
        }

        val expiresAtMs = raw.substring(0, separator).toLongOrNull()
        if (expiresAtMs == null) {
            runCatching { file.delete() }
            return null
        }

        val text = raw.substring(separator + 1)
        if (expiresAtMs <= System.currentTimeMillis()) {
            if (allowExpired) {
                memoryCache[key] = CacheEntry(text, expiresAtMs)
                return text
            }
            runCatching { file.delete() }
            return null
        }

        // Riporta in memoria
        memoryCache[key] = CacheEntry(text, expiresAtMs)
        return text
    }

    private fun writeDisk(key: String, text: String, expiresAtMs: Long) {
        val directory = diskDirectory ?: return
        runCatching {
            directory.mkdirs()
            cacheFile(key)?.writeText("$expiresAtMs\n$text")
            trimDisk(directory)
        }
    }

    private fun cacheFile(key: String): File? {
        val directory = diskDirectory ?: return null
        return File(directory, "${sha256(key)}.html")
    }

    private fun trimDisk(directory: File) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "html" }.orEmpty()
        if (files.size <= MAX_DISK_ENTRIES) return

        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_ENTRIES)
            .forEach { runCatching { it.delete() } }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
