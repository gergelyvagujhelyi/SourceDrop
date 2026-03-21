package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.util.SafeRegex
import dev.sourcedrop.app.util.UrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DirectApkAdapter(private val client: OkHttpClient) : SourceAdapter {

    override suspend fun checkForUpdate(app: TrackedApp): AdapterResult =
        withContext(Dispatchers.IO) {
            val url = app.apkUrl.ifBlank { app.sourceUrl }
            if (url.isBlank()) {
                throw AdapterError.InvalidConfigError("No APK URL configured")
            }

            try {
                UrlValidator.validateHost(url)
            } catch (e: IllegalArgumentException) {
                throw AdapterError.InvalidConfigError(e.message ?: "Invalid URL")
            }

            // HEAD request to verify the URL is reachable
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                throw AdapterError.NetworkError("Failed to reach APK URL: ${e.message}", e)
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                if (code == 404) throw AdapterError.NotFoundError("APK not found at URL")
                throw AdapterError.NetworkError("APK URL returned $code")
            }

            response.close()

            // For direct APK, version must come from the user's config or headers
            val version = if (app.versionPattern.isNotBlank() && app.currentVersion.isNotBlank()) {
                // Use the source URL as a version source (e.g. URL contains version)
                extractVersionFromUrl(url, app.versionPattern)
            } else {
                // We can't determine version from a direct APK URL alone
                // Return "available" as a sentinel so the user sees something
                "available"
            }

            AdapterResult(
                version = version,
                apkUrl = url
            )
        }

    private fun extractVersionFromUrl(url: String, pattern: String): String {
        val match = SafeRegex.find(pattern, url) ?: return "available"
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: match.value.ifBlank { "available" }
    }
}
