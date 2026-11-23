package dev.sourcedrop.app.updates

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import dev.sourcedrop.app.data.repository.UpdateEventRepository
import dev.sourcedrop.app.sourceadapters.AdapterError
import dev.sourcedrop.app.sourceadapters.AdapterResult
import dev.sourcedrop.app.sourceadapters.SourceAdapterFactory
import dev.sourcedrop.app.util.VersionComparator
import kotlinx.coroutines.CancellationException

/**
 * Checks one tracked app's source for a newer version and saves what it finds. Shared by the
 * background worker, the app list and the app's page, which each only decide what to show
 * (or notify) for the [Outcome].
 */
class UpdateChecker(
    private val repository: TrackedAppRepository,
    private val eventRepository: UpdateEventRepository,
    private val adapterFactory: SourceAdapterFactory,
    private val versionSync: InstalledVersionSync,
    private val githubRateLimit: GitHubRateLimit,
    private val releaseFeed: ReleaseFeed = ReleaseFeed { null },
    private val clock: () -> Long = System::currentTimeMillis
) {

    sealed interface Outcome {
        /** The app as saved after this check. */
        val app: TrackedApp

        /** The source has a newer version; [isNew] if it hadn't been seen before. */
        data class UpdateFound(
            override val app: TrackedApp,
            val result: AdapterResult,
            val isNew: Boolean
        ) : Outcome

        data class UpToDate(override val app: TrackedApp, val result: AdapterResult) : Outcome

        /**
         * Not checked because of GitHub's rate limit: it's refusing (until [until]), or for an
         * automatic check, the budget is down to the reserve. The app's status is left as it was.
         */
        data class RateLimited(override val app: TrackedApp, val until: Long?) : Outcome

        data class Failed(override val app: TrackedApp, val error: Exception) : Outcome

        /**
         * Automatic check of a GitHub app whose releases feed shows nothing new since the last
         * full check, so the API wasn't asked; the last result still stands (see [check]).
         */
        data class Unchanged(override val app: TrackedApp) : Outcome
    }

    /**
     * @param automatic true for checks nobody asked for right now (background, on open);
     *   those don't spend GitHub's last few calls.
     */
    suspend fun check(app: TrackedApp, automatic: Boolean): Outcome {
        val current = versionSync.sync(app)
        val isGitHub = current.sourceType == TrackedApp.SOURCE_TYPE_GITHUB

        // The feed doesn't use the API budget, so this works even while GitHub's API refuses.
        val feedTag = if (isGitHub) releaseFeed.newestTag(current) else null
        if (automatic && feedTag != null && nothingNewSinceFullCheck(current, feedTag)) {
            val now = clock()
            val checked = current.copy(lastCheckedAt = now, updatedAt = now)
            repository.updateApp(checked)
            return Outcome.Unchanged(checked)
        }

        if (isGitHub) {
            githubRateLimit.blockedUntil()?.let { return Outcome.RateLimited(current, it) }
            if (automatic && !githubRateLimit.allowsAutomaticCheck()) {
                return Outcome.RateLimited(current, githubRateLimit.resetAt())
            }
        }

        val result = try {
            adapterFactory.create(current.sourceType).checkForUpdate(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdapterError.RateLimitError) {
            return Outcome.RateLimited(current, if (isGitHub) githubRateLimit.blockedUntil() else null)
        } catch (e: Exception) {
            val now = clock()
            val failed = current.copy(
                lastCheckedAt = now,
                lastStatus = TrackedApp.STATUS_ERROR,
                updatedAt = now
            )
            repository.updateApp(failed)
            return Outcome.Failed(failed, e)
        }

        val now = clock()
        val isNewer = current.currentVersion.isBlank() ||
            VersionComparator.isNewer(current.currentVersion, result.version)
        val checked = current.copy(
            latestKnownVersion = result.version,
            lastCheckedAt = now,
            lastStatus = if (isNewer) TrackedApp.STATUS_UPDATE_AVAILABLE else TrackedApp.STATUS_UP_TO_DATE,
            updatedAt = now,
            // What the feed showed when the API was asked; blank (feed unavailable) means the
            // next check asks the API again.
            feedTopTag = if (isGitHub) feedTag.orEmpty() else current.feedTopTag,
            lastFullCheckAt = if (isGitHub) now else current.lastFullCheckAt
        )
        repository.updateApp(checked)
        if (!isNewer) return Outcome.UpToDate(checked, result)

        val isNew = eventRepository.getEventByVersion(current.id, result.version) == null
        if (isNew) {
            eventRepository.insertEvent(
                UpdateEvent(
                    trackedAppId = current.id,
                    detectedVersion = result.version,
                    releaseNotes = result.releaseNotes,
                    apkUrl = result.apkUrl,
                    detectedAt = now
                )
            )
        }
        return Outcome.UpdateFound(checked, result, isNew)
    }

    /**
     * Whether the API can be skipped: the feed's newest tag is the one it showed at the last
     * full check, that check was recent and succeeded, and if an update is waiting, its APK
     * link is known (a release can get its APK attached after it's published).
     */
    private suspend fun nothingNewSinceFullCheck(app: TrackedApp, feedTag: String): Boolean {
        if (app.feedTopTag.isBlank() || feedTag != app.feedTopTag) return false
        val sinceFullCheck = clock() - app.lastFullCheckAt
        if (sinceFullCheck < 0 || sinceFullCheck >= FULL_CHECK_INTERVAL_MS) return false
        if (app.lastStatus == TrackedApp.STATUS_ERROR || app.latestKnownVersion.isBlank()) return false

        val updateWaiting = app.currentVersion.isBlank() ||
            VersionComparator.isNewer(app.currentVersion, app.latestKnownVersion)
        if (updateWaiting) {
            val event = eventRepository.getEventByVersion(app.id, app.latestKnownVersion)
            if (event == null || event.apkUrl.isBlank()) return false
        }
        return true
    }

    companion object {
        /** However quiet the feed, the API is asked at least this often (catches replaced APKs). */
        const val FULL_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
