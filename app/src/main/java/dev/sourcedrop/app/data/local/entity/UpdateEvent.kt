package dev.sourcedrop.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "update_events",
    foreignKeys = [
        ForeignKey(
            entity = TrackedApp::class,
            parentColumns = ["id"],
            childColumns = ["trackedAppId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackedAppId")]
)
data class UpdateEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackedAppId: Long,
    val detectedVersion: String,
    val releaseNotes: String = "",
    val apkUrl: String = "",
    val detectedAt: Long = System.currentTimeMillis(),
    val downloadStatus: String = DOWNLOAD_NONE,
    val installStatus: String = INSTALL_NONE,
    val downloadId: Long = 0,
    val localApkPath: String = ""
) {
    companion object {
        const val DOWNLOAD_NONE = "none"
        const val DOWNLOAD_IN_PROGRESS = "in_progress"
        const val DOWNLOAD_COMPLETE = "complete"
        const val DOWNLOAD_FAILED = "failed"

        const val INSTALL_NONE = "none"
        const val INSTALL_STARTED = "started"
        const val INSTALL_COMPLETE = "complete"
        const val INSTALL_FAILED = "failed"
    }
}
