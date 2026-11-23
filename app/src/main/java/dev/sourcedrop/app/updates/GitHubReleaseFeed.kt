package dev.sourcedrop.app.updates

import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.sourceadapters.GitHubAdapter
import dev.sourcedrop.app.util.boundedBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** The newest release tag of an app's source, from somewhere cheaper than the API. */
fun interface ReleaseFeed {
    /** The newest tag, or null if it can't be told (then the API decides). */
    suspend fun newestTag(app: TrackedApp): String?
}

/**
 * Reads a repository's public releases feed (github.com/<owner>/<repo>/releases.atom). It isn't
 * part of the REST API, so it doesn't spend the 60-an-hour API budget. It only answers "has
 * anything been published since last time?"; which APK to install still comes from the API.
 *
 * Any problem (offline, private repo, a changed format) gives null, so callers fall back to the
 * API. Remembers each feed's ETag, so an unchanged feed comes back as a short 304.
 */
class GitHubReleaseFeed(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://github.com"
) : ReleaseFeed {

    private data class Cached(val etag: String, val tag: String)

    private val cache = ConcurrentHashMap<String, Cached>()

    override suspend fun newestTag(app: TrackedApp): String? = withContext(Dispatchers.IO) {
        val (owner, repo) = try {
            GitHubAdapter.parseOwnerRepo(app.sourceUrl)
        } catch (_: Exception) {
            return@withContext null
        }
        val url = "$baseUrl/$owner/$repo/releases.atom"
        val cached = cache[url]
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/atom+xml")
            .apply { cached?.let { header("If-None-Match", it.etag) } }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 && cached != null -> cached.tag
                    !response.isSuccessful -> null
                    else -> {
                        val tag = parseNewestTag(response.boundedBody(MAX_FEED_BYTES))
                        val etag = response.header("ETag")
                        if (tag != null && etag != null) cache[url] = Cached(etag, tag) else cache.remove(url)
                        tag
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_FEED_BYTES = 1L * 1024 * 1024

        // Each entry links to .../releases/tag/<tag>; entries are newest first.
        private val TAG_LINK = Regex("""href="[^"]*/releases/tag/([^"?#]+)"""")

        /** The first entry's tag, decoded; null if the feed has none. */
        internal fun parseNewestTag(atom: String): String? {
            val raw = TAG_LINK.find(atom)?.groupValues?.get(1) ?: return null
            return percentDecode(unescapeXml(raw)).takeIf { it.isNotBlank() }
        }

        private fun unescapeXml(s: String): String = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

        /** Decodes %XX sequences as UTF-8; unlike URLDecoder, leaves '+' alone. */
        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3) else null
                val byte = hex?.takeIf { h -> h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } }
                    ?.toInt(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
