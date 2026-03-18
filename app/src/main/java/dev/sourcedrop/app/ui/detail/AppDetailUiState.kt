package dev.sourcedrop.app.ui.detail

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent

data class AppDetailUiState(
    val app: TrackedApp? = null,
    val events: List<UpdateEvent> = emptyList(),
    val isLoading: Boolean = true,
    val isChecking: Boolean = false,
    val checkError: String? = null,
    val checkSuccess: String? = null,
    val downloadingEventId: Long? = null,
    val downloadProgress: Int = 0,
    val canInstallPackages: Boolean = true,
    val showInstallPermissionDialog: Boolean = false
) {
    val hasUpdate: Boolean
        get() = app != null &&
            app.latestKnownVersion.isNotBlank() &&
            app.currentVersion.isNotBlank() &&
            app.lastStatus == TrackedApp.STATUS_UPDATE_AVAILABLE
}
