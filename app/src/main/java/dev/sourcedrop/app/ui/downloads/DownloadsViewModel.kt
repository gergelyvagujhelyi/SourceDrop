package dev.sourcedrop.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import dev.sourcedrop.app.data.repository.UpdateEventRepository
import dev.sourcedrop.app.downloader.ApkDownloader
import dev.sourcedrop.app.installer.ApkInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val trackedAppRepository: TrackedAppRepository,
    private val updateEventRepository: UpdateEventRepository,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                updateEventRepository.getAllEvents(),
                trackedAppRepository.getAllApps()
            ) { events, apps ->
                val appMap = apps.associateBy { it.id }
                val items = events.map { event ->
                    DownloadItem(
                        event = event,
                        appName = appMap[event.trackedAppId]?.displayName ?: "Unknown"
                    )
                }
                _uiState.update { it.copy(items = items, isLoading = false) }
            }.collect {}
        }
    }

    fun installApk(event: UpdateEvent) {
        if (event.localApkPath.isBlank()) return
        apkInstaller.launchInstall(event.localApkPath)
        viewModelScope.launch {
            updateEventRepository.updateEvent(
                event.copy(installStatus = UpdateEvent.INSTALL_STARTED)
            )
        }
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

    fun cleanupAll() {
        apkDownloader.cleanupAllApks()
        viewModelScope.launch {
            val items = _uiState.value.items
            items.filter { it.event.localApkPath.isNotBlank() }.forEach { item ->
                updateEventRepository.updateEvent(
                    item.event.copy(
                        downloadStatus = UpdateEvent.DOWNLOAD_NONE,
                        installStatus = UpdateEvent.INSTALL_NONE,
                        downloadId = 0,
                        localApkPath = ""
                    )
                )
            }
        }
    }

    companion object {
        fun factory(
            trackedAppRepository: TrackedAppRepository,
            updateEventRepository: UpdateEventRepository,
            apkDownloader: ApkDownloader,
            apkInstaller: ApkInstaller
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DownloadsViewModel(
                        trackedAppRepository, updateEventRepository,
                        apkDownloader, apkInstaller
                    ) as T
                }
            }
        }
    }
}
