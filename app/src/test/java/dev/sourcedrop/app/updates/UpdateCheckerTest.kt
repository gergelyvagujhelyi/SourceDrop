package dev.sourcedrop.app.updates

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import dev.sourcedrop.app.data.repository.UpdateEventRepository
import dev.sourcedrop.app.sourceadapters.AdapterError
import dev.sourcedrop.app.sourceadapters.AdapterResult
import dev.sourcedrop.app.sourceadapters.SourceAdapter
import dev.sourcedrop.app.sourceadapters.SourceAdapterFactory
import dev.sourcedrop.app.testutil.FakeTrackedAppDao
import dev.sourcedrop.app.testutil.FakeUpdateEventDao
import dev.sourcedrop.app.testutil.InMemoryRateLimitStore
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class UpdateCheckerTest {

    private val appDao = FakeTrackedAppDao()
    private val eventDao = FakeUpdateEventDao()
    private val repository = TrackedAppRepository(appDao)
    private val eventRepository = UpdateEventRepository(eventDao)
    private val installed = mutableMapOf<String, String>()
    private var now = 1_000_000_000L
    private val rateLimit = GitHubRateLimit(InMemoryRateLimitStore(), { "" }, { now })

    /** What the source answers; counts calls. */
    private var answer: () -> AdapterResult = { AdapterResult(version = "2.0.0") }
    private var sourceCalls = 0
    private val adapter = object : SourceAdapter {
        override suspend fun checkForUpdate(app: TrackedApp): AdapterResult {
            sourceCalls++
            return answer()
        }
    }
    private val factory: SourceAdapterFactory = mock<SourceAdapterFactory>().also {
        whenever(it.create(any())).thenReturn(adapter)
    }

    private fun TestScope.checker() = UpdateChecker(
        repository = repository,
        eventRepository = eventRepository,
        adapterFactory = factory,
        versionSync = InstalledVersionSync(
            repository, { installed[it] }, clock = { now },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        ),
        githubRateLimit = rateLimit,
        releaseFeed = { feedTag },
        clock = { now }
    )

    /** What the releases feed shows as newest; null = feed unavailable. */
    private var feedTag: String? = null

    private suspend fun insert(
        current: String = "1.0.0",
        sourceType: String = TrackedApp.SOURCE_TYPE_GITHUB,
        status: String = TrackedApp.STATUS_UP_TO_DATE
    ): TrackedApp {
        val app = TrackedApp(
            displayName = "Example",
            packageName = "com.example.app",
            sourceType = sourceType,
            sourceUrl = "https://github.com/example/app",
            currentVersion = current,
            latestKnownVersion = current,
            lastStatus = status,
            lastCheckedAt = 5L
        )
        return app.copy(id = appDao.insert(app))
    }

    private fun spendGitHubBudget(remaining: Int) {
        rateLimit.record(200) {
            mapOf(
                "x-ratelimit-remaining" to remaining.toString(),
                "x-ratelimit-reset" to ((now + 3_600_000) / 1000).toString()
            )[it]
        }
    }

    private fun blockGitHub(): Long {
        val reset = (now + 3_600_000) / 1000
        rateLimit.record(403) {
            mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to reset.toString())[it]
        }
        return reset * 1000
    }

    @Test
    fun `newer version is saved and recorded once`() = runTest {
        val app = insert()
        answer = { AdapterResult(version = "2.0.0", apkUrl = "https://example.com/a.apk", releaseNotes = "notes") }

        val first = checker().check(app, automatic = false)
        assertTrue(first is UpdateChecker.Outcome.UpdateFound)
        assertTrue((first as UpdateChecker.Outcome.UpdateFound).isNew)

        val saved = appDao.getByIdOnce(app.id)!!
        assertEquals("2.0.0", saved.latestKnownVersion)
        assertEquals(TrackedApp.STATUS_UPDATE_AVAILABLE, saved.lastStatus)
        assertEquals(now, saved.lastCheckedAt)
        val events = eventDao.getEventsForAppOnce(app.id)
        assertEquals(1, events.size)
        assertEquals("https://example.com/a.apk", events[0].apkUrl)

        val second = checker().check(saved, automatic = false) as UpdateChecker.Outcome.UpdateFound
        assertFalse("seen before, so no second notification", second.isNew)
        assertEquals(1, eventDao.getEventsForAppOnce(app.id).size)
    }

    @Test
    fun `same version is up to date`() = runTest {
        val app = insert(current = "2.0.0")

        val outcome = checker().check(app, automatic = true)

        assertTrue(outcome is UpdateChecker.Outcome.UpToDate)
        assertEquals(TrackedApp.STATUS_UP_TO_DATE, appDao.getByIdOnce(app.id)!!.lastStatus)
        assertTrue(eventDao.getEventsForAppOnce(app.id).isEmpty())
    }

    @Test
    fun `compares with what's installed, not a stale stored version`() = runTest {
        val app = insert(current = "1.0.0")
        installed["com.example.app"] = "2.0.0" // updated outside SourceDrop

        val outcome = checker().check(app, automatic = true)

        assertTrue(outcome is UpdateChecker.Outcome.UpToDate)
        val saved = appDao.getByIdOnce(app.id)!!
        assertEquals("2.0.0", saved.currentVersion)
        assertEquals(TrackedApp.STATUS_UP_TO_DATE, saved.lastStatus)
        assertTrue(eventDao.getEventsForAppOnce(app.id).isEmpty())
    }

    @Test
    fun `blank installed version counts as an update`() = runTest {
        val app = insert(current = "")
        val outcome = checker().check(app, automatic = false)
        assertTrue(outcome is UpdateChecker.Outcome.UpdateFound)
    }

    @Test
    fun `source failure marks the app as error`() = runTest {
        val app = insert()
        answer = { throw AdapterError.NetworkError("offline") }

        val outcome = checker().check(app, automatic = false)

        assertTrue(outcome is UpdateChecker.Outcome.Failed)
        val saved = appDao.getByIdOnce(app.id)!!
        assertEquals(TrackedApp.STATUS_ERROR, saved.lastStatus)
        assertEquals(now, saved.lastCheckedAt)
    }

    @Test
    fun `rate limit from the source is not an error`() = runTest {
        val app = insert(status = TrackedApp.STATUS_UPDATE_AVAILABLE)
        answer = { throw AdapterError.RateLimitError("limit") }

        val outcome = checker().check(app, automatic = false)

        assertTrue(outcome is UpdateChecker.Outcome.RateLimited)
        val saved = appDao.getByIdOnce(app.id)!!
        assertEquals(TrackedApp.STATUS_UPDATE_AVAILABLE, saved.lastStatus)
        assertEquals("not counted as checked", 5L, saved.lastCheckedAt)
    }

    @Test
    fun `while GitHub refuses, nothing calls it, even when asked`() = runTest {
        val app = insert()
        val until = blockGitHub()

        val manual = checker().check(app, automatic = false)
        val automatic = checker().check(app, automatic = true)

        assertEquals(0, sourceCalls)
        assertEquals(until, (manual as UpdateChecker.Outcome.RateLimited).until)
        assertTrue(automatic is UpdateChecker.Outcome.RateLimited)
    }

    @Test
    fun `the reserve is kept for the user's own checks`() = runTest {
        val app = insert()
        spendGitHubBudget(remaining = GitHubRateLimit.RESERVE - 1)

        val automatic = checker().check(app, automatic = true)
        assertTrue(automatic is UpdateChecker.Outcome.RateLimited)
        assertEquals(0, sourceCalls)

        val manual = checker().check(app, automatic = false)
        assertTrue(manual is UpdateChecker.Outcome.UpdateFound)
        assertEquals(1, sourceCalls)
    }

    @Test
    fun `GitHub's limit doesn't hold back other sources`() = runTest {
        val app = insert(sourceType = TrackedApp.SOURCE_TYPE_GITLAB)
        blockGitHub()

        val outcome = checker().check(app, automatic = true)

        assertTrue(outcome is UpdateChecker.Outcome.UpdateFound)
        assertEquals(1, sourceCalls)
    }

    @Test
    fun `GitHub is checked again after the reset`() = runTest {
        val app = insert()
        val until = blockGitHub()

        now = until
        val outcome = checker().check(app, automatic = true)

        assertTrue(outcome is UpdateChecker.Outcome.UpdateFound)
    }

    // ---- Releases feed: skipping the API when nothing changed ----

    /** An app whose last full check (an hour ago) saw [tag] at the top of the feed. */
    private suspend fun checkedRecently(
        tag: String = "v2.0.0",
        current: String = "2.0.0",
        latest: String = "2.0.0",
        status: String = TrackedApp.STATUS_UP_TO_DATE
    ): TrackedApp {
        val app = insert(current = current, status = status)
        val checked = app.copy(
            latestKnownVersion = latest,
            feedTopTag = tag,
            lastFullCheckAt = now - 3_600_000
        )
        repository.updateApp(checked)
        return checked
    }

    @Test
    fun `full check remembers the feed's newest tag`() = runTest {
        val app = insert()
        feedTag = "v2.0.0"

        checker().check(app, automatic = true)

        val saved = appDao.getByIdOnce(app.id)!!
        assertEquals("v2.0.0", saved.feedTopTag)
        assertEquals(now, saved.lastFullCheckAt)
        assertEquals(1, sourceCalls)
    }

    @Test
    fun `nothing new in the feed skips the API`() = runTest {
        val app = checkedRecently()
        feedTag = "v2.0.0"

        val outcome = checker().check(app, automatic = true)

        assertTrue(outcome is UpdateChecker.Outcome.Unchanged)
        assertEquals(0, sourceCalls)
        val saved = appDao.getByIdOnce(app.id)!!
        assertEquals("counts as checked", now, saved.lastCheckedAt)
        assertEquals("but not as a full check", now - 3_600_000, saved.lastFullCheckAt)
        assertEquals(TrackedApp.STATUS_UP_TO_DATE, saved.lastStatus)
    }

    @Test
    fun `new tag in the feed asks the API`() = runTest {
        val app = checkedRecently()
        feedTag = "v2.1.0-beta" // e.g. a pre-release the app ignores: one call, then remembered
        answer = { AdapterResult(version = "2.0.0") }

        assertTrue(checker().check(app, automatic = true) is UpdateChecker.Outcome.UpToDate)
        assertEquals(1, sourceCalls)

        val saved = appDao.getByIdOnce(app.id)!!
        assertEquals("v2.1.0-beta", saved.feedTopTag)
        assertTrue(checker().check(saved, automatic = true) is UpdateChecker.Outcome.Unchanged)
        assertEquals(1, sourceCalls)
    }

    @Test
    fun `feed unavailable asks the API and forgets the tag`() = runTest {
        val app = checkedRecently()
        feedTag = null

        checker().check(app, automatic = true)

        assertEquals(1, sourceCalls)
        assertEquals("", appDao.getByIdOnce(app.id)!!.feedTopTag)
    }

    @Test
    fun `asking the user always goes to the API`() = runTest {
        val app = checkedRecently()
        feedTag = "v2.0.0"

        checker().check(app, automatic = false)

        assertEquals(1, sourceCalls)
    }

    @Test
    fun `a day after the last full check the API is asked anyway`() = runTest {
        val app = checkedRecently()
        repository.updateApp(app.copy(lastFullCheckAt = now - UpdateChecker.FULL_CHECK_INTERVAL_MS))
        feedTag = "v2.0.0"

        checker().check(appDao.getByIdOnce(app.id)!!, automatic = true)

        assertEquals(1, sourceCalls)
    }

    @Test
    fun `never fully checked asks the API`() = runTest {
        val app = insert() // feedTopTag blank
        feedTag = "v2.0.0"

        checker().check(app, automatic = true)

        assertEquals(1, sourceCalls)
    }

    @Test
    fun `an error status asks the API`() = runTest {
        val app = checkedRecently(status = TrackedApp.STATUS_ERROR)
        feedTag = "v2.0.0"

        checker().check(app, automatic = true)

        assertEquals(1, sourceCalls)
    }

    @Test
    fun `update waiting without an APK link asks the API`() = runTest {
        val app = checkedRecently(current = "1.0.0", latest = "2.0.0", status = TrackedApp.STATUS_UPDATE_AVAILABLE)
        eventDao.insert(UpdateEvent(trackedAppId = app.id, detectedVersion = "2.0.0", apkUrl = ""))
        feedTag = "v2.0.0"

        checker().check(app, automatic = true)

        assertEquals(1, sourceCalls)
    }

    @Test
    fun `update waiting with its APK link known skips the API`() = runTest {
        val app = checkedRecently(current = "1.0.0", latest = "2.0.0", status = TrackedApp.STATUS_UPDATE_AVAILABLE)
        eventDao.insert(UpdateEvent(trackedAppId = app.id, detectedVersion = "2.0.0", apkUrl = "https://x/a.apk"))
        feedTag = "v2.0.0"

        val outcome = checker().check(app, automatic = true)

        assertTrue(outcome is UpdateChecker.Outcome.Unchanged)
        assertEquals(0, sourceCalls)
        assertEquals(TrackedApp.STATUS_UPDATE_AVAILABLE, appDao.getByIdOnce(app.id)!!.lastStatus)
    }

    @Test
    fun `the feed still works while GitHub's API refuses`() = runTest {
        val app = checkedRecently()
        blockGitHub()
        feedTag = "v2.0.0"

        assertTrue(checker().check(app, automatic = true) is UpdateChecker.Outcome.Unchanged)

        feedTag = "v2.1.0"
        assertTrue(checker().check(app, automatic = true) is UpdateChecker.Outcome.RateLimited)
        assertEquals(0, sourceCalls)
    }

    @Test
    fun `other sources don't use the feed`() = runTest {
        val app = insert(sourceType = TrackedApp.SOURCE_TYPE_GITLAB)
        repository.updateApp(app.copy(feedTopTag = "x", lastFullCheckAt = now - 1000))
        feedTag = "x"

        checker().check(appDao.getByIdOnce(app.id)!!, automatic = true)

        assertEquals(1, sourceCalls)
        assertEquals("untouched", "x", appDao.getByIdOnce(app.id)!!.feedTopTag)
    }
}
