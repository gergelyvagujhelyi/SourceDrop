package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.data.local.entity.TrackedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.sourcedrop.app.util.SafeRegex
import dev.sourcedrop.app.util.UrlValidator
import dev.sourcedrop.app.util.boundedBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class GitLabAdapter(private val client: OkHttpClient) : SourceAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun checkForUpdate(app: TrackedApp): AdapterResult =
        withContext(Dispatchers.IO) {
            val (host, projectPath) = parseGitLabUrl(app.sourceUrl)
            val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
            val apiUrl = "https://$host/api/v4/projects/$encodedPath/releases"

            try {
                UrlValidator.validateHost(apiUrl)
            } catch (e: IllegalArgumentException) {
                throw AdapterError.InvalidConfigError(e.message ?: "Invalid URL")
            }

            val request = Request.Builder()
                .url(apiUrl)
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                throw AdapterError.NetworkError("Failed to connect to GitLab: ${e.message}", e)
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                if (code == 404) throw AdapterError.NotFoundError("Project not found: $projectPath")
                throw AdapterError.NetworkError("GitLab API returned $code")
            }

            val body = response.boundedBody()
            response.close()

            val releases = json.parseToJsonElement(body).jsonArray
            if (releases.isEmpty()) {
                throw AdapterError.NotFoundError("No releases found for $projectPath")
            }

            val release = releases[0].jsonObject

            val tagName = release["tag_name"]?.jsonPrimitive?.content
                ?: throw AdapterError.ParseError("Release has no tag_name")

            val version = extractVersion(tagName, app.versionPattern)
            val description = release["description"]?.jsonPrimitive?.content ?: ""
            val apkUrl = findApkLink(release, app.assetMatchPattern)

            AdapterResult(
                version = version,
                apkUrl = apkUrl,
                releaseNotes = description
            )
        }

    private fun findApkLink(release: JsonObject, pattern: String): String {
        val assets = release["assets"]?.jsonObject ?: return ""
        val links = assets["links"]?.jsonArray ?: return ""
        val useDefault = pattern.isBlank()

        for (link in links) {
            val obj = link.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: ""
            val url = obj["direct_asset_url"]?.jsonPrimitive?.content
                ?: obj["url"]?.jsonPrimitive?.content ?: continue

            val matches = if (useDefault) {
                name.endsWith(".apk", ignoreCase = true) || url.endsWith(".apk", ignoreCase = true)
            } else {
                SafeRegex.containsMatch(pattern, name, setOf(RegexOption.IGNORE_CASE)) ||
                    SafeRegex.containsMatch(pattern, url, setOf(RegexOption.IGNORE_CASE))
            }
            if (matches) return url
        }
        return ""
    }

    private fun extractVersion(tagName: String, pattern: String): String {
        if (pattern.isBlank()) return tagName.trimStart('v', 'V')
        val match = SafeRegex.find(pattern, tagName) ?: return tagName.trimStart('v', 'V')
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: match.value.ifBlank { tagName.trimStart('v', 'V') }
    }

    companion object {
        private val GITLAB_URL_PATTERN = Regex(
            "(?:https?://)?([^/]+)/(.+?)/?$",
            RegexOption.IGNORE_CASE
        )

        fun parseGitLabUrl(url: String): Pair<String, String> {
            val cleaned = url.trim()
                .removeSuffix("/")
                .removeSuffix("/-/releases")
                .removeSuffix("/releases")
            val match = GITLAB_URL_PATTERN.matchEntire(cleaned)
                ?: throw AdapterError.InvalidConfigError(
                    "Invalid GitLab URL. Expected: https://gitlab.com/owner/repo"
                )
            val host = match.groupValues[1]
            val path = match.groupValues[2].removeSuffix(".git")
            return Pair(host, path)
        }
    }
}
