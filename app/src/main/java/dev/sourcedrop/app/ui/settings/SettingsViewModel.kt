package dev.sourcedrop.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.sourcedrop.app.data.preferences.AppPreferences
import dev.sourcedrop.app.notifications.NotificationHelper
import dev.sourcedrop.app.worker.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsViewModel(
    private val preferences: AppPreferences,
    private val workScheduler: WorkScheduler,
    private val notificationHelper: NotificationHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun loadState(): SettingsUiState {
        return SettingsUiState(
            notificationsEnabled = preferences.notificationsEnabled,
            backgroundChecksEnabled = preferences.backgroundChecksEnabled,
            checkIntervalHours = preferences.globalCheckIntervalHours,
            hasNotificationPermission = notificationHelper.hasNotificationPermission()
        )
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        preferences.notificationsEnabled = enabled
        _uiState.update { it.copy(notificationsEnabled = enabled) }
    }

    fun setBackgroundChecksEnabled(enabled: Boolean) {
        preferences.backgroundChecksEnabled = enabled
        workScheduler.reschedule(preferences.globalCheckIntervalHours, enabled)
        _uiState.update { it.copy(backgroundChecksEnabled = enabled) }
    }

    fun setCheckIntervalHours(hours: Int) {
        val clamped = hours.coerceIn(1, 168)
        preferences.globalCheckIntervalHours = clamped
        if (preferences.backgroundChecksEnabled) {
            workScheduler.schedulePeriodicCheck(clamped)
        }
        _uiState.update { it.copy(checkIntervalHours = clamped) }
    }

    fun refreshPermissionState() {
        _uiState.update {
            it.copy(hasNotificationPermission = notificationHelper.hasNotificationPermission())
        }
    }

    companion object {
        fun factory(
            preferences: AppPreferences,
            workScheduler: WorkScheduler,
            notificationHelper: NotificationHelper
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(preferences, workScheduler, notificationHelper) as T
                }
            }
        }
    }
}
