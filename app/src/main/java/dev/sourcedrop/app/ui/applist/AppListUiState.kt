package dev.sourcedrop.app.ui.applist

import dev.sourcedrop.app.data.local.entity.TrackedApp

data class AppListUiState(
    val apps: List<TrackedApp> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val refreshError: String? = null
) {
    val isEmpty: Boolean get() = !isLoading && apps.isEmpty()
}
