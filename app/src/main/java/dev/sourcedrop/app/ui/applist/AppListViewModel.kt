package dev.sourcedrop.app.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import dev.sourcedrop.app.data.repository.UpdateEventRepository
import dev.sourcedrop.app.sourceadapters.SourceAdapterFactory
import dev.sourcedrop.app.util.VersionComparator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppListViewModel(
    private val repository: TrackedAppRepository,
    private val updateEventRepository: UpdateEventRepository,
    private val adapterFactory: SourceAdapterFactory
) : ViewModel() {

    private val _refreshState = MutableStateFlow(RefreshState())

    val uiState = combine(
        repository.getAllApps(),
        _refreshState
    ) { apps, refresh ->
        AppListUiState(
            apps = apps,
            isLoading = false,
            isRefreshing = refresh.isRefreshing,
            refreshError = refresh.error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppListUiState()
    )

    fun deleteApp(id: Long) {
        viewModelScope.launch {
            repository.deleteApp(id)
        }
    }

    fun refreshAll() {
        if (_refreshState.value.isRefreshing) return

        viewModelScope.launch {
            _refreshState.value = RefreshState(isRefreshing = true)
            val apps = uiState.value.apps.filter { it.checkEnabled }
            var errorCount = 0

            for (app in apps) {
                try {
                    val adapter = adapterFactory.create(app.sourceType)
                    val result = adapter.checkForUpdate(app)
                    val now = System.currentTimeMillis()
                    val isNewer = app.currentVersion.isBlank() ||
                        VersionComparator.isNewer(app.currentVersion, result.version)
                    val status = if (isNewer) TrackedApp.STATUS_UPDATE_AVAILABLE
                        else TrackedApp.STATUS_UP_TO_DATE

                    repository.updateApp(
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
                } catch (e: Exception) {
                    val now = System.currentTimeMillis()
                    repository.updateApp(
                        app.copy(
                            lastCheckedAt = now,
                            lastStatus = TrackedApp.STATUS_ERROR,
                            updatedAt = now
                        )
                    )
                    errorCount++
                }
            }

            _refreshState.value = RefreshState(
                isRefreshing = false,
                error = if (errorCount > 0) "$errorCount app(s) failed to check" else null
            )
        }
    }

    fun dismissError() {
        _refreshState.value = _refreshState.value.copy(error = null)
    }

    private data class RefreshState(
        val isRefreshing: Boolean = false,
        val error: String? = null
    )

    companion object {
        fun factory(
            repository: TrackedAppRepository,
            updateEventRepository: UpdateEventRepository,
            adapterFactory: SourceAdapterFactory
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppListViewModel(repository, updateEventRepository, adapterFactory) as T
                }
            }
        }
    }
}
