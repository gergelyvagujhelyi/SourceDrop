package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.updates.GitHubRateLimit
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
import okhttp3.Response
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

class AppMetadataFetcher(
    private val client: OkHttpClient,
    private val apiTokenProvider: () -> String = { "" },
    private val rateLimit: GitHubRateLimit? = null,
    private val githubApiBase: String = "https://api.github.com"
) {

    // Lookups for the Add app screen. Real failures (no connection, server errors, unreadable
    // replies) are thrown, so the screen shows them and Next can try again; otherwise a failed
    // lookup looked like an app with no package name and no releases. A 404 for one of the
    // build files tried is normal and isn't a failure.

    private fun unreachable(service: String, e: Exception) = AdapterError.NetworkError(
        "Couldn't reach $service (${e.message ?: e.javaClass.simpleName}). " +
            "Check your connection and tap Next to try again.", e
    )

    private fun badReply(service: String, code: Int) = AdapterError.NetworkError(
        "$service couldn't be read right now (HTTP $code). Tap Next to try again."
    )

    private fun unexpectedReply(service: String) =
        AdapterError.ParseError("$service sent an unexpected reply. Tap Next to try again.")

    /** Records this GitHub response's rate-limit headers; true if GitHub refused because of the limit. */
    private fun Response.githubLimited(): Boolean =
        rateLimit?.record(code) { header(it) } ?: GitHubRateLimit.isRateLimited(
            code,
            header("x-ratelimit-remaining")?.trim()?.toIntOrNull(),
            header("retry-after")?.trim()?.toLongOrNull()
        )

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

    private fun githubRequest(
        url: String,
        accept: String = "application/vnd.github+json"
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", accept)
        val token = apiTokenProvider()
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    private fun fetchGitHubVersions(owner: String, repo: String): List<ReleaseVersion> {
        val request = githubRequest("$githubApiBase/repos/$owner/$repo/releases")
        val response = try { client.newCall(request).execute() } catch (e: Exception) { throw unreachable("GitHub", e) }
        val limited = response.githubLimited()
        if (!response.isSuccessful) {
            val code = response.code
            val resetHeader = response.header("x-ratelimit-reset")
            response.close()
            if (limited) {
                val resetInfo = formatResetTime(resetHeader)
                throw AdapterError.RateLimitError("GitHub API rate limit exceeded. Resets $resetInfo")
            }
            if (code == 404) {
                throw AdapterError.NotFoundError(
                    "Repository not found: $owner/$repo. Check the URL; private repositories " +
                        "need an API token in Settings."
                )
            }
            throw badReply("GitHub", code)
        }
        val body = try { response.boundedBody() } catch (e: Exception) { throw unreachable("GitHub", e) }
            finally { response.close() }

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
        catch (_: Exception) { throw unexpectedReply("GitHub") }
    }

    private fun fetchGitLabVersions(host: String, projectPath: String): List<ReleaseVersion> {
        val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
        val apiUrl = "https://$host/api/v4/projects/$encodedPath/releases"
        try { UrlValidator.validateHost(apiUrl) } catch (_: IllegalArgumentException) { return emptyList() }
        val request = Request.Builder()
            .url(apiUrl)
            .build()
        val response = try { client.newCall(request).execute() } catch (e: Exception) { throw unreachable(host, e) }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 404) throw AdapterError.NotFoundError("Project not found: $projectPath on $host. Check the URL.")
            throw badReply(host, code)
        }
        val body = try { response.boundedBody() } catch (e: Exception) { throw unreachable(host, e) }
            finally { response.close() }

        return try {
            val releases = json.parseToJsonElement(body).jsonArray
            releases.mapNotNull { element ->
                val release = element.jsonObject
                val tagName = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val version = tagName.trimStart('v', 'V')
                val releaseNotes = release["description"]?.jsonPrimitive?.content ?: ""
                ReleaseVersion(tagName = tagName, version = version, releaseNotes = releaseNotes)
            }
        } catch (_: Exception) { throw unexpectedReply(host) }
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
        var failure: AdapterError? = null

        for ((gradlePath, resPath) in modules) {
            try {
                val request = githubRequest(
                    "$githubApiBase/repos/$owner/$repo/contents/$gradlePath",
                    "application/vnd.github.raw+json"
                )
                val response = client.newCall(request).execute()
                val limited = response.githubLimited()
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
                    if (limited) {
                        throw AdapterError.RateLimitError("GitHub API rate limit exceeded. Resets ${formatResetTime(resetHeader)}")
                    }
                    if (code != 404) failure = badReply("GitHub", code)
                }
            } catch (e: AdapterError.RateLimitError) { throw e }
            catch (e: Exception) {
                // No connection: the other paths would fail the same way.
                failure = unreachable("GitHub", e)
                break
            }
        }
        // Only a failure if the package name couldn't be found because of it.
        if (packageName == null) failure?.let { throw it }

        var appName: String? = null
        if (stringsPath != null) {
            try {
                val request = githubRequest(
                    "$githubApiBase/repos/$owner/$repo/contents/$stringsPath",
                    "application/vnd.github.raw+json"
                )
                val response = client.newCall(request).execute()
                val limited = response.githubLimited()
                if (response.isSuccessful) {
                    val content = response.boundedBody()
                    response.close()
                    appName = extractAppName(content)
                } else {
                    val resetHeader = response.header("x-ratelimit-reset")
                    response.close()
                    if (limited) {
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
        var failure: AdapterError? = null

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
                    val code = response.code
                    response.close()
                    if (code != 404) failure = badReply(host, code)
                }
            } catch (e: Exception) {
                failure = unreachable(host, e)
                break
            }
        }
        if (packageName == null) failure?.let { throw it }

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
        // Kotlin `applicationId = "x"`, `applicationId("x")`, and Groovy `applicationId 'x'`.
        private val APPLICATION_ID_REGEX = Regex(
            """\bapplicationId\s*[=(]?\s*["']([^"']+)["']"""
        )
        private val NAMESPACE_REGEX = Regex(
            """\bnamespace\s*[=(]?\s*["']([^"']+)["']"""
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
