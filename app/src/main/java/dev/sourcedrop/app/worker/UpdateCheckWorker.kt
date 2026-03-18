package dev.sourcedrop.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.sourcedrop.app.SourceDropApplication
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.util.VersionComparator
import kotlinx.coroutines.flow.first

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SourceDropApplication ?: return Result.failure()
        val container = app.container
        val preferences = container.preferences
        val trackedAppRepo = container.trackedAppRepository
        val updateEventRepo = container.updateEventRepository
        val adapterFactory = container.sourceAdapterFactory
        val notificationHelper = container.notificationHelper

        if (!preferences.backgroundChecksEnabled) {
            Log.d(TAG, "Background checks disabled, skipping")
            return Result.success()
        }

        val apps = trackedAppRepo.getAllApps().first()
        val enabledApps = apps.filter { it.checkEnabled }

        Log.d(TAG, "Checking ${enabledApps.size} apps for updates")

        var hasErrors = false

        val versionDetector = container.installedVersionDetector

        for (trackedApp in enabledApps) {
            try {
                // Auto-detect installed version if currentVersion is blank
                var currentApp = trackedApp
                if (currentApp.currentVersion.isBlank() && currentApp.packageName.isNotBlank()) {
                    val detected = versionDetector.getInstalledVersion(currentApp.packageName)
                    if (detected != null) {
                        currentApp = currentApp.copy(
                            currentVersion = detected,
                            updatedAt = System.currentTimeMillis()
                        )
                        trackedAppRepo.updateApp(currentApp)
                    }
                }

                val adapter = adapterFactory.create(currentApp.sourceType)
                val result = adapter.checkForUpdate(currentApp)
                val now = System.currentTimeMillis()

                val isNewer = currentApp.currentVersion.isBlank() ||
                    VersionComparator.isNewer(currentApp.currentVersion, result.version)

                val status = if (isNewer) {
                    TrackedApp.STATUS_UPDATE_AVAILABLE
                } else {
                    TrackedApp.STATUS_UP_TO_DATE
                }

                trackedAppRepo.updateApp(
                    currentApp.copy(
                        latestKnownVersion = result.version,
                        lastCheckedAt = now,
                        lastStatus = status,
                        updatedAt = now
                    )
                )

                if (isNewer) {
                    val existing = updateEventRepo.getEventByVersion(
                        currentApp.id, result.version
                    )
                    if (existing == null) {
                        updateEventRepo.insertEvent(
                            UpdateEvent(
                                trackedAppId = currentApp.id,
                                detectedVersion = result.version,
                                releaseNotes = result.releaseNotes,
                                apkUrl = result.apkUrl,
                                detectedAt = now
                            )
                        )

                        if (preferences.notificationsEnabled) {
                            notificationHelper.showUpdateNotification(
                                appId = currentApp.id,
                                appName = currentApp.displayName,
                                newVersion = result.version,
                                releaseNotes = result.releaseNotes
                            )
                        }
                    }
                }

                Log.d(TAG, "${currentApp.displayName}: $status (${result.version})")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking ${trackedApp.displayName}: ${e.message}")
                val now = System.currentTimeMillis()
                trackedAppRepo.updateApp(
                    trackedApp.copy(
                        lastCheckedAt = now,
                        lastStatus = TrackedApp.STATUS_ERROR,
                        updatedAt = now
                    )
                )
                hasErrors = true
            }
        }

        return if (hasErrors) Result.retry() else Result.success()
    }

    companion object {
        const val TAG = "UpdateCheckWorker"
        const val WORK_NAME = "sourcedrop_update_check"
    }
}
