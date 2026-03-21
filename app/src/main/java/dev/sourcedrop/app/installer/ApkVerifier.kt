package dev.sourcedrop.app.installer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

class ApkVerifier(private val context: Context) {

    sealed class Result {
        data object Success : Result()
        data object NotInstalled : Result()
        data class SignatureMismatch(val message: String) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Verifies that a downloaded APK is signed by the same developer as the
     * currently installed version. If the app is not installed, verification
     * is skipped (first-time install).
     */
    fun verify(apkFilePath: String, packageName: String): Result {
        if (packageName.isBlank()) return Result.Success

        val file = File(apkFilePath)
        if (!file.exists()) return Result.Error("APK file not found")

        val installedCerts = getInstalledSigningCerts(packageName)
            ?: return Result.NotInstalled

        val apkCerts = getApkSigningCerts(apkFilePath)
            ?: return Result.Error("Could not read APK signing info")

        if (apkCerts.isEmpty()) {
            return Result.Error("Downloaded APK is not signed")
        }

        val installedHashes = installedCerts.map { sha256Hex(it) }.toSet()
        val apkHashes = apkCerts.map { sha256Hex(it) }.toSet()

        return if (installedHashes.any { it in apkHashes }) {
            Result.Success
        } else {
            Result.SignatureMismatch(
                "APK is signed by a different developer than the installed version"
            )
        }
    }

    private fun getInstalledSigningCerts(packageName: String): List<ByteArray>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = info.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory?.map { it.toByteArray() }
                        ?: emptyList()
                }
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    packageName, PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun getApkSigningCerts(apkFilePath: String): List<ByteArray>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageArchiveInfo(
                    apkFilePath, PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: return null
                val signingInfo = info.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory?.map { it.toByteArray() }
                        ?: emptyList()
                }
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageArchiveInfo(
                    apkFilePath, PackageManager.GET_SIGNATURES
                ) ?: return null
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
