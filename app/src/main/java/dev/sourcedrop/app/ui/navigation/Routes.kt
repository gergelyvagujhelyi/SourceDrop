package dev.sourcedrop.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object OnboardingRoute

@Serializable
object AppListRoute

@Serializable
object AddAppRoute

/**
 * @param autoCheck check the app's source as soon as the page opens
 * @param announcedVersion the version the opening app said is out, if any (a hint only)
 */
@Serializable
data class AppDetailRoute(
    val appId: Long,
    val autoCheck: Boolean = false,
    val announcedVersion: String? = null
)

@Serializable
data class EditAppRoute(val appId: Long)

@Serializable
object DownloadsRoute

@Serializable
object SettingsRoute

@Serializable
object InstalledAppsRoute
