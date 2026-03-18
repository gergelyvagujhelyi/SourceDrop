package dev.sourcedrop.app.util

import android.content.Context
import android.content.pm.PackageManager

class InstalledVersionDetector(private val context: Context) {

    fun getInstalledVersion(packageName: String): String? {
        if (packageName.isBlank()) return null
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            info.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
