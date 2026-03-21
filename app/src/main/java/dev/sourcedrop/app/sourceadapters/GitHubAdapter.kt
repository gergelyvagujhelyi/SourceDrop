package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.data.local.entity.TrackedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class GitHubAdapter(private val client: OkHttpClient) : SourceAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun checkForUpdate(app: TrackedApp): AdapterResult =
        withContext(Dispatchers.IO) {
            val (owner, repo) = parseOwnerRepo(app.sourceUrl)
            val apiUrl = "https://api.github.com/repos/$owner/$repo/releases"

            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                throw AdapterError.NetworkError("Failed to connect to GitHub: ${e.message}", e)
            }

            if (!response.isSuccessful) {
                val code = response.code
                val resetHeader = response.header("x-ratelimit-reset")
                response.close()
                if (code == 404) throw AdapterError.NotFoundError("Repository not found: $owner/$repo")
                if (code == 403 || code == 429) {
                    val resetInfo = formatResetTime(resetHeader)
                    throw AdapterError.RateLimitError("GitHub API rate limit exceeded. Resets $resetInfo")
                }
                throw AdapterError.NetworkError("GitHub API returned $code")
            }

            val body = response.body?.string()
                ?: throw AdapterError.ParseError("Empty response from GitHub")
            response.close()

            val releases = json.parseToJsonElement(body).jsonArray
            if (releases.isEmpty()) {
                throw AdapterError.NotFoundError("No releases found for $owner/$repo")
            }

            val release = findRelease(releases, app)
                ?: throw AdapterError.NotFoundError("No matching release found")

            val tagName = release["tag_name"]?.jsonPrimitive?.content
                ?: throw AdapterError.ParseError("Release has no tag_name")

            val version = extractVersion(tagName, app.versionPattern)
            val releaseNotes = release["body"]?.jsonPrimitive?.content ?: ""
            val isPreRelease = release["prerelease"]?.jsonPrimitive?.boolean ?: false
            val apkUrl = findApkAsset(release, app.assetMatchPattern)

            AdapterResult(
                version = version,
                apkUrl = apkUrl,
                releaseNotes = releaseNotes,
                isPreRelease = isPreRelease
            )
        }

    private fun findRelease(releases: JsonArray, app: TrackedApp): JsonObject? {
        if (app.includePreReleases) {
            // When pre-releases are included, return first non-draft release
            for (element in releases) {
                val release = element.jsonObject
                val draft = release["draft"]?.jsonPrimitive?.boolean ?: false
                if (!draft) return release
            }
        } else {
            // Default: skip pre-releases, prefer stable
            for (element in releases) {
                val release = element.jsonObject
                val prerelease = release["prerelease"]?.jsonPrimitive?.boolean ?: false
                val draft = release["draft"]?.jsonPrimitive?.boolean ?: false
                if (!draft && !prerelease) return release
            }
            // Fallback: return first non-draft release (including pre-releases)
            for (element in releases) {
                val release = element.jsonObject
                val draft = release["draft"]?.jsonPrimitive?.boolean ?: false
                if (!draft) return release
            }
        }
        return null
    }

    private fun findApkAsset(release: JsonObject, pattern: String): String {
        val assets = release["assets"]?.jsonArray ?: return ""
        val regex = if (pattern.isNotBlank()) {
            try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (e: Exception) { null }
        } else {
            Regex("\\.apk$", RegexOption.IGNORE_CASE)
        }

        for (asset in assets) {
            val obj = asset.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: continue
            if (regex != null && regex.containsMatchIn(name)) {
                return obj["browser_download_url"]?.jsonPrimitive?.content ?: ""
            }
        }
        return ""
    }

    private fun extractVersion(tagName: String, pattern: String): String {
        if (pattern.isBlank()) return tagName.trimStart('v', 'V')
        return try {
            val regex = Regex(pattern)
            val match = regex.find(tagName)
            match?.groupValues?.getOrNull(1) ?: match?.value ?: tagName
        } catch (e: Exception) {
            tagName.trimStart('v', 'V')
        }
    }

    private fun formatResetTime(resetHeader: String?): String {
        val resetEpoch = resetHeader?.toLongOrNull() ?: return "soon"
        val now = System.currentTimeMillis() / 1000
        val diff = resetEpoch - now
        if (diff <= 0) return "soon"
        val minutes = (diff + 59) / 60
        return "in $minutes min"
    }

    companion object {
        private val GITHUB_URL_PATTERN = Regex(
            "(?:https?://)?github\\.com/([^/]+)/([^/]+)/?.*",
            RegexOption.IGNORE_CASE
        )

        fun parseOwnerRepo(url: String): Pair<String, String> {
            val match = GITHUB_URL_PATTERN.matchEntire(url.trim())
                ?: throw AdapterError.InvalidConfigError(
                    "Invalid GitHub URL. Expected: https://github.com/owner/repo"
                )
            return Pair(match.groupValues[1], match.groupValues[2].removeSuffix(".git"))
        }
    }
}
