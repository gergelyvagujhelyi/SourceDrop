package dev.sourcedrop.app.downloader

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class ApkDownloader(private val context: Context) {

    private val downloadManager: DownloadManager =
        context.getSystemService(DownloadManager::class.java)

    /**
     * Starts an APK download and returns the DownloadManager download ID.
     * The APK is saved to app-private external storage.
     */
    fun enqueueDownload(url: String, appName: String, version: String): Long {
        val fileName = sanitizeFileName("${appName}_${version}.apk")

        // Ensure the apks directory exists
        val apkDir = File(context.getExternalFilesDir(null), "apks")
        if (!apkDir.exists()) apkDir.mkdirs()

        // Delete any existing file with the same name
        val targetFile = File(apkDir, fileName)
        if (targetFile.exists()) targetFile.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("$appName $version")
            .setDescription("Downloading update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "apks/$fileName")
            .setMimeType("application/vnd.android.package-archive")

        return downloadManager.enqueue(request)
    }

    /**
     * Emits download progress updates as a Flow.
     * Completes when the download finishes or fails.
     */
    fun observeProgress(downloadId: Long): Flow<DownloadProgress> = flow {
        var isRunning = true
        while (isRunning) {
            val progress = queryProgress(downloadId)
            emit(progress)

            when (progress.status) {
                DownloadStatus.COMPLETE,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED -> isRunning = false
                else -> delay(500)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Queries the current download progress.
     */
    fun queryProgress(downloadId: Long): DownloadProgress {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor? = downloadManager.query(query)

        cursor?.use {
            if (!it.moveToFirst()) {
                return DownloadProgress(status = DownloadStatus.FAILED, reason = "Download not found")
            }

            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val totalIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val downloadedIndex = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
            val localUriIndex = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

            val statusCode = it.getInt(statusIndex)
            val totalBytes = it.getLong(totalIndex)
            val downloadedBytes = it.getLong(downloadedIndex)
            val reasonCode = it.getInt(reasonIndex)
            val localUri = it.getString(localUriIndex)

            val progress = if (totalBytes > 0) {
                (downloadedBytes * 100 / totalBytes).toInt()
            } else {
                0
            }

            val status = when (statusCode) {
                DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETE
                DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                else -> DownloadStatus.FAILED
            }

            return DownloadProgress(
                status = status,
                progress = progress,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                localUri = localUri,
                reason = if (status == DownloadStatus.FAILED) "Error code: $reasonCode" else null
            )
        }

        return DownloadProgress(status = DownloadStatus.FAILED, reason = "Could not query download")
    }

    /**
     * Returns the local file path for a completed download.
     */
    fun getDownloadedFilePath(downloadId: Long): String? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val uri = cursor.getString(uriIndex)
                    return Uri.parse(uri).path
                }
            }
        }
        return null
    }

    fun cancelDownload(downloadId: Long) {
        downloadManager.remove(downloadId)
    }

    /**
     * Deletes a downloaded APK file.
     */
    fun deleteApk(filePath: String): Boolean {
        val file = File(filePath)
        return if (file.exists()) file.delete() else true
    }

    /**
     * Deletes all APK files in the download directory.
     */
    fun cleanupAllApks() {
        val apkDir = File(context.getExternalFilesDir(null), "apks")
        apkDir.listFiles()?.forEach { it.delete() }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}

enum class DownloadStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETE,
    FAILED,
    CANCELLED
}

data class DownloadProgress(
    val status: DownloadStatus,
    val progress: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val localUri: String? = null,
    val reason: String? = null
)
