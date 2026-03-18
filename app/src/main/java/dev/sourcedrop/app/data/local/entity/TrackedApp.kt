package dev.sourcedrop.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracked_apps")
data class TrackedApp(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val displayName: String,
    val packageName: String = "",
    val sourceType: String = SOURCE_TYPE_GITHUB,
    val sourceUrl: String,
    val apkUrl: String = "",
    val versionPattern: String = "",
    val assetMatchPattern: String = "",
    val currentVersion: String = "",
    val latestKnownVersion: String = "",
    val lastCheckedAt: Long = 0,
    val lastStatus: String = STATUS_IDLE,
    val checkEnabled: Boolean = true,
    val checkIntervalHours: Int = 12,
    val includePreReleases: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_TYPE_GITHUB = "github"
        const val SOURCE_TYPE_GITLAB = "gitlab"
        const val SOURCE_TYPE_JSON = "json"
        const val SOURCE_TYPE_DIRECT_APK = "direct_apk"
        const val SOURCE_TYPE_HTML = "html"

        const val STATUS_IDLE = "idle"
        const val STATUS_CHECKING = "checking"
        const val STATUS_UP_TO_DATE = "up_to_date"
        const val STATUS_UPDATE_AVAILABLE = "update_available"
        const val STATUS_ERROR = "error"

        val SOURCE_TYPES = listOf(
            SOURCE_TYPE_GITHUB,
            SOURCE_TYPE_GITLAB,
            SOURCE_TYPE_JSON,
            SOURCE_TYPE_DIRECT_APK,
            SOURCE_TYPE_HTML
        )
    }
}
