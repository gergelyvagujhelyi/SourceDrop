package dev.sourcedrop.app.ui.applist

import dev.sourcedrop.app.data.local.dao.TrackedAppDao
import dev.sourcedrop.app.data.local.dao.UpdateEventDao
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import dev.sourcedrop.app.data.repository.UpdateEventRepository
import dev.sourcedrop.app.downloader.ApkDownloader
import dev.sourcedrop.app.installer.ApkInstaller
import dev.sourcedrop.app.sourceadapters.AdapterResult
import dev.sourcedrop.app.sourceadapters.SourceAdapter
import dev.sourcedrop.app.sourceadapters.SourceAdapterFactory
import dev.sourcedrop.app.testutil.FakeTrackedAppDao
import dev.sourcedrop.app.testutil.FakeUpdateEventDao
import dev.sourcedrop.app.testutil.InMemoryRateLimitStore
import dev.sourcedrop.app.updates.GitHubRateLimit
import dev.sourcedrop.app.updates.InstalledVersionSync
import dev.sourcedrop.app.updates.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class AppListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeAppDao: FakeTrackedAppDao
    private lateinit var fakeEventDao: FakeUpdateEventDao
    private lateinit var appRepository: TrackedAppRepository
    private lateinit var eventRepository: UpdateEventRepository
    private lateinit var mockInstaller: ApkInstaller
    private lateinit var mockDownloader: ApkDownloader
    private lateinit var mockAdapterFactory: SourceAdapterFactory

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeAppDao = FakeTrackedAppDao()
        fakeEventDao = FakeUpdateEventDao()
        appRepository = TrackedAppRepository(fakeAppDao)
        eventRepository = UpdateEventRepository(fakeEventDao)
        mockInstaller = mock()
        mockDownloader = mock()
        mockAdapterFactory = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val versionSync by lazy { InstalledVersionSync(appRepository, installedVersionOf = { null }) }

    private fun createViewModel(
        selfPackageName: String = "dev.sourcedrop.app",
        selfVersion: String = "1.0.0"
    ): AppListViewModel {
        return AppListViewModel(
            repository = appRepository,
            updateEventRepository = eventRepository,
            updateChecker = UpdateChecker(
                appRepository, eventRepository, mockAdapterFactory, versionSync,
                GitHubRateLimit(InMemoryRateLimitStore(), { "" })
            ),
            versionSync = versionSync,
            apkDownloader = mockDownloader,
            apkInstaller = mockInstaller,
            selfPackageName = selfPackageName,
            selfVersion = selfVersion
        )
    }

    @Test
    fun `init inserts SourceDrop as tracked app`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val apps = fakeAppDao.getAll().first()
        assertEquals(1, apps.size)
        assertEquals("SourceDrop", apps[0].displayName)
        assertEquals("dev.sourcedrop.app", apps[0].packageName)
        assertEquals(TrackedApp.SOURCE_TYPE_GITHUB, apps[0].sourceType)
    }

    @Test
    fun `init creates UpdateEvent for SourceDrop`() = runTest {
        val vm = createViewModel(selfVersion = "1.0.0")
        advanceUntilIdle()

        val events = fakeEventDao.getAllEvents().first()
        assertEquals(1, events.size)
        assertEquals("1.0.0", events[0].detectedVersion)
    }

    @Test
    fun `init does not create UpdateEvent when version is blank`() = runTest {
        val vm = createViewModel(selfVersion = "")
        advanceUntilIdle()

        val events = fakeEventDao.getAllEvents().first()
        assertEquals(0, events.size)
    }

    @Test
    fun `init does not duplicate SourceDrop on second launch`() = runTest {
        val vm1 = createViewModel()
        advanceUntilIdle()

        val vm2 = createViewModel()
        advanceUntilIdle()

        val apps = fakeAppDao.getAll().first()
        assertEquals(1, apps.size)
    }

    @Test
    fun `search filters apps by display name`() = runTest {
        fakeAppDao.insert(TrackedApp(displayName = "Firefox", sourceUrl = "https://example.com"))
        fakeAppDao.insert(TrackedApp(displayName = "Chrome", sourceUrl = "https://example.com"))

        val vm = createViewModel()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.updateSearchQuery("fire")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.apps.count { it.displayName == "Firefox" })
        assertFalse(state.apps.any { it.displayName == "Chrome" })
        collector.cancel()
    }

    @Test
    fun `search filters apps by package name`() = runTest {
        fakeAppDao.insert(TrackedApp(displayName = "MyApp", packageName = "com.example.myapp", sourceUrl = "https://example.com"))

        val vm = createViewModel()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.updateSearchQuery("com.example")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.apps.count { it.displayName == "MyApp" })
        collector.cancel()
    }

    @Test
    fun `empty search shows all apps`() = runTest {
        fakeAppDao.insert(TrackedApp(displayName = "App1", sourceUrl = "https://example.com"))
        fakeAppDao.insert(TrackedApp(displayName = "App2", sourceUrl = "https://example.com"))

        val vm = createViewModel()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.updateSearchQuery("")
        advanceUntilIdle()

        val state = vm.uiState.value
        // 2 manually inserted + 1 SourceDrop from init
        assertEquals(3, state.apps.size)
        collector.cancel()
    }

    @Test
    fun `deleteApp removes app from repository`() = runTest {
        val id = fakeAppDao.insert(TrackedApp(displayName = "ToDelete", sourceUrl = "https://example.com"))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteApp(id)
        advanceUntilIdle()

        val apps = fakeAppDao.getAll().first()
        assertFalse(apps.any { it.id == id })
    }

    @Test
    fun `deleteApp cleans up downloaded APKs`() = runTest {
        val id = fakeAppDao.insert(TrackedApp(displayName = "WithApk", sourceUrl = "https://example.com"))
        fakeEventDao.insert(UpdateEvent(trackedAppId = id, detectedVersion = "1.0", localApkPath = "/path/to/apk"))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteApp(id)
        advanceUntilIdle()

        verify(mockDownloader).deleteApk("/path/to/apk")
    }

    @Test
    fun `refreshAll updates app with new version`() = runTest {
        val id = fakeAppDao.insert(
            TrackedApp(
                displayName = "TestApp",
                sourceUrl = "https://github.com/test/repo",
                sourceType = TrackedApp.SOURCE_TYPE_GITHUB,
                currentVersion = "1.0.0"
            )
        )

        val mockAdapter: SourceAdapter = mock()
        whenever(mockAdapter.checkForUpdate(any())).thenReturn(
            AdapterResult(version = "2.0.0", apkUrl = "https://example.com/app.apk", releaseNotes = "New release")
        )
        whenever(mockAdapterFactory.create(TrackedApp.SOURCE_TYPE_GITHUB)).thenReturn(mockAdapter)

        val vm = createViewModel()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.refreshAll()
        advanceUntilIdle()

        val app = fakeAppDao.getByIdOnce(id)!!
        assertEquals("2.0.0", app.latestKnownVersion)
        assertEquals(TrackedApp.STATUS_UPDATE_AVAILABLE, app.lastStatus)

        val events = fakeEventDao.getEventsForAppOnce(id)
        assertTrue(events.any { it.detectedVersion == "2.0.0" })
        collector.cancel()
    }

    @Test
    fun `refreshAll sets error status on failure`() = runTest {
        val id = fakeAppDao.insert(
            TrackedApp(
                displayName = "FailApp",
                sourceUrl = "https://github.com/test/fail",
                sourceType = TrackedApp.SOURCE_TYPE_GITHUB,
                currentVersion = "1.0.0"
            )
        )

        val mockAdapter: SourceAdapter = mock()
        whenever(mockAdapter.checkForUpdate(any())).thenThrow(RuntimeException("Network error"))
        whenever(mockAdapterFactory.create(TrackedApp.SOURCE_TYPE_GITHUB)).thenReturn(mockAdapter)

        val vm = createViewModel()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.refreshAll()
        advanceUntilIdle()

        val app = fakeAppDao.getByIdOnce(id)!!
        assertEquals(TrackedApp.STATUS_ERROR, app.lastStatus)

        val state = vm.uiState.value
        assertFalse(state.isRefreshing)
        collector.cancel()
    }

    @Test
    fun `dismissError clears refresh error`() = runTest {
        val mockAdapter: SourceAdapter = mock()
        whenever(mockAdapter.checkForUpdate(any())).thenThrow(RuntimeException("fail"))
        whenever(mockAdapterFactory.create(any())).thenReturn(mockAdapter)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshAll()
        advanceUntilIdle()

        vm.dismissError()
        advanceUntilIdle()

        assertNull(vm.uiState.value.refreshError)
    }
}
