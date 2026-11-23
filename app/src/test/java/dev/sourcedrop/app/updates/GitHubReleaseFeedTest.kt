package dev.sourcedrop.app.updates

import dev.sourcedrop.app.data.local.entity.TrackedApp
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GitHubReleaseFeedTest {

    private lateinit var server: MockWebServer
    private lateinit var feed: GitHubReleaseFeed
    private val app = TrackedApp(displayName = "Sotto", sourceUrl = "https://github.com/owner/repo")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        feed = GitHubReleaseFeed(OkHttpClient(), server.url("").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun atom(vararg tags: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US">
          <id>tag:github.com,2008:https://github.com/owner/repo/releases</id>
          <link type="text/html" rel="alternate" href="https://github.com/owner/repo/releases"/>
          ${tags.joinToString("\n") { tag ->
            """<entry>
                 <id>tag:github.com,2008:Repository/1/$tag</id>
                 <link rel="alternate" type="text/html" href="https://github.com/owner/repo/releases/tag/$tag"/>
                 <title>$tag</title>
               </entry>"""
          }}
        </feed>
    """.trimIndent()

    @Test
    fun `newest tag is the first entry`() = runTest {
        server.enqueue(MockResponse().setBody(atom("v0.7.10", "v0.7.9", "v0.7.8")))

        assertEquals("v0.7.10", feed.newestTag(app))
        assertEquals("/owner/repo/releases.atom", server.takeRequest().path)
    }

    @Test
    fun `tags are decoded`() {
        assertEquals("v1.0+build.5", GitHubReleaseFeed.parseNewestTag(atom("v1.0%2Bbuild.5")))
        assertEquals("v1.0+build", GitHubReleaseFeed.parseNewestTag(atom("v1.0+build")))
        assertEquals("app/1.2&x", GitHubReleaseFeed.parseNewestTag(atom("app%2F1.2&amp;x")))
        assertEquals("v2-é", GitHubReleaseFeed.parseNewestTag(atom("v2-%C3%A9")))
        assertEquals("bad%zz", GitHubReleaseFeed.parseNewestTag(atom("bad%zz")))
        assertEquals("end%", GitHubReleaseFeed.parseNewestTag(atom("end%")))
    }

    @Test
    fun `no releases gives null`() = runTest {
        server.enqueue(MockResponse().setBody(atom()))
        assertNull(feed.newestTag(app))
    }

    @Test
    fun `errors give null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(feed.newestTag(app))

        server.enqueue(MockResponse().setResponseCode(429))
        assertNull(feed.newestTag(app))

        server.enqueue(MockResponse().setBody("<html>not a feed</html>"))
        assertNull(feed.newestTag(app))
    }

    @Test
    fun `not a GitHub URL gives null without a request`() = runTest {
        assertNull(feed.newestTag(app.copy(sourceUrl = "https://example.com/app")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unchanged feed is a 304 with the remembered tag`() = runTest {
        server.enqueue(MockResponse().setHeader("ETag", "W/\"abc\"").setBody(atom("v1.2.0")))
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals("v1.2.0", feed.newestTag(app))
        assertEquals("v1.2.0", feed.newestTag(app))

        server.takeRequest()
        assertEquals("W/\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `changed feed replaces the remembered tag`() = runTest {
        server.enqueue(MockResponse().setHeader("ETag", "\"one\"").setBody(atom("v1.2.0")))
        server.enqueue(MockResponse().setHeader("ETag", "\"two\"").setBody(atom("v1.3.0", "v1.2.0")))

        assertEquals("v1.2.0", feed.newestTag(app))
        assertEquals("v1.3.0", feed.newestTag(app))
    }

    @Test
    fun `304 without anything remembered gives null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))
        assertNull(feed.newestTag(app))
    }
}
