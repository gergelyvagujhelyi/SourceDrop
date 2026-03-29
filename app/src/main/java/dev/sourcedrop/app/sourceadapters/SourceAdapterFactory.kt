package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.preferences.AppPreferences
import okhttp3.OkHttpClient

class SourceAdapterFactory(
    private val client: OkHttpClient,
    private val preferences: AppPreferences
) {

    fun create(sourceType: String): SourceAdapter {
        return when (sourceType) {
            TrackedApp.SOURCE_TYPE_GITHUB -> GitHubAdapter(client, preferences.githubApiToken)
            TrackedApp.SOURCE_TYPE_GITLAB -> GitLabAdapter(client)
            TrackedApp.SOURCE_TYPE_JSON -> JsonAdapter(client)
            TrackedApp.SOURCE_TYPE_DIRECT_APK -> DirectApkAdapter(client)
            TrackedApp.SOURCE_TYPE_HTML -> HtmlAdapter(client)
            else -> throw AdapterError.InvalidConfigError("Unknown source type: $sourceType")
        }
    }
}
