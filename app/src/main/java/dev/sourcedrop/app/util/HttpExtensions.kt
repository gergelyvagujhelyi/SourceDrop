package dev.sourcedrop.app.util

import okhttp3.Response
import java.io.IOException

private const val MAX_RESPONSE_BYTES: Long = 5L * 1024 * 1024 // 5 MB

/**
 * Reads the response body with a maximum size limit to prevent OOM from
 * malicious or unexpectedly large responses.
 * Returns null if the body is null. Throws [IOException] if the limit is exceeded.
 */
fun Response.boundedBody(maxSize: Long = MAX_RESPONSE_BYTES): String {
    val source = this.body.source()
    source.request(maxSize + 1)
    if (source.buffer.size > maxSize) {
        throw IOException("Response exceeds ${maxSize / 1024 / 1024}MB size limit")
    }
    return source.buffer.readUtf8()
}
