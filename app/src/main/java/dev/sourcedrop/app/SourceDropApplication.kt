package dev.sourcedrop.app

import android.app.Application
import dev.sourcedrop.app.di.AppContainer

class SourceDropApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Create notification channels
        container.notificationHelper.createChannels()

        // Schedule background update checks
        if (container.preferences.backgroundChecksEnabled) {
            container.workScheduler.schedulePeriodicCheck(
                container.preferences.globalCheckIntervalHours
            )
        }
    }
}
