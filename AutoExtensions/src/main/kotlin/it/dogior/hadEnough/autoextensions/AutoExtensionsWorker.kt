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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "AutoExtensions"

// Give CloudStream's own startup sequence (which loads already-installed
// plugins) time to finish before we touch the plugin system ourselves.
private const val INITIAL_DELAY_MS = 8_000L

// How many extensions "Update all" downloads at the same time.
private const val UPDATE_PARALLELISM = 4

// Same as this plugin's Gradle module name: used as a second safety net to
// make sure "Update all" never replaces the plugin that is running it.
private const val SELF_INTERNAL_NAME = "AutoExtensions"

/** [summary] is shown to the user; [changed] is how many extensions were actually downloaded/updated. */
private data class TaskResult(val summary: String, val changed: Int)

/**
 * Brings back two of the extension settings CloudStream removed, built
 * entirely on PUBLIC, non-internal API (the same functions the Extensions
 * screen itself uses for a manual tap):
 *  - RepositoryManager.getRepositories() / PREBUILT_REPOSITORIES / getRepoPlugins()
 *    to read the same repository list and plugin index the Extensions screen reads.
 *  - PluginManager.getPluginPath() / getPluginsOnline() to know what is installed.
 *  - PluginManager.downloadPlugin(...) — the single-plugin, UNGUARDED function
 *    — to download+register+load each extension.
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

    /** Triggered by the "Update all now" button in the settings panel. */
    fun runUpdateAllNow() {
        runTask(isAutomatic = false, startMessage = "Updating extensions…") { activity, context ->
            performUpdateAll(activity, context)
        }
    }

    private fun runCheck(isAutomatic: Boolean) {
        runTask(isAutomatic, startMessage = null) { activity, context ->
            performCheck(activity, context)
        }
    }

    private fun runTask(
        isAutomatic: Boolean,
        startMessage: String?,
        block: suspend (Activity, Context) -> TaskResult,
    ) {
        if (isRunning) {
            if (!isAutomatic) {
                CommonActivity.activity?.let { showToast(it, "Another task is already running", Toast.LENGTH_SHORT) }
            }
            return
        }

        val activity = CommonActivity.activity
        if (activity == null) {
            Log.w(TAG, "No activity available yet, skipping task")
            return
        }
        val context = activity.applicationContext

        if (isAutomatic && !AutoExtensionsPrefs.isEnabled(context)) return

        isRunning = true
        if (startMessage != null) {
            runCatching { showToast(activity, startMessage, Toast.LENGTH_SHORT) }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { block(activity, context) }
            isRunning = false

            val taskResult = result.getOrElse { t ->
                Log.e(TAG, "Task failed", t)
                TaskResult("Failed: ${t.javaClass.simpleName}", 0)
            }
            AutoExtensionsPrefs.setLastRun(context, System.currentTimeMillis(), taskResult.summary)

            withContext(Dispatchers.Main) {
                hostPlugin?.onCheckFinished(taskResult.summary)
                // Only pop a toast on a manual run, or on an automatic run
                // that actually found something — an automatic run with
                // nothing new shouldn't bother the user every time the app opens.
                if (!isAutomatic || taskResult.changed > 0) {
                    runCatching { showToast(activity, taskResult.summary, Toast.LENGTH_LONG) }
                }
            }
        }
    }

    private suspend fun loadAllRepoPlugins(): Pair<Int, List<PluginWrapper>> {
        val repos = (RepositoryManager.getRepositories().toList() + RepositoryManager.PREBUILT_REPOSITORIES.toList())
            .distinctBy { it.url }
        val plugins = repos.flatMap { repo ->
            runCatching { RepositoryManager.getRepoPlugins(repo) }.getOrNull().orEmpty()
        }.distinctBy { it.plugin.url }
        return repos.size to plugins
    }

    /** Downloads every extension from the repositories that isn't installed yet. */
    private suspend fun performCheck(activity: Activity, context: Context): TaskResult {
        val includeNsfw = AutoExtensionsPrefs.isNsfwIncluded(context)
        val (repoCount, allPlugins) = loadAllRepoPlugins()

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

        return TaskResult(
            "Checked ${allPlugins.size} extensions across $repoCount repositories, $downloaded new downloaded.",
            downloaded,
        )
    }

    /**
     * Same as CloudStream's "Update plugins manually": forcibly re-downloads
     * every installed online extension from its repository (no version
     * check), and unloads the ones the repository marks as down.
     *
     * Differences from the original, on purpose:
     *  - The old file is NOT deleted first: the download is atomic (temp
     *    file + move), so if the network drops the installed copy stays intact.
     *  - This plugin itself is skipped, so it never unloads/replaces its own
     *    code while it is running.
     */
    private suspend fun performUpdateAll(activity: Activity, context: Context): TaskResult {
        val (_, onlinePlugins) = loadAllRepoPlugins()

        // File path of this very plugin, as registered by the plugin manager.
        val selfPath = PluginManager.plugins.entries.firstOrNull { it.value === hostPlugin }?.key

        val installed = PluginManager.getPluginsOnline().flatMap { saved ->
            onlinePlugins
                .filter { it.plugin.internalName == saved.internalName }
                .mapNotNull { online ->
                    PluginManager.OnlinePluginData(saved, online).takeIf { it.validOnlineData(activity) }
                }
        }.distinctBy { it.onlineData.plugin.url }

        val updated = AtomicInteger(0)
        val skippedSelf = AtomicInteger(0)
        val semaphore = Semaphore(UPDATE_PARALLELISM)

        coroutineScope {
            installed.map { item ->
                async {
                    val isSelf = item.savedData.filePath == selfPath ||
                        item.savedData.internalName.equals(SELF_INTERNAL_NAME, ignoreCase = true)
                    if (isSelf) {
                        skippedSelf.incrementAndGet()
                        return@async
                    }
                    semaphore.withPermit {
                        if (item.isDisabled) {
                            Log.w(TAG, "Unloading disabled extension: ${item.onlineData.plugin.name}")
                            runCatching { PluginManager.unloadPlugin(item.savedData.filePath) }
                        } else {
                            val ok = runCatching {
                                PluginManager.downloadPlugin(
                                    activity,
                                    item.onlineData.plugin.url,
                                    item.onlineData.plugin.fileHash,
                                    item.savedData.internalName,
                                    File(item.savedData.filePath),
                                    true,
                                )
                            }.getOrDefault(false)
                            if (ok) updated.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        val count = updated.get()
        val summary = if (count > 0) {
            "Updated $count of ${installed.size} extensions."
        } else {
            "No extensions were updated."
        }
        return TaskResult(summary + if (skippedSelf.get() > 0) " (AutoExtensions itself was skipped.)" else "", count)
    }
}
