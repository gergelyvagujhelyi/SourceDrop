package dev.sourcedrop.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object AppListRoute

@Serializable
object AddAppRoute

@Serializable
data class AppDetailRoute(val appId: Long)

@Serializable
data class EditAppRoute(val appId: Long)

@Serializable
object DownloadsRoute

@Serializable
object SettingsRoute

@Serializable
object InstalledAppsRoute
