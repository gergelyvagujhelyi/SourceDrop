package dev.sourcedrop.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import dev.sourcedrop.app.data.repository.UpdateEventRepository
import dev.sourcedrop.app.downloader.ApkDownloader
import dev.sourcedrop.app.downloader.DownloadStatus
import dev.sourcedrop.app.installer.ApkInstaller
import dev.sourcedrop.app.installer.ApkVerifier
import dev.sourcedrop.app.data.local.entity.TrackedApp.Companion.SOURCE_TYPE_GITHUB
import dev.sourcedrop.app.data.local.entity.TrackedApp.Companion.SOURCE_TYPE_GITLAB
import dev.sourcedrop.app.notifications.NotificationHelper
import dev.sourcedrop.app.sourceadapters.AdapterError
import dev.sourcedrop.app.sourceadapters.AppMetadataFetcher
import dev.sourcedrop.app.sourceadapters.GitHubAdapter
import dev.sourcedrop.app.sourceadapters.GitLabAdapter
import dev.sourcedrop.app.sourceadapters.ReleaseVersion
import dev.sourcedrop.app.updates.InstalledVersionSync
import dev.sourcedrop.app.updates.UpdateChecker
import dev.sourcedrop.app.util.formatTime
import dev.sourcedrop.app.util.VersionComparator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppDetailViewModel(
    private val appId: Long,
    private val trackedAppRepository: TrackedAppRepository,
    private val updateEventRepository: UpdateEventRepository,
    private val updateChecker: UpdateChecker,
    private val versionSync: InstalledVersionSync,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    private val apkVerifier: ApkVerifier,
    private val appMetadataFetcher: AppMetadataFetcher,
    private val notificationHelper: NotificationHelper,
    autoCheck: Boolean = false,
    /** The version the app that opened this page said is out (a hint, see OpenRequest). */
    private val announcedVersion: String? = null
) : ViewModel() {

    private var installAfterDownload = false
    private val _uiState = MutableStateFlow(AppDetailUiState(announcedVersion = announcedVersion))
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                trackedAppRepository.getAppById(appId),
                updateEventRepository.getEventsForApp(appId)
            ) { app, events ->
                _uiState.update {
                    it.copy(
                        app = app,
                        events = events,
                        isLoading = false,
                        canInstallPackages = apkInstaller.canInstallPackages()
                    )
                }
            }.collect {}
        }

        if (autoCheck) {
            viewModelScope.launch {
                _uiState.first { it.app != null }
                checkNow()
            }
        }
    }

    fun loadAllReleases() {
        val app = _uiState.value.app ?: return
        if (_uiState.value.isLoadingReleases) return
        if (_uiState.value.allReleases.isNotEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingReleases = true, releasesError = null) }
            try {
                val metadata = when (app.sourceType) {
                    SOURCE_TYPE_GITHUB -> {
                        val (owner, repo) = GitHubAdapter.parseOwnerRepo(app.sourceUrl)
                        appMetadataFetcher.fetchFromGitHub(owner, repo)
                    }
                    SOURCE_TYPE_GITLAB -> {
                        val (host, path) = GitLabAdapter.parseGitLabUrl(app.sourceUrl)
                        appMetadataFetcher.fetchFromGitLab(host, path)
                    }
                    else -> null
                }
                _uiState.update {
                    it.copy(
                        allReleases = metadata?.versions ?: emptyList(),
                        isLoadingReleases = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoadingReleases = false, releasesError = e.message)
                }
            }
        }
    }

    fun installLatestUpdate() {
        val event = _uiState.value.events.firstOrNull() ?: return
        if (event.apkUrl.isBlank()) return

        if (event.downloadStatus == UpdateEvent.DOWNLOAD_COMPLETE && event.localApkPath.isNotBlank()) {
            installApk(event)
        } else {
            installAfterDownload = true
            downloadApk(event)
        }
    }

    fun downloadRelease(release: ReleaseVersion) {
        if (release.apkUrl.isBlank()) return
        val app = _uiState.value.app ?: return
        if (_uiState.value.downloadingEventId != null) return

        installAfterDownload = true
        viewModelScope.launch {
            // Reuse existing UpdateEvent if this version was already detected, otherwise create one
            var event = updateEventRepository.getEventByVersion(app.id, release.version)
            if (event != null && event.apkUrl.isBlank()) {
                // Existing event has no APK URL — update it from the release
                event = event.copy(apkUrl = release.apkUrl)
                updateEventRepository.updateEvent(event)
            }
            if (event == null) {
                event = UpdateEvent(
                    trackedAppId = app.id,
                    detectedVersion = release.version,
                    releaseNotes = release.releaseNotes,
                    apkUrl = release.apkUrl
                ).let {
                    val id = updateEventRepository.insertEvent(it)
                    it.copy(id = id)
                }
            }
            downloadApk(event)
        }
    }

    fun checkNow() {
        val app = _uiState.value.app ?: return
        if (_uiState.value.isChecking) return

        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, checkError = null, checkSuccess = null) }

            when (val outcome = updateChecker.check(app, automatic = false)) {
                is UpdateChecker.Outcome.UpdateFound,
                is UpdateChecker.Outcome.UpToDate -> {
                    val found = (outcome as? UpdateChecker.Outcome.UpdateFound)?.result
                        ?: (outcome as UpdateChecker.Outcome.UpToDate).result
                    val announced = announcedVersion
                    val message = when {
                        announced != null && VersionComparator.isNewer(found.version, announced) ->
                            "${app.displayName} $announced was announced, but the source still " +
                                "lists ${found.version}. Try again in a few minutes."
                        outcome is UpdateChecker.Outcome.UpdateFound -> "Update found: ${found.version}"
                        else -> "Up to date (${found.version})"
                    }
                    _uiState.update { it.copy(isChecking = false, checkSuccess = message) }
                }
                is UpdateChecker.Outcome.RateLimited -> {
                    val message = "GitHub rate limit reached" +
                        (outcome.until?.let { ". Try again after ${formatTime(it)}" } ?: "")
                    _uiState.update { it.copy(isChecking = false, checkError = message) }
                }
                is UpdateChecker.Outcome.Failed -> {
                    val error = outcome.error
                    val message = if (error is AdapterError) error.message
                        else "Unexpected error: ${error.message}"
                    _uiState.update { it.copy(isChecking = false, checkError = message) }
                }
            }
        }
    }

    fun downloadApk(event: UpdateEvent) {
        if (event.apkUrl.isBlank()) return
        if (_uiState.value.downloadingEventId != null) return

        val appName = _uiState.value.app?.displayName ?: "app"

        viewModelScope.launch {
            _uiState.update { it.copy(downloadingEventId = event.id, downloadProgress = 0) }

            val downloadId = apkDownloader.enqueueDownload(
                url = event.apkUrl,
                appName = appName,
                version = event.detectedVersion
            )

            updateEventRepository.updateEvent(
                event.copy(
                    downloadStatus = UpdateEvent.DOWNLOAD_IN_PROGRESS,
                    downloadId = downloadId
                )
            )

            // Observe download progress
            apkDownloader.observeProgress(downloadId).collect { progress ->
                _uiState.update { it.copy(downloadProgress = progress.progress) }

                when (progress.status) {
                    DownloadStatus.COMPLETE -> {
                        val filePath = apkDownloader.getDownloadedFilePath(downloadId) ?: ""
                        val updatedEvent = event.copy(
                            downloadStatus = UpdateEvent.DOWNLOAD_COMPLETE,
                            downloadId = downloadId,
                            localApkPath = filePath
                        )
                        updateEventRepository.updateEvent(updatedEvent)
                        _uiState.update {
                            it.copy(
                                downloadingEventId = null,
                                downloadProgress = 100,
                                checkSuccess = "Download complete"
                            )
                        }
                        if (installAfterDownload) {
                            installAfterDownload = false
                            installApk(updatedEvent)
                        }
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                        updateEventRepository.updateEvent(
                            event.copy(
                                downloadStatus = UpdateEvent.DOWNLOAD_FAILED,
                                downloadId = downloadId
                            )
                        )
                        _uiState.update {
                            it.copy(
                                downloadingEventId = null,
                                downloadProgress = 0,
                                checkError = "Download failed: ${progress.reason ?: "Unknown error"}"
                            )
                        }
                    }
                    else -> { /* still in progress */ }
                }
            }
        }
    }

    fun installApk(event: UpdateEvent) {
        if (event.localApkPath.isBlank()) return

        if (!apkInstaller.canInstallPackages()) {
            _uiState.update { it.copy(showInstallPermissionDialog = true) }
            return
        }

        // Verify APK signature matches the installed version
        val app = _uiState.value.app
        if (app != null && app.packageName.isNotBlank()) {
            when (val result = apkVerifier.verify(event.localApkPath, app.packageName)) {
                is ApkVerifier.Result.SignatureMismatch -> {
                    _uiState.update { it.copy(checkError = "Security: ${result.message}") }
                    return
                }
                is ApkVerifier.Result.Error -> {
                    _uiState.update { it.copy(checkError = "Verification failed: ${result.message}") }
                    return
                }
                is ApkVerifier.Result.Success,
                is ApkVerifier.Result.NotInstalled -> { /* proceed */ }
            }
        }

        val success = apkInstaller.launchInstall(event.localApkPath)
        if (success) {
            viewModelScope.launch {
                updateEventRepository.updateEvent(
                    event.copy(installStatus = UpdateEvent.INSTALL_STARTED)
                )
            }
        } else {
            // Reset download status so the user can re-download
            viewModelScope.launch {
                apkDownloader.deleteApk(event.localApkPath)
                updateEventRepository.updateEvent(
                    event.copy(
                        downloadStatus = UpdateEvent.DOWNLOAD_NONE,
                        localApkPath = ""
                    )
                )
            }
            _uiState.update {
                it.copy(checkError = "Failed to install. The downloaded file may be corrupt or not a valid APK.")
            }
        }
    }

    /** Back on this page (e.g. from the system installer): match the installed version. */
    fun verifyInstallation() {
        val app = _uiState.value.app ?: return
        viewModelScope.launch { versionSync.sync(app) }
    }

    fun getInstallPermissionIntent() = apkInstaller.createInstallPermissionIntent()

    fun dismissInstallPermissionDialog() {
        _uiState.update {
            it.copy(
                showInstallPermissionDialog = false,
                canInstallPackages = apkInstaller.canInstallPackages()
            )
        }
    }

    fun refreshInstallPermission() {
        _uiState.update { it.copy(canInstallPackages = apkInstaller.canInstallPackages()) }
    }

    fun deleteApk(event: UpdateEvent) {
        if (event.localApkPath.isNotBlank()) {
            apkDownloader.deleteApk(event.localApkPath)
        }
        viewModelScope.launch {
            updateEventRepository.updateEvent(
                event.copy(
                    downloadStatus = UpdateEvent.DOWNLOAD_NONE,
                    installStatus = UpdateEvent.INSTALL_NONE,
                    downloadId = 0,
                    localApkPath = ""
                )
            )
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(checkError = null, checkSuccess = null) }
    }

    companion object {
        fun factory(
            appId: Long,
            trackedAppRepository: TrackedAppRepository,
            updateEventRepository: UpdateEventRepository,
            updateChecker: UpdateChecker,
            versionSync: InstalledVersionSync,
            apkDownloader: ApkDownloader,
            apkInstaller: ApkInstaller,
            apkVerifier: ApkVerifier,
            appMetadataFetcher: AppMetadataFetcher,
            notificationHelper: NotificationHelper,
            autoCheck: Boolean = false,
            announcedVersion: String? = null
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppDetailViewModel(
                        appId, trackedAppRepository, updateEventRepository,
                        updateChecker, versionSync, apkDownloader, apkInstaller, apkVerifier,
                        appMetadataFetcher, notificationHelper,
                        autoCheck, announcedVersion
                    ) as T
                }
            }
        }
    }
}
