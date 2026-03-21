package dev.sourcedrop.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.sourcedrop.app.di.AppContainer
import dev.sourcedrop.app.ui.appform.AppFormScreen
import dev.sourcedrop.app.ui.appform.AppFormViewModel
import dev.sourcedrop.app.ui.applist.AppListScreen
import dev.sourcedrop.app.ui.applist.AppListViewModel
import dev.sourcedrop.app.ui.detail.AppDetailScreen
import dev.sourcedrop.app.ui.detail.AppDetailViewModel
import dev.sourcedrop.app.ui.downloads.DownloadsScreen
import dev.sourcedrop.app.ui.downloads.DownloadsViewModel
import dev.sourcedrop.app.ui.installedapps.InstalledAppsScreen
import dev.sourcedrop.app.ui.settings.SettingsScreen
import dev.sourcedrop.app.ui.settings.SettingsViewModel

@Composable
fun SourceDropNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AppListRoute,
        enterTransition = { fadeIn(animationSpec = tween(350)) },
        exitTransition = { fadeOut(animationSpec = tween(350)) },
        popEnterTransition = { fadeIn(animationSpec = tween(350)) },
        popExitTransition = { fadeOut(animationSpec = tween(350)) }
    ) {
        composable<AppListRoute> {
            val context = LocalContext.current
            val selfPackageName = context.packageName
            val selfVersion = try {
                context.packageManager.getPackageInfo(selfPackageName, 0).versionName ?: ""
            } catch (_: Exception) { "" }
            val viewModel: AppListViewModel = viewModel(
                factory = AppListViewModel.factory(
                    container.trackedAppRepository,
                    container.updateEventRepository,
                    container.sourceAdapterFactory,
                    container.apkDownloader,
                    container.apkInstaller,
                    selfPackageName,
                    selfVersion
                )
            )
            AppListScreen(
                viewModel = viewModel,
                onAddApp = { navController.navigate(AddAppRoute) },
                onAppClick = { appId -> navController.navigate(AppDetailRoute(appId)) },
                onDownloads = { navController.navigate(DownloadsRoute) },
                onSettings = { navController.navigate(SettingsRoute) }
            )
        }

        composable<AppDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDetailRoute>()
            val viewModel: AppDetailViewModel = viewModel(
                factory = AppDetailViewModel.factory(
                    route.appId,
                    container.trackedAppRepository,
                    container.updateEventRepository,
                    container.sourceAdapterFactory,
                    container.apkDownloader,
                    container.apkInstaller,
                    container.apkVerifier,
                    container.installedVersionDetector
                )
            )
            AppDetailScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onEdit = { appId -> navController.navigate(EditAppRoute(appId)) }
            )
        }

        composable<AddAppRoute> {
            val viewModel: AppFormViewModel = viewModel(
                factory = AppFormViewModel.factory(container.trackedAppRepository, container.updateEventRepository, container.apkDownloader, container.apkInstaller, null, container.appMetadataFetcher)
            )
            AppFormScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<EditAppRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditAppRoute>()
            val viewModel: AppFormViewModel = viewModel(
                factory = AppFormViewModel.factory(container.trackedAppRepository, container.updateEventRepository, container.apkDownloader, container.apkInstaller, route.appId, container.appMetadataFetcher)
            )
            AppFormScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<DownloadsRoute> {
            val viewModel: DownloadsViewModel = viewModel(
                factory = DownloadsViewModel.factory(
                    container.trackedAppRepository,
                    container.updateEventRepository,
                    container.apkDownloader,
                    container.apkInstaller,
                    container.apkVerifier
                )
            )
            DownloadsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<SettingsRoute>(
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(350)) },
            popEnterTransition = { fadeIn(animationSpec = tween(350)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) {
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(
                    container.preferences,
                    container.workScheduler,
                    container.notificationHelper
                )
            )
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onInstalledApps = { navController.navigate(InstalledAppsRoute) }
            )
        }

        composable<InstalledAppsRoute> {
            InstalledAppsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
