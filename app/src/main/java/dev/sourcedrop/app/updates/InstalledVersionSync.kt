package dev.sourcedrop.app.updates

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import dev.sourcedrop.app.util.VersionComparator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a tracked app's "current version" equal to what's actually installed on the phone.
 *
 * Apps get updated outside SourceDrop too (an APK installed by hand, adb, SourceDrop updating
 * itself), and without this the stored version goes stale: SourceDrop would show and notify
 * about updates that are already installed. Asks the phone only; never the network.
 */
class InstalledVersionSync(
    private val repository: TrackedAppRepository,
    /** The installed versionName of a package, or null if it isn't installed (or visible). */
    private val installedVersionOf: (String) -> String?,
    /** Called with the app's id when it turns out to be up to date, to clear its notification. */
    private val onCaughtUp: (Long) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /** Brings [app] in line with the phone and returns the up-to-date record. */
    suspend fun sync(app: TrackedApp): TrackedApp {
        if (app.packageName.isBlank()) return app
        val installed = withContext(ioDispatcher) { installedVersionOf(app.packageName) }
            ?: return app // not installed: nothing to compare with, leave the record alone

        // Work on the stored record, not a possibly older copy from the UI.
        val stored = repository.getAppByIdOnce(app.id) ?: return app
        if (installed == stored.currentVersion) return stored

        val latest = stored.latestKnownVersion
        val status = when {
            latest.isBlank() -> stored.lastStatus
            !VersionComparator.isNewer(installed, latest) -> TrackedApp.STATUS_UP_TO_DATE
            stored.lastStatus == TrackedApp.STATUS_UP_TO_DATE -> TrackedApp.STATUS_UPDATE_AVAILABLE
            else -> stored.lastStatus
        }
        val synced = stored.copy(currentVersion = installed, lastStatus = status, updatedAt = clock())
        repository.updateApp(synced)
        if (status == TrackedApp.STATUS_UP_TO_DATE) onCaughtUp(stored.id)
        return synced
    }

    suspend fun syncAll(apps: List<TrackedApp>) {
        for (app in apps) sync(app)
    }
}
