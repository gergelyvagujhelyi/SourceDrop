package dev.sourcedrop.app.ui.appform

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
import dev.sourcedrop.app.sourceadapters.AdapterError
import dev.sourcedrop.app.sourceadapters.AppMetadataFetcher
import dev.sourcedrop.app.sourceadapters.GitHubAdapter
import dev.sourcedrop.app.sourceadapters.GitLabAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppFormViewModel(
    private val repository: TrackedAppRepository,
    private val updateEventRepository: UpdateEventRepository,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    private val appId: Long?,
    private val metadataFetcher: AppMetadataFetcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppFormUiState())
    val uiState: StateFlow<AppFormUiState> = _uiState.asStateFlow()

    private var existingApp: TrackedApp? = null
    private var autoFillJob: Job? = null

    init {
        if (appId != null && appId > 0) {
            loadApp(appId)
        }
    }

    private fun loadApp(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val app = repository.getAppByIdOnce(id)
            if (app != null) {
                existingApp = app
                _uiState.update {
                    it.copy(
                        displayName = app.displayName,
                        packageName = app.packageName,
                        sourceType = app.sourceType,
                        sourceUrl = app.sourceUrl,
                        apkUrl = app.apkUrl,
                        versionPattern = app.versionPattern,
                        assetMatchPattern = app.assetMatchPattern,
                        currentVersion = app.currentVersion,
                        checkIntervalHours = app.checkIntervalHours,
                        includePreReleases = app.includePreReleases,
                        isEditing = true,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun updateDisplayName(value: String) {
        _uiState.update { it.copy(displayName = value, errors = it.errors - "displayName") }
    }

    fun updatePackageName(value: String) {
        _uiState.update { it.copy(packageName = value) }
    }

    fun updateSourceType(value: String) {
        _uiState.update { it.copy(sourceType = value) }
        if (_uiState.value.isEditing) {
            autoFillFromUrl(_uiState.value.sourceUrl)
        }
    }

    fun updateSourceUrl(value: String) {
        _uiState.update { it.copy(sourceUrl = value, errors = it.errors - "sourceUrl") }
        if (_uiState.value.isEditing) {
            autoFillFromUrl(value)
        }
    }

    fun updateApkUrl(value: String) {
        _uiState.update { it.copy(apkUrl = value) }
    }

    fun updateVersionPattern(value: String) {
        _uiState.update { it.copy(versionPattern = value) }
    }

    fun updateAssetMatchPattern(value: String) {
        _uiState.update { it.copy(assetMatchPattern = value) }
    }

    fun updateCurrentVersion(value: String) {
        _uiState.update { it.copy(currentVersion = value) }
    }

    fun updateCheckIntervalHours(value: Int) {
        _uiState.update { it.copy(checkIntervalHours = value.coerceIn(1, 168)) }
    }

    fun updateIncludePreReleases(value: Boolean) {
        _uiState.update { it.copy(includePreReleases = value) }
    }

    fun selectVersion(index: Int) {
        val state = _uiState.value
        if (index in state.availableVersions.indices) {
            val ver = state.availableVersions[index]
            _uiState.update {
                if (it.isAppInstalled) {
                    it.copy(selectedVersionIndex = index, apkUrl = ver.apkUrl)
                } else {
                    it.copy(selectedVersionIndex = index, currentVersion = ver.version, apkUrl = ver.apkUrl)
                }
            }
        }
    }

    fun nextStep() {
        val state = _uiState.value
        if (state.isEditing) return

        when (state.step) {
            1 -> {
                val url = state.sourceUrl.trim()
                if (url.isBlank()) {
                    _uiState.update { it.copy(errors = mapOf("sourceUrl" to "URL is required")) }
                    return
                }
                if (!isValidUrl(url)) {
                    _uiState.update { it.copy(errors = mapOf("sourceUrl" to "Invalid URL format")) }
                    return
                }
                // Detect source type from URL
                val sourceType = detectSourceType(url)
                _uiState.update { it.copy(sourceType = sourceType, sourceUrl = url, isFetching = true, fetchError = null, errors = emptyMap()) }
                fetchMetadata(url, sourceType)
            }
        }
    }

    fun previousStep() {
        val state = _uiState.value
        if (state.step > 1) {
            _uiState.update { it.copy(step = state.step - 1, fetchError = null) }
        }
    }

    private fun detectSourceType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("github.com") -> TrackedApp.SOURCE_TYPE_GITHUB
            lower.contains("gitlab.com") || lower.contains("gitlab") -> TrackedApp.SOURCE_TYPE_GITLAB
            lower.endsWith(".apk") -> TrackedApp.SOURCE_TYPE_DIRECT_APK
            lower.endsWith(".json") -> TrackedApp.SOURCE_TYPE_JSON
            else -> TrackedApp.SOURCE_TYPE_HTML
        }
    }

    private fun fetchMetadata(url: String, sourceType: String) {
        viewModelScope.launch {
            try {
                val metadata = when (sourceType) {
                    TrackedApp.SOURCE_TYPE_GITHUB -> {
                        val (owner, repo) = GitHubAdapter.parseOwnerRepo(url)
                        metadataFetcher.fetchFromGitHub(owner, repo)
                    }
                    TrackedApp.SOURCE_TYPE_GITLAB -> {
                        val (host, path) = GitLabAdapter.parseGitLabUrl(url)
                        metadataFetcher.fetchFromGitLab(host, path)
                    }
                    else -> null
                }

                if (metadata != null) {
                    val stableVersions = metadata.versions.filter { !it.isPreRelease }
                    val versions = stableVersions.ifEmpty { metadata.versions }
                    val latestVersion = versions.firstOrNull()
                    val pkgName = metadata.packageName ?: ""
                    val installed = pkgName.isNotBlank() && apkInstaller.isPackageInstalled(pkgName)
                    val installedVersion = if (installed) apkInstaller.getInstalledVersion(pkgName) else null
                    val installedName = if (installed) apkInstaller.getInstalledAppName(pkgName) else null
                    _uiState.update {
                        it.copy(
                            step = 2,
                            isFetching = false,
                            displayName = installedName ?: metadata.displayName ?: "",
                            packageName = pkgName,
                            availableVersions = versions,
                            selectedVersionIndex = 0,
                            currentVersion = installedVersion ?: latestVersion?.version ?: "",
                            apkUrl = latestVersion?.apkUrl ?: "",
                            isAppInstalled = installed
                        )
                    }
                } else {
                    // For non-GitHub/GitLab sources, go to step 2 with no versions
                    _uiState.update { it.copy(step = 2, isFetching = false) }
                }
            } catch (e: AdapterError.RateLimitError) {
                _uiState.update {
                    it.copy(isFetching = false, fetchError = e.message)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isFetching = false, fetchError = "Failed to fetch: ${e.message}")
                }
            }
        }
    }

    private fun autoFillFromUrl(url: String) {
        autoFillJob?.cancel()
        if (url.isBlank()) return

        autoFillJob = viewModelScope.launch {
            delay(600) // debounce while user is typing
            val state = _uiState.value

            try {
                val metadata = when (state.sourceType) {
                    TrackedApp.SOURCE_TYPE_GITHUB -> {
                        val (owner, repo) = GitHubAdapter.parseOwnerRepo(url)
                        metadataFetcher.fetchFromGitHub(owner, repo)
                    }
                    TrackedApp.SOURCE_TYPE_GITLAB -> {
                        val (host, path) = GitLabAdapter.parseGitLabUrl(url)
                        metadataFetcher.fetchFromGitLab(host, path)
                    }
                    else -> null
                } ?: return@launch

                _uiState.update {
                    it.copy(
                        displayName = if (it.displayName.isBlank()) {
                            metadata.displayName ?: it.displayName
                        } else it.displayName,
                        packageName = if (it.packageName.isBlank()) {
                            metadata.packageName ?: it.packageName
                        } else it.packageName
                    )
                }
            } catch (_: Exception) {
                // Autofill is best-effort, silently ignore failures
            }
        }
    }

    fun save() {
        val state = _uiState.value
        val errors = validate(state)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val app = existingApp?.copy(
                displayName = state.displayName.trim(),
                packageName = state.packageName.trim(),
                sourceType = state.sourceType,
                sourceUrl = state.sourceUrl.trim(),
                apkUrl = state.apkUrl.trim(),
                versionPattern = state.versionPattern.trim(),
                assetMatchPattern = state.assetMatchPattern.trim(),
                currentVersion = state.currentVersion.trim(),
                checkIntervalHours = state.checkIntervalHours,
                includePreReleases = state.includePreReleases,
                updatedAt = now
            ) ?: run {
                val selectedVersion = state.availableVersions.getOrNull(state.selectedVersionIndex)
                val latestVersion = state.availableVersions.firstOrNull()?.version ?: state.currentVersion.trim()
                TrackedApp(
                    displayName = state.displayName.trim(),
                    packageName = state.packageName.trim(),
                    sourceType = state.sourceType,
                    sourceUrl = state.sourceUrl.trim(),
                    apkUrl = state.apkUrl.trim(),
                    versionPattern = state.versionPattern.trim(),
                    assetMatchPattern = state.assetMatchPattern.trim(),
                    currentVersion = state.currentVersion.trim(),
                    latestKnownVersion = latestVersion,
                    checkIntervalHours = state.checkIntervalHours,
                    includePreReleases = state.includePreReleases,
                    createdAt = now,
                    updatedAt = now
                )
            }

            if (existingApp != null) {
                repository.updateApp(app)
            } else {
                val appId = repository.insertApp(app)
                // Create an UpdateEvent for the selected version
                val selectedVersion = state.availableVersions.getOrNull(state.selectedVersionIndex)
                if (selectedVersion != null) {
                    updateEventRepository.insertEvent(
                        UpdateEvent(
                            trackedAppId = appId,
                            detectedVersion = selectedVersion.version,
                            releaseNotes = selectedVersion.releaseNotes,
                            apkUrl = selectedVersion.apkUrl,
                            detectedAt = now
                        )
                    )
                }
            }
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    fun saveAndInstall() {
        val state = _uiState.value
        val errors = validate(state)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        val selectedVersion = state.availableVersions.getOrNull(state.selectedVersionIndex)
        val apkUrl = selectedVersion?.apkUrl ?: state.apkUrl.trim()
        if (apkUrl.isBlank()) {
            save()
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0) }

            val now = System.currentTimeMillis()
            val latestVersion = state.availableVersions.firstOrNull()?.version ?: state.currentVersion.trim()
            val app = TrackedApp(
                displayName = state.displayName.trim(),
                packageName = state.packageName.trim(),
                sourceType = state.sourceType,
                sourceUrl = state.sourceUrl.trim(),
                apkUrl = apkUrl,
                versionPattern = state.versionPattern.trim(),
                assetMatchPattern = state.assetMatchPattern.trim(),
                currentVersion = state.currentVersion.trim(),
                latestKnownVersion = latestVersion,
                checkIntervalHours = state.checkIntervalHours,
                includePreReleases = state.includePreReleases,
                createdAt = now,
                updatedAt = now
            )
            val newAppId = repository.insertApp(app)

            var event: UpdateEvent? = null
            if (selectedVersion != null) {
                val ev = UpdateEvent(
                    trackedAppId = newAppId,
                    detectedVersion = selectedVersion.version,
                    releaseNotes = selectedVersion.releaseNotes,
                    apkUrl = apkUrl,
                    detectedAt = now
                )
                val eventId = updateEventRepository.insertEvent(ev)
                event = ev.copy(id = eventId)
            }

            val downloadId = apkDownloader.enqueueDownload(
                apkUrl,
                state.displayName.trim(),
                state.currentVersion.trim()
            )

            if (event != null) {
                event = event.copy(downloadStatus = UpdateEvent.DOWNLOAD_IN_PROGRESS, downloadId = downloadId)
                updateEventRepository.updateEvent(event)
            }

            apkDownloader.observeProgress(downloadId).collect { progress ->
                _uiState.update { it.copy(downloadProgress = progress.progress) }

                when (progress.status) {
                    DownloadStatus.COMPLETE -> {
                        val filePath = apkDownloader.getDownloadedFilePath(downloadId) ?: ""
                        if (event != null) {
                            updateEventRepository.updateEvent(
                                event.copy(
                                    downloadStatus = UpdateEvent.DOWNLOAD_COMPLETE,
                                    localApkPath = filePath
                                )
                            )
                        }
                        _uiState.update { it.copy(isDownloading = false, downloadProgress = 100) }
                        if (filePath.isNotBlank() && state.packageName.isNotBlank()) {
                            apkInstaller.launchInstall(filePath)
                            _uiState.update { it.copy(isInstalling = true, pendingApkPath = filePath) }
                        } else if (filePath.isNotBlank()) {
                            apkInstaller.launchInstall(filePath)
                            _uiState.update { it.copy(isSaved = true) }
                        } else {
                            _uiState.update { it.copy(isSaved = true) }
                        }
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                        if (event != null) {
                            updateEventRepository.updateEvent(
                                event.copy(downloadStatus = UpdateEvent.DOWNLOAD_FAILED)
                            )
                        }
                        _uiState.update { it.copy(isDownloading = false, isSaved = true) }
                    }
                    else -> {}
                }
            }
        }
    }

    fun checkInstallComplete() {
        val state = _uiState.value
        if (!state.isInstalling) return
        if (state.packageName.isNotBlank() && apkInstaller.isPackageInstalled(state.packageName)) {
            _uiState.update { it.copy(isInstalling = false, isSaved = true) }
        }
    }

    fun retryInstall() {
        val state = _uiState.value
        if (!state.isInstalling || state.pendingApkPath.isBlank()) return
        apkInstaller.launchInstall(state.pendingApkPath)
    }

    private fun validate(state: AppFormUiState): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        if (state.displayName.isBlank()) {
            errors["displayName"] = "App name is required"
        }
        if (state.sourceUrl.isBlank()) {
            errors["sourceUrl"] = "Source URL is required"
        } else if (!isValidUrl(state.sourceUrl.trim())) {
            errors["sourceUrl"] = "Invalid URL format"
        }
        if (state.apkUrl.isNotBlank() && !isValidUrl(state.apkUrl.trim())) {
            errors["apkUrl"] = "Invalid URL format"
        }
        return errors
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            uri.scheme in listOf("http", "https") && uri.host != null
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun factory(
            repository: TrackedAppRepository,
            updateEventRepository: UpdateEventRepository,
            apkDownloader: ApkDownloader,
            apkInstaller: ApkInstaller,
            appId: Long?,
            metadataFetcher: AppMetadataFetcher
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppFormViewModel(repository, updateEventRepository, apkDownloader, apkInstaller, appId, metadataFetcher) as T
                }
            }
        }
    }
}
