package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.data.local.entity.TrackedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class JsonAdapter(private val client: OkHttpClient) : SourceAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun checkForUpdate(app: TrackedApp): AdapterResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(app.sourceUrl)
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                throw AdapterError.NetworkError("Failed to fetch JSON: ${e.message}", e)
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw AdapterError.NetworkError("JSON endpoint returned $code")
            }

            val body = response.body?.string()
                ?: throw AdapterError.ParseError("Empty response body")
            response.close()

            val root = try {
                json.parseToJsonElement(body)
            } catch (e: Exception) {
                throw AdapterError.ParseError("Invalid JSON: ${e.message}")
            }

            // If root is an array, use the first element
            val element = when (root) {
                is JsonArray -> {
                    if (root.isEmpty()) throw AdapterError.NotFoundError("Empty JSON array")
                    root[0]
                }
                else -> root
            }

            val versionPath = app.versionPattern.ifBlank { "version" }
            val version = resolveJsonPath(element, versionPath)
                ?: throw AdapterError.ParseError("Could not find version at path: $versionPath")

            val apkUrl = if (app.assetMatchPattern.isNotBlank()) {
                resolveJsonPath(element, app.assetMatchPattern) ?: ""
            } else if (app.apkUrl.isNotBlank()) {
                app.apkUrl
            } else {
                resolveJsonPath(element, "apk_url")
                    ?: resolveJsonPath(element, "download_url")
                    ?: ""
            }

            val releaseNotes = resolveJsonPath(element, "release_notes")
                ?: resolveJsonPath(element, "body")
                ?: resolveJsonPath(element, "changelog")
                ?: ""

            AdapterResult(
                version = version,
                apkUrl = apkUrl,
                releaseNotes = releaseNotes
            )
        }

    companion object {
        /**
         * Resolves a dot-separated path in a JSON element.
         * Example: "data.latest.version" navigates root -> data -> latest -> version
         */
        fun resolveJsonPath(element: JsonElement, path: String): String? {
            val segments = path.split(".")
            var current: JsonElement = element
            for (segment in segments) {
                current = when (current) {
                    is JsonObject -> current.jsonObject[segment] ?: return null
                    is JsonArray -> {
                        val index = segment.toIntOrNull() ?: return null
                        current.jsonArray.getOrNull(index) ?: return null
                    }
                    else -> return null
                }
            }
            return when (current) {
                is JsonPrimitive -> current.jsonPrimitive.content
                else -> current.toString()
            }
        }
    }
}
