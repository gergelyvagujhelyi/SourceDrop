package dev.sourcedrop.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.sourcedrop.app.SourceDropApplication
import dev.sourcedrop.app.updates.UpdateChecker
import kotlinx.coroutines.flow.first

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SourceDropApplication ?: return Result.failure()
        val container = app.container
        val preferences = container.preferences
        val notificationHelper = container.notificationHelper
        val updateChecker = container.updateChecker

        if (!preferences.backgroundChecksEnabled) {
            Log.d(TAG, "Background checks disabled, skipping")
            return Result.success()
        }

        val enabledApps = container.trackedAppRepository.getAllApps().first().filter { it.checkEnabled }
        Log.d(TAG, "Checking ${enabledApps.size} apps for updates")

        var hasErrors = false
        for (trackedApp in enabledApps) {
            when (val outcome = updateChecker.check(trackedApp, automatic = true)) {
                is UpdateChecker.Outcome.UpdateFound -> {
                    Log.d(TAG, "${trackedApp.displayName}: update ${outcome.result.version}")
                    if (outcome.isNew && preferences.notificationsEnabled) {
                        notificationHelper.showUpdateNotification(
                            appId = outcome.app.id,
                            appName = outcome.app.displayName,
                            newVersion = outcome.result.version,
                            releaseNotes = outcome.result.releaseNotes
                        )
                    }
                }
                is UpdateChecker.Outcome.UpToDate ->
                    Log.d(TAG, "${trackedApp.displayName}: up to date (${outcome.result.version})")
                // Not an error: the next scheduled run tries again, after GitHub's reset.
                is UpdateChecker.Outcome.RateLimited ->
                    Log.d(TAG, "${trackedApp.displayName}: skipped, GitHub rate limit")
                is UpdateChecker.Outcome.Failed -> {
                    Log.e(TAG, "Error checking ${trackedApp.displayName}: ${outcome.error.message}")
                    hasErrors = true
                }
            }
        }

        return if (hasErrors) Result.retry() else Result.success()
    }

    companion object {
        const val TAG = "UpdateCheckWorker"
        const val WORK_NAME = "sourcedrop_update_check"
    }
}
