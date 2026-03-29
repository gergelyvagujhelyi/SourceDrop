package dev.sourcedrop.app.ui.appform

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.sourceadapters.ReleaseVersion

data class AppFormUiState(
    val displayName: String = "",
    val packageName: String = "",
    val sourceType: String = TrackedApp.SOURCE_TYPE_GITHUB,
    val sourceUrl: String = "",
    val apkUrl: String = "",
    val versionPattern: String = "",
    val assetMatchPattern: String = "",
    val currentVersion: String = "",
    val checkIntervalHours: Int = 12,
    val includePreReleases: Boolean = false,
    val isEditing: Boolean = false,
    val isSaved: Boolean = false,
    val isLoading: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    // Wizard state (add mode only)
    val step: Int = 1,
    val isFetching: Boolean = false,
    val fetchError: String? = null,
    val availableVersions: List<ReleaseVersion> = emptyList(),
    val selectedVersionIndex: Int = 0,
    val isAppInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val isInstalling: Boolean = false,
    val pendingApkPath: String = ""
) {
    val sourceTypeLabel: String get() = when (sourceType) {
        TrackedApp.SOURCE_TYPE_GITHUB -> "GitHub Release"
        TrackedApp.SOURCE_TYPE_GITLAB -> "GitLab Release"
        TrackedApp.SOURCE_TYPE_JSON -> "JSON Endpoint"
        TrackedApp.SOURCE_TYPE_DIRECT_APK -> "Direct APK URL"
        TrackedApp.SOURCE_TYPE_HTML -> "HTML Page"
        else -> sourceType
    }
}
