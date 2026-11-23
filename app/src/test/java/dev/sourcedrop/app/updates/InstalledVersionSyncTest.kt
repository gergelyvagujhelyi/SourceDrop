package dev.sourcedrop.app.updates

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import dev.sourcedrop.app.testutil.FakeTrackedAppDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledVersionSyncTest {

    private val dao = FakeTrackedAppDao()
    private val repository = TrackedAppRepository(dao)
    private val installed = mutableMapOf<String, String>()
    private val caughtUp = mutableListOf<Long>()

    private fun sync(dispatcher: CoroutineDispatcher) = InstalledVersionSync(
        repository = repository,
        installedVersionOf = { installed[it] },
        onCaughtUp = { caughtUp += it },
        clock = { 42L },
        ioDispatcher = dispatcher
    )

    private suspend fun insert(
        current: String,
        latest: String,
        status: String,
        packageName: String = "com.example.app"
    ): TrackedApp {
        val app = TrackedApp(
            displayName = "Example",
            packageName = packageName,
            sourceUrl = "https://github.com/example/app",
            currentVersion = current,
            latestKnownVersion = latest,
            lastStatus = status,
            updatedAt = 1L
        )
        return app.copy(id = dao.insert(app))
    }

    @Test
    fun `updated to the latest outside SourceDrop becomes up to date`() = runTest {
        val app = insert("1.2.0", "1.3.0", TrackedApp.STATUS_UPDATE_AVAILABLE)
        installed["com.example.app"] = "1.3.0"

        val synced = sync(UnconfinedTestDispatcher(testScheduler)).sync(app)

        assertEquals("1.3.0", synced.currentVersion)
        assertEquals(TrackedApp.STATUS_UP_TO_DATE, synced.lastStatus)
        assertEquals(synced, dao.getByIdOnce(app.id))
        assertEquals(listOf(app.id), caughtUp)
    }

    @Test
    fun `newer than the latest known release is up to date`() = runTest {
        val app = insert("0.7.8", "0.7.9", TrackedApp.STATUS_UPDATE_AVAILABLE)
        installed["com.example.app"] = "0.7.10"

        val synced = sync(UnconfinedTestDispatcher(testScheduler)).sync(app)

        assertEquals("0.7.10", synced.currentVersion)
        assertEquals(TrackedApp.STATUS_UP_TO_DATE, synced.lastStatus)
    }

    @Test
    fun `still behind keeps update available`() = runTest {
        val app = insert("1.0.0", "1.3.0", TrackedApp.STATUS_UPDATE_AVAILABLE)
        installed["com.example.app"] = "1.2.0"

        val synced = sync(UnconfinedTestDispatcher(testScheduler)).sync(app)

        assertEquals("1.2.0", synced.currentVersion)
        assertEquals(TrackedApp.STATUS_UPDATE_AVAILABLE, synced.lastStatus)
        assertTrue(caughtUp.isEmpty())
    }

    @Test
    fun `downgraded below the latest becomes update available`() = runTest {
        val app = insert("1.3.0", "1.3.0", TrackedApp.STATUS_UP_TO_DATE)
        installed["com.example.app"] = "1.2.0"

        val synced = sync(UnconfinedTestDispatcher(testScheduler)).sync(app)

        assertEquals(TrackedApp.STATUS_UPDATE_AVAILABLE, synced.lastStatus)
    }

    @Test
    fun `an error status is kept while still behind`() = runTest {
        val app = insert("1.0.0", "1.3.0", TrackedApp.STATUS_ERROR)
        installed["com.example.app"] = "1.1.0"

        val synced = sync(UnconfinedTestDispatcher(testScheduler)).sync(app)

        assertEquals("1.1.0", synced.currentVersion)
        assertEquals(TrackedApp.STATUS_ERROR, synced.lastStatus)
    }

    @Test
    fun `no latest known yet only records the version`() = runTest {
        val app = insert("", "", TrackedApp.STATUS_IDLE)
        installed["com.example.app"] = "2.0"

        val synced = sync(UnconfinedTestDispatcher(testScheduler)).sync(app)

        assertEquals("2.0", synced.currentVersion)
        assertEquals(TrackedApp.STATUS_IDLE, synced.lastStatus)
    }

    @Test
    fun `not installed leaves the record alone`() = runTest {
        val app = insert("1.0.0", "1.3.0", TrackedApp.STATUS_UPDATE_AVAILABLE)

        val synced = sync(UnconfinedTestDispatcher(testScheduler)).sync(app)

        assertEquals(app, synced)
        assertEquals(1L, dao.getByIdOnce(app.id)!!.updatedAt)
    }

    @Test
    fun `same version writes nothing`() = runTest {
        val app = insert("1.3.0", "1.3.0", TrackedApp.STATUS_UP_TO_DATE)
        installed["com.example.app"] = "1.3.0"

        sync(UnconfinedTestDispatcher(testScheduler)).sync(app)

        assertEquals(1L, dao.getByIdOnce(app.id)!!.updatedAt)
        assertTrue(caughtUp.isEmpty())
    }

    @Test
    fun `no package name is skipped`() = runTest {
        val app = insert("1.0.0", "1.3.0", TrackedApp.STATUS_UPDATE_AVAILABLE, packageName = "")
        installed[""] = "9.9"

        assertEquals(app, sync(UnconfinedTestDispatcher(testScheduler)).sync(app))
    }

    @Test
    fun `works from the stored record, not an older copy`() = runTest {
        val stale = insert("1.0.0", "1.0.0", TrackedApp.STATUS_UP_TO_DATE)
        // Meanwhile a check found 1.3.0.
        dao.update(stale.copy(latestKnownVersion = "1.3.0", lastStatus = TrackedApp.STATUS_UPDATE_AVAILABLE))
        installed["com.example.app"] = "1.2.0"

        val synced = sync(UnconfinedTestDispatcher(testScheduler)).sync(stale)

        assertEquals("1.3.0", synced.latestKnownVersion)
        assertEquals(TrackedApp.STATUS_UPDATE_AVAILABLE, synced.lastStatus)
    }

    @Test
    fun `syncAll handles each app`() = runTest {
        val a = insert("1.0", "2.0", TrackedApp.STATUS_UPDATE_AVAILABLE, packageName = "com.example.a")
        val b = insert("1.0", "1.0", TrackedApp.STATUS_UP_TO_DATE, packageName = "com.example.b")
        installed["com.example.a"] = "2.0"
        installed["com.example.b"] = "1.1"

        sync(UnconfinedTestDispatcher(testScheduler)).syncAll(listOf(a, b))

        assertEquals("2.0", dao.getByIdOnce(a.id)!!.currentVersion)
        assertEquals("1.1", dao.getByIdOnce(b.id)!!.currentVersion)
    }
}
