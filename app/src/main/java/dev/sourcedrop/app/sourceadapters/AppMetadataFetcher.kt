package dev.sourcedrop.app.sourceadapters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

data class AppMetadata(
    val displayName: String? = null,
    val packageName: String? = null
)

class AppMetadataFetcher(private val client: OkHttpClient) {

    suspend fun fetchFromGitHub(owner: String, repo: String): AppMetadata =
        withContext(Dispatchers.IO) {
            val displayName = formatRepoName(repo)
            val packageName = fetchGitHubApplicationId(owner, repo)
            AppMetadata(displayName = displayName, packageName = packageName)
        }

    suspend fun fetchFromGitLab(host: String, projectPath: String): AppMetadata =
        withContext(Dispatchers.IO) {
            val projectName = projectPath.substringAfterLast("/")
            val displayName = formatRepoName(projectName)
            val packageName = fetchGitLabApplicationId(host, projectPath)
            AppMetadata(displayName = displayName, packageName = packageName)
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
                    response.close()
                }
            } catch (_: Exception) {
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
