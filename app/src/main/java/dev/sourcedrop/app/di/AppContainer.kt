package dev.sourcedrop.app.di

import android.content.Context
import dev.sourcedrop.app.data.local.SourceDropDatabase
import dev.sourcedrop.app.data.preferences.AppPreferences
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import dev.sourcedrop.app.data.repository.UpdateEventRepository
import dev.sourcedrop.app.downloader.ApkDownloader
import dev.sourcedrop.app.installer.ApkInstaller
import dev.sourcedrop.app.installer.ApkVerifier
import dev.sourcedrop.app.notifications.NotificationHelper
import dev.sourcedrop.app.sourceadapters.AppMetadataFetcher
import dev.sourcedrop.app.sourceadapters.SourceAdapterFactory
import dev.sourcedrop.app.util.InstalledVersionDetector
import dev.sourcedrop.app.worker.WorkScheduler
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {

    private val database: SourceDropDatabase = SourceDropDatabase.getInstance(context)

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val preferences: AppPreferences by lazy {
        AppPreferences(context)
    }

    val notificationHelper: NotificationHelper by lazy {
        NotificationHelper(context)
    }

    val workScheduler: WorkScheduler by lazy {
        WorkScheduler(context)
    }

    val apkDownloader: ApkDownloader by lazy {
        ApkDownloader(context)
    }

    val apkInstaller: ApkInstaller by lazy {
        ApkInstaller(context)
    }

    val apkVerifier: ApkVerifier by lazy {
        ApkVerifier(context)
    }

    val trackedAppRepository: TrackedAppRepository by lazy {
        TrackedAppRepository(database.trackedAppDao())
    }

    val updateEventRepository: UpdateEventRepository by lazy {
        UpdateEventRepository(database.updateEventDao())
    }

    val sourceAdapterFactory: SourceAdapterFactory by lazy {
        SourceAdapterFactory(httpClient, preferences)
    }

    val appMetadataFetcher: AppMetadataFetcher by lazy {
        AppMetadataFetcher(httpClient) { preferences.githubApiToken }
    }

    val installedVersionDetector: InstalledVersionDetector by lazy {
        InstalledVersionDetector(context)
    }
}
