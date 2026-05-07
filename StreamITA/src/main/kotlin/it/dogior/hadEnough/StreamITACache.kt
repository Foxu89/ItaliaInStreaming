package it.dogior.hadEnough

import java.util.concurrent.TimeUnit

object StreamITACache {
    private const val TAG = "StreamITACache"

    private data class CacheEntry(
        val text: String,
        val expiresAtMs: Long,
    )

    private val memoryCache = object : LinkedHashMap<String, CacheEntry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > 256
        }
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
    }

    @Synchronized
    fun get(key: String): String? {
        val entry = memoryCache[key] ?: return null
        if (entry.expiresAtMs > System.currentTimeMillis()) {
            return entry.text
        }
        memoryCache.remove(key)
        return null
    }

    @Synchronized
    fun put(key: String, text: String, profile: CacheProfile) {
        if (text.isBlank()) return
        memoryCache[key] = CacheEntry(
            text = text,
            expiresAtMs = System.currentTimeMillis() + profile.ttlMs,
        )
    }

    @Synchronized
    fun remove(key: String) {
        memoryCache.remove(key)
    }

    @Synchronized
    fun clear() {
        memoryCache.clear()
    }

    @Synchronized
    fun stats(): String {
        val valid = memoryCache.count { it.value.expiresAtMs > System.currentTimeMillis() }
        val expired = memoryCache.size - valid
        return "Cache: $valid validi, $expired scaduti (max 256)"
    }
}
