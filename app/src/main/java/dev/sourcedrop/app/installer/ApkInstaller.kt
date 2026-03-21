package dev.sourcedrop.app.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

class ApkInstaller(private val context: Context) {

    /**
     * Checks whether this app is allowed to install unknown apps.
     * On Android 8.0+ (API 26+), this is a per-app permission.
     */
    fun canInstallPackages(): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * Returns an Intent to open the system settings page where the user
     * can grant the "Install unknown apps" permission for this app.
     */
    fun createInstallPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates an Intent to install an APK file using the system package installer.
     *
     * Code path:
     * 1. Takes the local file path of the downloaded APK
     * 2. Converts it to a content:// URI via FileProvider
     * 3. Creates an ACTION_VIEW intent with the APK MIME type
     * 4. Grants temporary read permission to the system installer
     *
     * The user will see the standard Android install confirmation dialog.
     * This does NOT perform silent installation.
     */
    fun createInstallIntent(apkFilePath: String): Intent {
        val file = File(apkFilePath)
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Launches the APK install flow.
     * Returns true if the intent was launched successfully.
     *
     * Full flow:
     * 1. Verify the APK file exists at [apkFilePath]
     * 2. Generate content:// URI via FileProvider
     * 3. Launch ACTION_VIEW with APK MIME type
     * 4. Android system shows the package installer dialog
     * 5. User approves or denies the installation
     */
    fun isPackageInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getInstalledVersion(packageName: String): String? {
        if (packageName.isBlank()) return null
        return try {
            context.packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            null
        }
    }

    fun launchUninstall(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun launchInstall(apkFilePath: String): Boolean {
        val file = File(apkFilePath)
        if (!file.exists()) return false

        return try {
            val intent = createInstallIntent(apkFilePath)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
