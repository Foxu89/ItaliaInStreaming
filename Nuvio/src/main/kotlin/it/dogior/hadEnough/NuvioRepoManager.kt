package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps track of the Nuvio repositories the user added (manifest.json + scraper .js files),
 * stores the scripts on disk and remembers which scrapers are switched off.
 */
class NuvioRepoManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private val root = File(appContext.filesDir, "nuvio").apply { mkdirs() }

    // ── repositories ──

    fun repos(): List<NuvioRepo> {
        val json = prefs.getString(KEY_REPOS, null) ?: return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val list = mutableListOf<NuvioRepo>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(repoFromJson(obj))
        }
        return list
    }

    private fun saveRepos(list: List<NuvioRepo>) {
        val arr = JSONArray()
        list.forEach { arr.put(repoToJson(it)) }
        prefs.edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    /**
     * Accepts a raw GitHub URL (with or without /manifest.json), a normal github.com repository URL
     * or a "owner/repo" style link, downloads the manifest and every scraper.
     */
    suspend fun addRepo(input: String): NuvioSyncReport {
        val candidates = candidateBases(input)
        if (candidates.isEmpty()) throw IllegalArgumentException("URL non valido")
        var lastError: Exception? = null
        for (base in candidates) {
            try {
                return sync(base)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("manifest.json non trovato")
    }

    suspend fun refreshRepo(id: String): NuvioSyncReport {
        val repo = repos().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Repository non trovato")
        return sync(repo.url)
    }

    fun removeRepo(id: String) {
        saveRepos(repos().filterNot { it.id == id })
        File(root, id).deleteRecursively()
        val overrides = overrides()
        val keys = overrides.keys().asSequence().filter { it.startsWith("$id:") }.toList()
        if (keys.isNotEmpty()) {
            keys.forEach { overrides.remove(it) }
            prefs.edit().putString(KEY_ENABLED, overrides.toString()).apply()
        }
    }

    private suspend fun sync(base: String): NuvioSyncReport {
        val resp = app.get("$base/manifest.json", timeout = 60L)
        if (!resp.isSuccessful) {
            throw IllegalStateException("manifest.json non raggiungibile (HTTP ${resp.code})")
        }
        val manifest = try {
            JSONObject(resp.text)
        } catch (_: Exception) {
            throw IllegalStateException("manifest.json non e' un JSON valido")
        }
        val scrapersJson = manifest.optJSONArray("scrapers")
            ?: throw IllegalStateException("manifest.json senza la lista \"scrapers\"")

        val scrapers = mutableListOf<NuvioScraper>()
        for (i in 0 until scrapersJson.length()) {
            val o = scrapersJson.optJSONObject(i) ?: continue
            val filename = o.str("filename") ?: continue
            val id = o.str("id") ?: filename
            scrapers.add(
                NuvioScraper(
                    id = id,
                    name = o.str("name") ?: id,
                    description = o.str("description") ?: "",
                    version = o.str("version") ?: "",
                    filename = filename,
                    types = o.stringList("supportedTypes"),
                    languages = o.stringList("contentLanguage"),
                    enabledByDefault = o.optBoolean("enabled", true),
                )
            )
        }
        if (scrapers.isEmpty()) throw IllegalStateException("Nessuno scraper nel manifest.json")

        val repoId = repoId(base)
        val dir = File(root, repoId).apply { mkdirs() }

        val outcomes = scrapers.amap { s ->
            try {
                val r = app.get("$base/${s.filename.trimStart('/')}", timeout = 60L)
                if (!r.isSuccessful || r.text.isBlank()) {
                    throw IllegalStateException("HTTP ${r.code}")
                }
                File(dir, safeName(s.filename)).writeText(r.text)
                true to s.name
            } catch (_: Exception) {
                false to s.name
            }
        }

        val repo = NuvioRepo(
            id = repoId,
            url = base,
            name = manifest.str("name") ?: base.removePrefix("https://").substringBefore('/'),
            description = manifest.str("description") ?: "",
            scrapers = scrapers,
        )
        val all = repos().toMutableList()
        val idx = all.indexOfFirst { it.id == repoId }
        if (idx >= 0) all[idx] = repo else all.add(repo)
        saveRepos(all)

        return NuvioSyncReport(
            repo = repo,
            downloaded = outcomes.count { it.first },
            failed = outcomes.filterNot { it.first }.map { it.second },
        )
    }

    // ── scrapers ──

    fun isEnabled(repo: NuvioRepo, scraper: NuvioScraper): Boolean {
        val key = "${repo.id}:${scraper.id}"
        val o = overrides()
        return if (o.has(key)) o.optBoolean(key, scraper.enabledByDefault) else scraper.enabledByDefault
    }

    fun setEnabled(repo: NuvioRepo, scraper: NuvioScraper, enabled: Boolean) {
        val o = overrides()
        o.put("${repo.id}:${scraper.id}", enabled)
        prefs.edit().putString(KEY_ENABLED, o.toString()).apply()
    }

    /** Enabled scrapers whose script was actually downloaded. */
    fun enabledScrapers(): List<Pair<NuvioRepo, NuvioScraper>> {
        val out = mutableListOf<Pair<NuvioRepo, NuvioScraper>>()
        for (repo in repos()) {
            for (s in repo.scrapers) {
                if (isEnabled(repo, s) && scriptFile(repo, s).exists()) out.add(repo to s)
            }
        }
        return out
    }

    fun readScript(repo: NuvioRepo, scraper: NuvioScraper): String? {
        val f = scriptFile(repo, scraper)
        return if (f.exists()) f.readText() else null
    }

    private fun scriptFile(repo: NuvioRepo, scraper: NuvioScraper) =
        File(File(root, repo.id), safeName(scraper.filename))

    private fun overrides(): JSONObject {
        val json = prefs.getString(KEY_ENABLED, null) ?: return JSONObject()
        return try { JSONObject(json) } catch (_: Exception) { JSONObject() }
    }

    // ── global settings ──

    /** Exposed to every scraper as the global SCRAPER_SETTINGS object. Always a valid JSON object. */
    var scraperSettingsJson: String
        get() {
            val s = prefs.getString(KEY_SETTINGS, "{}") ?: "{}"
            return try { JSONObject(s).toString() } catch (_: Exception) { "{}" }
        }
        set(value) {
            val clean = try { JSONObject(value).toString() } catch (_: Exception) { "{}" }
            prefs.edit().putString(KEY_SETTINGS, clean).apply()
        }

    var timeoutSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT).coerceIn(5, 180)
        set(value) {
            prefs.edit().putInt(KEY_TIMEOUT, value.coerceIn(5, 180)).apply()
        }

    // ── helpers ──

    companion object {
        private const val PREF_FILE = "Nuvio"
        private const val KEY_REPOS = "repos"
        private const val KEY_ENABLED = "enabled_overrides"
        private const val KEY_SETTINGS = "scraper_settings"
        private const val KEY_TIMEOUT = "timeout_seconds"
        const val DEFAULT_TIMEOUT = 45

        fun repoId(base: String): String = Integer.toHexString(base.lowercase().hashCode())

        private fun safeName(filename: String): String =
            filename.trimStart('/').replace(Regex("[^A-Za-z0-9._-]"), "_")

        /** Possible base URLs (without /manifest.json) for what the user typed. */
        fun candidateBases(input: String): List<String> {
            var s = input.trim().trim('"', '\'').trim()
            if (s.isEmpty()) return emptyList()
            s = s.removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")
            if (!s.startsWith("http", ignoreCase = true)) s = "https://$s"

            val gh = Regex(
                "^https?://(?:www\\.)?github\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?(?:/tree/([^/#?]+))?/?$",
                RegexOption.IGNORE_CASE
            ).find(s)
            if (gh != null) {
                val owner = gh.groupValues[1]
                val repo = gh.groupValues[2]
                val branch = gh.groupValues[3]
                val branches = if (branch.isNotEmpty()) listOf(branch) else listOf("main", "master")
                return branches.map { "https://raw.githubusercontent.com/$owner/$repo/refs/heads/$it" }
            }
            return listOf(s)
        }

        private fun JSONObject.str(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key, "").trim().ifEmpty { null }
        }

        private fun JSONObject.stringList(key: String): List<String> {
            val arr = optJSONArray(key) ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                if (!arr.isNull(i)) out.add(arr.optString(i))
            }
            return out
        }

        private fun repoToJson(r: NuvioRepo): JSONObject = JSONObject().apply {
            put("id", r.id)
            put("url", r.url)
            put("name", r.name)
            put("description", r.description)
            put("scrapers", JSONArray().apply {
                r.scrapers.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("description", s.description)
                        put("version", s.version)
                        put("filename", s.filename)
                        put("types", JSONArray(s.types))
                        put("languages", JSONArray(s.languages))
                        put("enabled", s.enabledByDefault)
                    })
                }
            })
        }

        private fun repoFromJson(o: JSONObject): NuvioRepo {
            val arr = o.optJSONArray("scrapers") ?: JSONArray()
            val scrapers = mutableListOf<NuvioScraper>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                scrapers.add(
                    NuvioScraper(
                        id = s.optString("id"),
                        name = s.optString("name"),
                        description = s.optString("description"),
                        version = s.optString("version"),
                        filename = s.optString("filename"),
                        types = s.stringList("types"),
                        languages = s.stringList("languages"),
                        enabledByDefault = s.optBoolean("enabled", true),
                    )
                )
            }
            return NuvioRepo(
                id = o.optString("id"),
                url = o.optString("url"),
                name = o.optString("name"),
                description = o.optString("description"),
                scrapers = scrapers,
            )
        }
    }
}
