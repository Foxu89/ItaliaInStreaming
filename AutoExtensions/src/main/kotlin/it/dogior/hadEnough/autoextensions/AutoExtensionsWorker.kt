package it.dogior.hadEnough.autoextensions

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.plugins.RepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AutoExtensions"

// Give CloudStream's own startup sequence (which loads already-installed
// plugins) time to finish before we touch the plugin system ourselves.
private const val INITIAL_DELAY_MS = 8_000L

/**
 * Reimplements CloudStream's "auto-download missing extensions" feature
 * entirely on top of PUBLIC, non-internal API:
 *  - RepositoryManager.getRepositories() / PREBUILT_REPOSITORIES / getRepoPlugins()
 *    to read the same repository list and plugin index the Extensions
 *    screen itself reads.
 *  - PluginManager.getPluginPath() to check (the exact same way the
 *    Extensions screen does) whether a given plugin is already downloaded.
 *  - PluginManager.downloadPlugin(...) — the single-plugin, UNGUARDED,
 *    publicly reusable function the Extensions screen calls for a manual
 *    "download" tap — to download+register+load each missing plugin.
 *
 * Deliberately does NOT call any of PluginManager's
 * "___DO_NOT_CALL_FROM_A_PLUGIN_..." functions: those are the bulk/
 * recursive ones, explicitly guarded against being invoked from a plugin
 * (assertNonRecursiveCallstack()), and documented as unsafe to use here.
 */
object AutoExtensionsWorker {
    private val handler = Handler(Looper.getMainLooper())
    private var hostPlugin: AutoExtensionsPlugin? = null
    private var isRunning = false

    fun scheduleInitialCheck(plugin: AutoExtensionsPlugin) {
        hostPlugin = plugin
        handler.postDelayed({ runCheck(isAutomatic = true) }, INITIAL_DELAY_MS)
    }

    /** Triggered by the "Check now" button in the settings panel. */
    fun runCheckNow() {
        runCheck(isAutomatic = false)
    }

    private fun runCheck(isAutomatic: Boolean) {
        if (isRunning) {
            if (!isAutomatic) {
                CommonActivity.activity?.let { showToast(it, "A check is already running", Toast.LENGTH_SHORT) }
            }
            return
        }

        val activity = CommonActivity.activity
        if (activity == null) {
            Log.w(TAG, "No activity available yet, skipping check")
            return
        }
        val context = activity.applicationContext

        if (isAutomatic && !AutoExtensionsPrefs.isEnabled(context)) return

        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { performCheck(activity, context) }
            isRunning = false

            val summary = result.getOrElse { t ->
                Log.e(TAG, "Check failed", t)
                "Check failed: ${t.javaClass.simpleName}"
            }
            AutoExtensionsPrefs.setLastRun(context, System.currentTimeMillis(), summary)

            withContext(Dispatchers.Main) {
                hostPlugin?.onCheckFinished(summary)
                val downloadedSomething = result.getOrNull()?.let { "0 new" !in it } ?: false
                // Only pop a toast on a manual run, or on an automatic run
                // that actually found something — an automatic run with
                // nothing new shouldn't bother the user every time the app opens.
                if (!isAutomatic || downloadedSomething) {
                    runCatching { showToast(activity, summary, Toast.LENGTH_LONG) }
                }
            }
        }
    }

    private suspend fun performCheck(activity: Activity, context: Context): String {
        val includeNsfw = AutoExtensionsPrefs.isNsfwIncluded(context)

        val repos = (RepositoryManager.getRepositories().toList() + RepositoryManager.PREBUILT_REPOSITORIES.toList())
            .distinctBy { it.url }

        val allPlugins: List<PluginWrapper> = repos.flatMap { repo ->
            runCatching { RepositoryManager.getRepoPlugins(repo) }.getOrNull().orEmpty()
        }.distinctBy { it.plugin.url }

        val missing = allPlugins.filter { wrapper ->
            val plugin = wrapper.plugin
            if (plugin.url.isBlank()) return@filter false
            if (wrapper.repositoryData.url.isBlank()) return@filter false
            if (plugin.status == PROVIDER_STATUS_DOWN) return@filter false
            if (!includeNsfw && plugin.tvTypes?.contains("NSFW") == true) return@filter false

            // Same exact check the Extensions screen uses to show the
            // "downloaded" badge: a plugin downloaded through this function
            // will correctly show up there as downloaded too.
            val path = PluginManager.getPluginPath(context, plugin.internalName, wrapper.repositoryData.url)
            !path.exists()
        }

        var downloaded = 0
        for (wrapper in missing) {
            val plugin = wrapper.plugin
            val ok = runCatching {
                PluginManager.downloadPlugin(
                    activity,
                    plugin.url,
                    plugin.fileHash,
                    plugin.internalName,
                    wrapper.repositoryData.url,
                    true, // load it right away, no restart needed
                )
            }.getOrDefault(false)
            if (ok) downloaded++
        }

        return "Checked ${allPlugins.size} extensions across ${repos.size} repositories, $downloaded new downloaded."
    }
}
