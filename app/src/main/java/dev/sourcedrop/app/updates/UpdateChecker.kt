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
    }

    /**
     * @param automatic true for checks nobody asked for right now (background, on open);
     *   those don't spend GitHub's last few calls.
     */
    suspend fun check(app: TrackedApp, automatic: Boolean): Outcome {
        val current = versionSync.sync(app)
        val isGitHub = current.sourceType == TrackedApp.SOURCE_TYPE_GITHUB

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
            updatedAt = now
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
}
