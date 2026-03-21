package dev.sourcedrop.app.sourceadapters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

data class AppMetadata(
    val displayName: String? = null,
    val packageName: String? = null,
    val versions: List<ReleaseVersion> = emptyList()
)

data class ReleaseVersion(
    val tagName: String,
    val version: String,
    val apkUrl: String = "",
    val releaseNotes: String = "",
    val isPreRelease: Boolean = false
)

class AppMetadataFetcher(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchFromGitHub(owner: String, repo: String): AppMetadata =
        withContext(Dispatchers.IO) {
            val displayName = formatRepoName(repo)
            val packageName = fetchGitHubApplicationId(owner, repo)
            val versions = fetchGitHubVersions(owner, repo)
            AppMetadata(displayName = displayName, packageName = packageName, versions = versions)
        }

    suspend fun fetchFromGitLab(host: String, projectPath: String): AppMetadata =
        withContext(Dispatchers.IO) {
            val projectName = projectPath.substringAfterLast("/")
            val displayName = formatRepoName(projectName)
            val packageName = fetchGitLabApplicationId(host, projectPath)
            val versions = fetchGitLabVersions(host, projectPath)
            AppMetadata(displayName = displayName, packageName = packageName, versions = versions)
        }

    private fun fetchGitHubVersions(owner: String, repo: String): List<ReleaseVersion> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases")
            .header("Accept", "application/vnd.github+json")
            .build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return emptyList() }
        if (!response.isSuccessful) {
            val code = response.code
            val resetHeader = response.header("x-ratelimit-reset")
            response.close()
            if (code == 403 || code == 429) {
                val resetInfo = formatResetTime(resetHeader)
                throw AdapterError.RateLimitError("GitHub API rate limit exceeded. Resets $resetInfo")
            }
            return emptyList()
        }
        val body = response.body?.string() ?: return emptyList()
        response.close()

        return try {
            val releases = json.parseToJsonElement(body).jsonArray
            releases.mapNotNull { element ->
                val release = element.jsonObject
                val draft = release["draft"]?.jsonPrimitive?.boolean ?: false
                if (draft) return@mapNotNull null
                val tagName = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val version = tagName.trimStart('v', 'V')
                val isPreRelease = release["prerelease"]?.jsonPrimitive?.boolean ?: false
                val releaseNotes = release["body"]?.jsonPrimitive?.content ?: ""
                val apkUrl = release["assets"]?.jsonArray?.firstOrNull { asset ->
                    val name = asset.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                    name.endsWith(".apk", ignoreCase = true)
                }?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.content ?: ""
                ReleaseVersion(tagName = tagName, version = version, apkUrl = apkUrl, releaseNotes = releaseNotes, isPreRelease = isPreRelease)
            }
        } catch (e: AdapterError.RateLimitError) { throw e }
        catch (_: Exception) { emptyList() }
    }

    private fun fetchGitLabVersions(host: String, projectPath: String): List<ReleaseVersion> {
        val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
        val request = Request.Builder()
            .url("https://$host/api/v4/projects/$encodedPath/releases")
            .build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return emptyList() }
        if (!response.isSuccessful) { response.close(); return emptyList() }
        val body = response.body?.string() ?: return emptyList()
        response.close()

        return try {
            val releases = json.parseToJsonElement(body).jsonArray
            releases.mapNotNull { element ->
                val release = element.jsonObject
                val tagName = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val version = tagName.trimStart('v', 'V')
                val releaseNotes = release["description"]?.jsonPrimitive?.content ?: ""
                ReleaseVersion(tagName = tagName, version = version, releaseNotes = releaseNotes)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchGitHubApplicationId(owner: String, repo: String): String? {
        val paths = listOf(
            "app/build.gradle.kts",
            "app/build.gradle",
            "build.gradle.kts",
            "build.gradle"
        )
        for (path in paths) {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/contents/$path")
                    .header("Accept", "application/vnd.github.raw+json")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val content = response.body?.string() ?: ""
                    response.close()
                    val appId = extractApplicationId(content)
                    if (appId != null) return appId
                } else {
                    val code = response.code
                    val resetHeader = response.header("x-ratelimit-reset")
                    response.close()
                    if (code == 403 || code == 429) {
                        throw AdapterError.RateLimitError("GitHub API rate limit exceeded. Resets ${formatResetTime(resetHeader)}")
                    }
                }
            } catch (e: AdapterError.RateLimitError) { throw e }
            catch (_: Exception) {
            }
        }
        return null
    }

    private fun fetchGitLabApplicationId(host: String, projectPath: String): String? {
        val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
        val paths = listOf(
            "app%2Fbuild.gradle.kts",
            "app%2Fbuild.gradle",
            "build.gradle.kts",
            "build.gradle"
        )
        for (filePath in paths) {
            try {
                val request = Request.Builder()
                    .url("https://$host/api/v4/projects/$encodedPath/repository/files/$filePath/raw?ref=HEAD")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val content = response.body?.string() ?: ""
                    response.close()
                    val appId = extractApplicationId(content)
                    if (appId != null) return appId
                } else {
                    response.close()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun formatResetTime(resetHeader: String?): String {
        val resetEpoch = resetHeader?.toLongOrNull() ?: return "soon"
        val now = System.currentTimeMillis() / 1000
        val diff = resetEpoch - now
        if (diff <= 0) return "soon"
        val minutes = (diff + 59) / 60
        return "in $minutes min"
    }

    private fun formatRepoName(name: String): String {
        return name
            .replace(Regex("[-_.]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
    }

    companion object {
        private val APPLICATION_ID_REGEX = Regex(
            """applicationId\s*[=(]\s*["']([^"']+)["']"""
        )
        private val NAMESPACE_REGEX = Regex(
            """namespace\s*[=(]\s*["']([^"']+)["']"""
        )

        fun extractApplicationId(buildFileContent: String): String? {
            APPLICATION_ID_REGEX.find(buildFileContent)?.groupValues?.get(1)?.let { return it }
            NAMESPACE_REGEX.find(buildFileContent)?.groupValues?.get(1)?.let { return it }
            return null
        }
    }
}
