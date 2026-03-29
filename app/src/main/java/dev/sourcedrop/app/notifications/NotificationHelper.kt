package dev.sourcedrop.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.sourcedrop.app.MainActivity
import dev.sourcedrop.app.R

class NotificationHelper(private val context: Context) {

    fun createChannels() {
        val channel = NotificationChannel(
            CHANNEL_UPDATES,
            context.getString(R.string.notification_channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun showUpdateNotification(
        appId: Long,
        appName: String,
        newVersion: String,
        releaseNotes: String = ""
    ) {
        if (!hasNotificationPermission()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_APP_ID, appId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            appId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.notification_update_title, appName, newVersion)
        val body = if (releaseNotes.isNotBlank()) {
            releaseNotes.take(200)
        } else {
            context.getString(R.string.notification_update_body)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_UPDATES)
            .build()

        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_ID_BASE + appId.toInt(), notification)
    }

    fun cancelUpdateNotification(appId: Long) {
        NotificationManagerCompat.from(context)
            .cancel(NOTIFICATION_ID_BASE + appId.toInt())
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        const val CHANNEL_UPDATES = "update_checks"
        const val GROUP_UPDATES = "dev.sourcedrop.app.UPDATES"
        const val EXTRA_APP_ID = "app_id"
        private const val NOTIFICATION_ID_BASE = 1000
    }
}
