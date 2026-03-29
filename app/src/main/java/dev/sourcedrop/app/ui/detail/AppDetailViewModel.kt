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
import dev.sourcedrop.app.sourceadapters.AdapterError
import dev.sourcedrop.app.sourceadapters.AppMetadataFetcher
import dev.sourcedrop.app.sourceadapters.GitHubAdapter
import dev.sourcedrop.app.sourceadapters.GitLabAdapter
import dev.sourcedrop.app.sourceadapters.ReleaseVersion
import dev.sourcedrop.app.sourceadapters.SourceAdapterFactory
import dev.sourcedrop.app.util.VersionComparator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppDetailViewModel(
    private val appId: Long,
    private val trackedAppRepository: TrackedAppRepository,
    private val updateEventRepository: UpdateEventRepository,
    private val adapterFactory: SourceAdapterFactory,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    private val apkVerifier: ApkVerifier,
    private val installedVersionDetector: dev.sourcedrop.app.util.InstalledVersionDetector,
    private val appMetadataFetcher: AppMetadataFetcher
) : ViewModel() {

    private var installAfterDownload = false
    private val _uiState = MutableStateFlow(AppDetailUiState())
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

    fun downloadRelease(release: ReleaseVersion) {
        if (release.apkUrl.isBlank()) return
        val app = _uiState.value.app ?: return
        if (_uiState.value.downloadingEventId != null) return

        installAfterDownload = true
        viewModelScope.launch {
            // Reuse existing UpdateEvent if this version was already detected, otherwise create one
            val event = updateEventRepository.getEventByVersion(app.id, release.version)
                ?: UpdateEvent(
                    trackedAppId = app.id,
                    detectedVersion = release.version,
                    releaseNotes = release.releaseNotes,
                    apkUrl = release.apkUrl
                ).let {
                    val id = updateEventRepository.insertEvent(it)
                    it.copy(id = id)
                }
            downloadApk(event)
        }
    }

    fun checkNow() {
        var app = _uiState.value.app ?: return
        if (_uiState.value.isChecking) return

        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, checkError = null, checkSuccess = null) }

            // Auto-detect installed version from device if currentVersion is blank
            if (app.currentVersion.isBlank() && app.packageName.isNotBlank()) {
                val detected = installedVersionDetector.getInstalledVersion(app.packageName)
                if (detected != null) {
                    app = app.copy(currentVersion = detected, updatedAt = System.currentTimeMillis())
                    trackedAppRepository.updateApp(app)
                }
            }

            try {
                val adapter = adapterFactory.create(app.sourceType)
                val result = adapter.checkForUpdate(app)

                val now = System.currentTimeMillis()
                val isNewer = app.currentVersion.isBlank() ||
                    VersionComparator.isNewer(app.currentVersion, result.version)

                val status = if (isNewer) {
                    TrackedApp.STATUS_UPDATE_AVAILABLE
                } else {
                    TrackedApp.STATUS_UP_TO_DATE
                }

                trackedAppRepository.updateApp(
                    app.copy(
                        latestKnownVersion = result.version,
                        lastCheckedAt = now,
                        lastStatus = status,
                        updatedAt = now
                    )
                )

                if (isNewer) {
                    val existing = updateEventRepository.getEventByVersion(app.id, result.version)
                    if (existing == null) {
                        updateEventRepository.insertEvent(
                            UpdateEvent(
                                trackedAppId = app.id,
                                detectedVersion = result.version,
                                releaseNotes = result.releaseNotes,
                                apkUrl = result.apkUrl,
                                detectedAt = now
                            )
                        )
                    }
                }

                val message = if (isNewer) {
                    "Update found: ${result.version}"
                } else {
                    "Up to date (${result.version})"
                }
                _uiState.update { it.copy(isChecking = false, checkSuccess = message) }

            } catch (e: AdapterError) {
                val now = System.currentTimeMillis()
                trackedAppRepository.updateApp(
                    app.copy(
                        lastCheckedAt = now,
                        lastStatus = TrackedApp.STATUS_ERROR,
                        updatedAt = now
                    )
                )
                _uiState.update { it.copy(isChecking = false, checkError = e.message) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isChecking = false, checkError = "Unexpected error: ${e.message}")
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
                // Update currentVersion to reflect what was just installed
                val app = _uiState.value.app ?: return@launch
                trackedAppRepository.updateApp(
                    app.copy(
                        currentVersion = event.detectedVersion,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } else {
            _uiState.update {
                it.copy(checkError = "Failed to launch installer. APK file may be missing.")
            }
        }
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
            adapterFactory: SourceAdapterFactory,
            apkDownloader: ApkDownloader,
            apkInstaller: ApkInstaller,
            apkVerifier: ApkVerifier,
            installedVersionDetector: dev.sourcedrop.app.util.InstalledVersionDetector,
            appMetadataFetcher: AppMetadataFetcher
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppDetailViewModel(
                        appId, trackedAppRepository, updateEventRepository,
                        adapterFactory, apkDownloader, apkInstaller, apkVerifier,
                        installedVersionDetector, appMetadataFetcher
                    ) as T
                }
            }
        }
    }
}
