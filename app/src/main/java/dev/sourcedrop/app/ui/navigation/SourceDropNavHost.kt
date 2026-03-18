package dev.sourcedrop.app.ui.navigation

import androidx.compose.runtime.Composable
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
import dev.sourcedrop.app.ui.settings.SettingsScreen
import dev.sourcedrop.app.ui.settings.SettingsViewModel

@Composable
fun SourceDropNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AppListRoute
    ) {
        composable<AppListRoute> {
            val viewModel: AppListViewModel = viewModel(
                factory = AppListViewModel.factory(
                    container.trackedAppRepository,
                    container.updateEventRepository,
                    container.sourceAdapterFactory
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
                    container.apkInstaller
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
                factory = AppFormViewModel.factory(container.trackedAppRepository, null)
            )
            AppFormScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<EditAppRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditAppRoute>()
            val viewModel: AppFormViewModel = viewModel(
                factory = AppFormViewModel.factory(container.trackedAppRepository, route.appId)
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
                    container.apkInstaller
                )
            )
            DownloadsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<SettingsRoute> {
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(
                    container.preferences,
                    container.workScheduler,
                    container.notificationHelper
                )
            )
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
