package dev.sourcedrop.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class WorkScheduler(private val context: Context) {

    fun schedulePeriodicCheck(intervalHours: Int) {
        val constrainedInterval = intervalHours.toLong().coerceAtLeast(MIN_INTERVAL_HOURS)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            constrainedInterval, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(constrainedInterval, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UpdateCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    fun cancelPeriodicCheck() {
        WorkManager.getInstance(context)
            .cancelUniqueWork(UpdateCheckWorker.WORK_NAME)
    }

    fun reschedule(intervalHours: Int, enabled: Boolean) {
        if (enabled) {
            schedulePeriodicCheck(intervalHours)
        } else {
            cancelPeriodicCheck()
        }
    }

    companion object {
        // WorkManager minimum periodic interval is 15 minutes.
        // We use 1 hour as a practical minimum for this use case.
        const val MIN_INTERVAL_HOURS = 1L
    }
}
