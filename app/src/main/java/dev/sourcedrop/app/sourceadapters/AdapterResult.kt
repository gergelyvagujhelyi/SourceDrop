package dev.sourcedrop.app.sourceadapters

data class AdapterResult(
    val version: String,
    val apkUrl: String = "",
    val releaseNotes: String = "",
    val isPreRelease: Boolean = false
)

sealed class AdapterError(override val message: String) : Exception(message) {
    class NetworkError(message: String, override val cause: Throwable? = null) :
        AdapterError(message)
    class ParseError(message: String) : AdapterError(message)
    class NotFoundError(message: String) : AdapterError(message)
    class InvalidConfigError(message: String) : AdapterError(message)
    class RateLimitError(message: String) : AdapterError(message)
}
