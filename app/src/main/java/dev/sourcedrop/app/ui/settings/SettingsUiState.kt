package dev.sourcedrop.app.ui.settings

data class SettingsUiState(
    val notificationsEnabled: Boolean = true,
    val backgroundChecksEnabled: Boolean = true,
    val checkIntervalHours: Int = 12,
    val hasNotificationPermission: Boolean = true
)
