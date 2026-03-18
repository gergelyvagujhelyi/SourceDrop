package dev.sourcedrop.app.ui.downloads

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent

data class DownloadItem(
    val event: UpdateEvent,
    val appName: String
)

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
    val isLoading: Boolean = true
) {
    val isEmpty: Boolean get() = !isLoading && items.isEmpty()
}
