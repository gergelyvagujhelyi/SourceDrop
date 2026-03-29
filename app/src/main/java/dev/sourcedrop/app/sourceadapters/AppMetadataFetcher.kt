package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.util.UrlValidator
import dev.sourcedrop.app.util.boundedBody
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
            val repoName = formatRepoName(repo)
            val (packageName, appName) = fetchGitHubProjectInfo(owner, repo)
            val versions = fetchGitHubVersions(owner, repo)
            AppMetadata(displayName = appName ?: repoName, packageName = packageName, versions = versions)
        }

    suspend fun fetchFromGitLab(host: String, projectPath: String): AppMetadata =
        withContext(Dispatchers.IO) {
            val projectName = projectPath.substringAfterLast("/")
            val repoName = formatRepoName(projectName)
            val (packageName, appName) = fetchGitLabProjectInfo(host, projectPath)
            val versions = fetchGitLabVersions(host, projectPath)
            AppMetadata(displayName = appName ?: repoName, packageName = packageName, versions = versions)
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
        val body = response.boundedBody()
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
                val apkCandidates = release["assets"]?.jsonArray?.mapNotNull { asset ->
                    val name = asset.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
                    val url = asset.jsonObject["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    name to url
                } ?: emptyList()
                val apkUrl = preferSignedApk(apkCandidates)
                ReleaseVersion(tagName = tagName, version = version, apkUrl = apkUrl, releaseNotes = releaseNotes, isPreRelease = isPreRelease)
            }
        } catch (e: AdapterError.RateLimitError) { throw e }
        catch (_: Exception) { emptyList() }
    }

    private fun fetchGitLabVersions(host: String, projectPath: String): List<ReleaseVersion> {
        val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
        val apiUrl = "https://$host/api/v4/projects/$encodedPath/releases"
        try { UrlValidator.validateHost(apiUrl) } catch (_: IllegalArgumentException) { return emptyList() }
        val request = Request.Builder()
            .url(apiUrl)
            .build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return emptyList() }
        if (!response.isSuccessful) { response.close(); return emptyList() }
        val body = response.boundedBody()
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

    private data class ProjectInfo(val packageName: String?, val appName: String?)

    private fun fetchGitHubProjectInfo(owner: String, repo: String): ProjectInfo {
        // Each entry: build.gradle path -> corresponding strings.xml path
        val modules = listOf(
            "app/build.gradle.kts" to "app/src/main/res/values/strings.xml",
            "app/build.gradle" to "app/src/main/res/values/strings.xml",
            "build.gradle.kts" to "src/main/res/values/strings.xml",
            "build.gradle" to "src/main/res/values/strings.xml"
        )
        var packageName: String? = null
        var stringsPath: String? = null

        for ((gradlePath, resPath) in modules) {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/contents/$gradlePath")
                    .header("Accept", "application/vnd.github.raw+json")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val content = response.boundedBody()
                    response.close()
                    val appId = extractApplicationId(content)
                    if (appId != null) {
                        packageName = appId
                        stringsPath = resPath
                        break
                    }
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

        var appName: String? = null
        if (stringsPath != null) {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/contents/$stringsPath")
                    .header("Accept", "application/vnd.github.raw+json")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val content = response.boundedBody()
                    response.close()
                    appName = extractAppName(content)
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

        return ProjectInfo(packageName, appName)
    }

    private fun fetchGitLabProjectInfo(host: String, projectPath: String): ProjectInfo {
        try { UrlValidator.validateHost("https://$host/") } catch (_: IllegalArgumentException) { return ProjectInfo(null, null) }
        val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
        val modules = listOf(
            "app%2Fbuild.gradle.kts" to "app%2Fsrc%2Fmain%2Fres%2Fvalues%2Fstrings.xml",
            "app%2Fbuild.gradle" to "app%2Fsrc%2Fmain%2Fres%2Fvalues%2Fstrings.xml",
            "build.gradle.kts" to "src%2Fmain%2Fres%2Fvalues%2Fstrings.xml",
            "build.gradle" to "src%2Fmain%2Fres%2Fvalues%2Fstrings.xml"
        )
        var packageName: String? = null
        var stringsPath: String? = null

        for ((gradlePath, resPath) in modules) {
            try {
                val request = Request.Builder()
                    .url("https://$host/api/v4/projects/$encodedPath/repository/files/$gradlePath/raw?ref=HEAD")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val content = response.boundedBody()
                    response.close()
                    val appId = extractApplicationId(content)
                    if (appId != null) {
                        packageName = appId
                        stringsPath = resPath
                        break
                    }
                } else {
                    response.close()
                }
            } catch (_: Exception) {
            }
        }

        var appName: String? = null
        if (stringsPath != null) {
            try {
                val request = Request.Builder()
                    .url("https://$host/api/v4/projects/$encodedPath/repository/files/$stringsPath/raw?ref=HEAD")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val content = response.boundedBody()
                    response.close()
                    appName = extractAppName(content)
                } else {
                    response.close()
                }
            } catch (_: Exception) {
            }
        }

        return ProjectInfo(packageName, appName)
    }

    private fun formatResetTime(resetHeader: String?): String {
        val resetEpoch = resetHeader?.toLongOrNull() ?: return "soon"
        val now = System.currentTimeMillis() / 1000
        val diff = resetEpoch - now
        if (diff <= 0) return "soon"
        val minutes = (diff + 59) / 60
        return "in $minutes min"
    }

    private fun preferSignedApk(candidates: List<Pair<String, String>>): String {
        if (candidates.isEmpty()) return ""
        candidates.firstOrNull { (name, _) ->
            name.contains("signed", ignoreCase = true) && !name.contains("unsigned", ignoreCase = true)
        }?.let { return it.second }
        candidates.firstOrNull { (name, _) ->
            !name.contains("unsigned", ignoreCase = true)
        }?.let { return it.second }
        return candidates.first().second
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
        private val APP_NAME_REGEX = Regex(
            """<string\s+name\s*=\s*"app_name"\s*>([^<]+)</string>"""
        )

        fun extractApplicationId(buildFileContent: String): String? {
            APPLICATION_ID_REGEX.find(buildFileContent)?.groupValues?.get(1)?.let { return it }
            NAMESPACE_REGEX.find(buildFileContent)?.groupValues?.get(1)?.let { return it }
            return null
        }

        fun extractAppName(stringsXmlContent: String): String? {
            return APP_NAME_REGEX.find(stringsXmlContent)?.groupValues?.get(1)?.trim()
        }
    }
}
