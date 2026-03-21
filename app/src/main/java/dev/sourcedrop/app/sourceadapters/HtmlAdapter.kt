package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.util.SafeRegex
import dev.sourcedrop.app.util.UrlValidator
import dev.sourcedrop.app.util.boundedBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class HtmlAdapter(private val client: OkHttpClient) : SourceAdapter {

    override suspend fun checkForUpdate(app: TrackedApp): AdapterResult =
        withContext(Dispatchers.IO) {
            if (app.versionPattern.isBlank()) {
                throw AdapterError.InvalidConfigError(
                    "Version pattern (regex) is required for HTML source type"
                )
            }

            try {
                UrlValidator.validateHost(app.sourceUrl)
            } catch (e: IllegalArgumentException) {
                throw AdapterError.InvalidConfigError(e.message ?: "Invalid URL")
            }

            val request = Request.Builder()
                .url(app.sourceUrl)
                .header("User-Agent", "SourceDrop/1.0")
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                throw AdapterError.NetworkError("Failed to fetch page: ${e.message}", e)
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw AdapterError.NetworkError("Page returned $code")
            }

            val html = response.boundedBody()
            response.close()

            val version = extractWithRegex(html, app.versionPattern)
                ?: throw AdapterError.ParseError(
                    "Version pattern did not match any content on the page"
                )

            val apkUrl = if (app.assetMatchPattern.isNotBlank()) {
                extractWithRegex(html, app.assetMatchPattern)
                    ?: app.apkUrl.ifBlank { "" }
            } else {
                app.apkUrl.ifBlank { "" }
            }

            // Resolve relative APK URLs
            val resolvedApkUrl = if (apkUrl.isNotBlank() && !apkUrl.startsWith("http")) {
                resolveRelativeUrl(app.sourceUrl, apkUrl)
            } else {
                apkUrl
            }

            AdapterResult(
                version = version,
                apkUrl = resolvedApkUrl
            )
        }

    companion object {
        fun extractWithRegex(content: String, pattern: String): String? {
            val match = SafeRegex.find(pattern, content) ?: return null
            return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: match.value
        }

        fun resolveRelativeUrl(baseUrl: String, relative: String): String {
            return try {
                val base = java.net.URI(baseUrl)
                base.resolve(relative).toString()
            } catch (e: Exception) {
                relative
            }
        }
    }
}
