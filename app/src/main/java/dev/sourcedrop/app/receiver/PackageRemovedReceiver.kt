package dev.sourcedrop.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.sourcedrop.app.SourceDropApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PackageRemovedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return

        val packageName = intent.data?.schemeSpecificPart ?: return

        val app = (context.applicationContext as? SourceDropApplication) ?: return
        val container = app.container

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val trackedApp = container.trackedAppRepository.getAppByPackageName(packageName)
                if (trackedApp != null) {
                    // Clean up downloaded APKs
                    val events = container.updateEventRepository.getEventsForAppOnce(trackedApp.id)
                    for (event in events) {
                        if (event.localApkPath.isNotBlank()) {
                            container.apkDownloader.deleteApk(event.localApkPath)
                        }
                    }
                    container.trackedAppRepository.deleteApp(trackedApp.id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
