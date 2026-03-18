package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.data.local.entity.TrackedApp
import okhttp3.OkHttpClient

class SourceAdapterFactory(private val client: OkHttpClient) {

    fun create(sourceType: String): SourceAdapter {
        return when (sourceType) {
            TrackedApp.SOURCE_TYPE_GITHUB -> GitHubAdapter(client)
            TrackedApp.SOURCE_TYPE_GITLAB -> GitLabAdapter(client)
            TrackedApp.SOURCE_TYPE_JSON -> JsonAdapter(client)
            TrackedApp.SOURCE_TYPE_DIRECT_APK -> DirectApkAdapter(client)
            TrackedApp.SOURCE_TYPE_HTML -> HtmlAdapter(client)
            else -> throw AdapterError.InvalidConfigError("Unknown source type: $sourceType")
        }
    }
}
