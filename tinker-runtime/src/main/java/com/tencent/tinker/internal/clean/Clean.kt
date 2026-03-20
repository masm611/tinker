package com.tencent.tinker.internal.clean

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.tencent.tinker.Tinker
import com.tencent.tinker.internal.JobId
import com.tencent.tinker.internal.annotation.DeployProcessOnly
import com.tencent.tinker.internal.errorTypeShouldBeThrown
import com.tencent.tinker.internal.module.oat.OatManager
import com.tencent.tinker.internal.module.patch.CleanedRawPatch
import com.tencent.tinker.internal.module.patch.RawPatchManager
import com.tencent.tinker.internal.util.debugLog
import com.tencent.tinker.internal.util.expected
import com.tencent.tinker.internal.util.infoLog
import com.tencent.tinker.internal.util.isInDeployProcess
import com.tencent.tinker.internal.util.scheduleJob
import com.tencent.tinker.internal.util.traceE
import com.tencent.tinker.internal.util.traceS
import com.tencent.tinker.internal.util.traceTask
import kotlin.concurrent.thread

private const val TAG = "Tinker.Clean"

@DeployProcessOnly
private fun cleanOatDirectories(
    context: Context,
    cleaned: Iterable<CleanedRawPatch>,
    oatManager: OatManager = OatManager.with(context),
) {
    cleaned.forEach {
        traceS("clean.oat.clean(dir = ${it.version})") {
            oatManager.clean(it.directory)
        }
    }
}

@DeployProcessOnly
private fun cleanPatches(
    context: Context,
    strategy: Strategy,
    rawPatchManager: RawPatchManager = RawPatchManager.with(context),
): List<String> {
    val cleaned = traceE("clean.clean(strategy = ${strategy.key})") {
        when (strategy) {
            Strategy.CLEAN_ALL -> rawPatchManager.cleanAll()
            Strategy.CLEAN_OBSOLETE -> rawPatchManager.cleanObsolete()
        }
    }
    cleanOatDirectories(context, cleaned)
    return cleaned.map { it.version }
}

@DeployProcessOnly
private fun cleanPatches(
    context: Context,
    params: JobParameters
): List<String> {
    val strategyIndex = params.extras.getInt(CLEAN_IPC_KEY_STRATEGY, -1)
    if (strategyIndex == -1) {
        throw Tinker.Error(
            Tinker.Error.Clean.MISSING_STRATEGY,
            "Strategy is missing while cleaning patch."
        )
    }
    val strategy = Strategy.entries.getOrNull(strategyIndex)
        ?: throw Tinker.Error(
            Tinker.Error.Clean.INVALID_STRATEGY,
            "Strategy index $strategyIndex is out of range."
        )
    debugLog(TAG) {
        "Cleaning patches with strategy \"${strategy.name.lowercase()}\"."
    }
    return cleanPatches(context, strategy)
}

private const val CLEAN_IPC_KEY_STRATEGY = "s"

private enum class Strategy(val key: String) {
    CLEAN_ALL("clean_all"),
    CLEAN_OBSOLETE("clean_obsolete"),
}

@DeployProcessOnly
class TinkerCleanService : JobService() {

    private fun runTask(params: JobParameters) {
        thread(name = "tinker-clean") {
            infoLog(TAG) {
                "Cleaning request received. Start cleaning."
            }
            val (pair, events) = traceTask("clean") {
                try {
                    val versions = expected<Tinker.Error.Clean, List<String>>("clean patch") {
                        cleanPatches(this, params)
                    }
                    versions to null
                } catch (error: Tinker.Error) {
                    if (error.type in errorTypeShouldBeThrown) {
                        throw error
                    }
                    null to error
                }
            }
            val (versions, error) = pair
            application
                .let { it as? Tinker.App }
                ?.cleanCallback
                ?.apply {
                    onTaskComplete(
                        Tinker.TaskSummary.Clean(
                            error,
                            events,
                            versions,
                        )
                    )
                }
            jobFinished(params, false)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        runTask(params)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return false
    }
}

internal fun Context.cleanAllPatchesByRemote() {
    infoLog(TAG) {
        "Send clean all patches request to remote."
    }
    scheduleJob(
        JobId.CLEAN.id,
        TinkerCleanService::class.java,
    ) {
        putInt(CLEAN_IPC_KEY_STRATEGY, Strategy.CLEAN_ALL.ordinal)
    }
}

internal fun Context.cleanObsoletePatchesByRemote() {
    infoLog(TAG) {
        "Send clean obsolete patches request to remote."
    }
    scheduleJob(
        JobId.CLEAN.id,
        TinkerCleanService::class.java,
    ) {
        putInt(CLEAN_IPC_KEY_STRATEGY, Strategy.CLEAN_OBSOLETE.ordinal)
    }
}

@JvmOverloads
internal fun Context.requestPatchAsUnavailable(
    version: String,
    rawPatchManager: RawPatchManager = RawPatchManager.with(this),
) {
    require(!isInDeployProcess) {
        "Cannot request patch as unavailable in deploy process."
    }
    infoLog(TAG) {
        "Request patch with version \"${version}\" as unavailable."
    }
    rawPatchManager.requestUnavailable(version)
}