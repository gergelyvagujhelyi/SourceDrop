package dev.sourcedrop.app.sourceadapters

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AppMetadataFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: AppMetadataFetcher

    /** Path (without query) -> response; anything else is a 404. */
    private val routes = mutableMapOf<String, () -> MockResponse>()

    private val gradle = """
        android {
            defaultConfig {
                applicationId "com.example.sotto"
            }
        }
    """.trimIndent()

    private val strings = """<resources><string name="app_name">Sotto</string></resources>"""

    private val releases = """
        [
          {"tag_name": "v0.7.10", "draft": false, "prerelease": false, "body": "notes",
           "assets": [{"name": "app-release.apk", "browser_download_url": "https://example.com/a.apk"}]},
          {"tag_name": "v0.7.9", "draft": false, "prerelease": false, "body": "", "assets": []}
        ]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path!!.substringBefore('?')]?.invoke() ?: MockResponse().setResponseCode(404)
        }
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // so requestCount shows exactly what was tried
            .build()
        fetcher = AppMetadataFetcher(client, githubApiBase = server.url("").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(body: String) = { MockResponse().setBody(body) }
    private fun status(code: Int) = { MockResponse().setResponseCode(code) }

    private suspend fun expectError(): AdapterError {
        try {
            fetcher.fetchFromGitHub("owner", "repo")
        } catch (e: AdapterError) {
            return e
        }
        fail("expected an AdapterError")
        throw AssertionError()
    }

    @Test
    fun `everything found`() = runTest {
        routes["/repos/owner/repo/contents/app/build.gradle.kts"] = ok(gradle)
        routes["/repos/owner/repo/contents/app/src/main/res/values/strings.xml"] = ok(strings)
        routes["/repos/owner/repo/releases"] = ok(releases)

        val metadata = fetcher.fetchFromGitHub("owner", "repo")

        assertEquals("com.example.sotto", metadata.packageName)
        assertEquals("Sotto", metadata.displayName)
        assertEquals(listOf("0.7.10", "0.7.9"), metadata.versions.map { it.version })
        assertEquals("https://example.com/a.apk", metadata.versions[0].apkUrl)
    }

    @Test
    fun `build file at a later path, earlier ones 404`() = runTest {
        routes["/repos/owner/repo/contents/build.gradle"] = ok(gradle)
        routes["/repos/owner/repo/releases"] = ok(releases)

        val metadata = fetcher.fetchFromGitHub("owner", "repo")

        assertEquals("com.example.sotto", metadata.packageName)
        assertEquals("Repo", metadata.displayName) // no strings.xml: falls back to the repo name
    }

    @Test
    fun `no build file anywhere and no releases is not an error`() = runTest {
        routes["/repos/owner/repo/releases"] = ok("[]")

        val metadata = fetcher.fetchFromGitHub("owner", "repo")

        assertNull(metadata.packageName)
        assertTrue(metadata.versions.isEmpty())
    }

    @Test
    fun `server error on the build files fails when no package name was found`() = runTest {
        routes["/repos/owner/repo/contents/app/build.gradle.kts"] = status(502)
        routes["/repos/owner/repo/releases"] = ok(releases)

        val error = expectError()

        assertTrue(error is AdapterError.NetworkError)
        assertTrue(error.message, error.message.contains("HTTP 502"))
        assertTrue(error.message, error.message.contains("try again"))
    }

    @Test
    fun `server error on one build file doesn't matter once another has the package name`() = runTest {
        routes["/repos/owner/repo/contents/app/build.gradle.kts"] = status(502)
        routes["/repos/owner/repo/contents/app/build.gradle"] = ok(gradle)
        routes["/repos/owner/repo/releases"] = ok(releases)

        assertEquals("com.example.sotto", fetcher.fetchFromGitHub("owner", "repo").packageName)
    }

    @Test
    fun `server error on releases fails`() = runTest {
        routes["/repos/owner/repo/contents/app/build.gradle.kts"] = ok(gradle)
        routes["/repos/owner/repo/releases"] = status(503)

        val error = expectError()

        assertTrue(error is AdapterError.NetworkError)
        assertTrue(error.message, error.message.contains("HTTP 503"))
    }

    @Test
    fun `missing repository says so`() = runTest {
        // Every path 404s, including releases.
        val error = expectError()

        assertTrue(error is AdapterError.NotFoundError)
        assertTrue(error.message, error.message.contains("owner/repo"))
    }

    @Test
    fun `no connection fails without trying every path`() = runTest {
        routes["/repos/owner/repo/contents/app/build.gradle.kts"] =
            // Policies checked before the request is read don't apply with a Dispatcher;
            // this one drops the connection once the request has arrived.
            { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }

        val error = expectError()

        assertTrue(error is AdapterError.NetworkError)
        assertTrue(error.message, error.message.startsWith("Couldn't reach GitHub"))
        assertEquals("stopped after the first failed connection", 1, server.requestCount)
    }

    @Test
    fun `rate limit is reported as such`() = runTest {
        routes["/repos/owner/repo/contents/app/build.gradle.kts"] = {
            MockResponse().setResponseCode(403)
                .setHeader("x-ratelimit-remaining", "0")
                .setHeader("x-ratelimit-reset", (System.currentTimeMillis() / 1000 + 600).toString())
        }

        assertTrue(expectError() is AdapterError.RateLimitError)
    }

    @Test
    fun `unreadable releases reply fails`() = runTest {
        routes["/repos/owner/repo/contents/app/build.gradle.kts"] = ok(gradle)
        routes["/repos/owner/repo/releases"] = ok("<html>maintenance</html>")

        val error = expectError()

        assertTrue(error is AdapterError.ParseError)
        assertTrue(error.message, error.message.contains("try again"))
    }

    @Test
    fun `applicationId is read in every build file style`() {
        val cases = mapOf(
            "applicationId = \"com.example.kts\"" to "com.example.kts",
            "applicationId(\"com.example.call\")" to "com.example.call",
            "applicationId 'com.example.groovy'" to "com.example.groovy",
            "applicationId \"com.example.groovy2\"" to "com.example.groovy2",
            "applicationIdSuffix '.debug'\nnamespace 'com.example.ns'" to "com.example.ns",
            "namespace = \"com.example.ns2\"" to "com.example.ns2"
        )
        for ((content, expected) in cases) {
            assertEquals(content, expected, AppMetadataFetcher.extractApplicationId(content))
        }
        assertNull(AppMetadataFetcher.extractApplicationId("testApplicationId 'x'"))
    }
}
