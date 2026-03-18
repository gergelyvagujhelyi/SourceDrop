package dev.sourcedrop.app.sourceadapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlAdapterTest {

    @Test
    fun extractWithRegex_captureGroup() {
        val html = """<span class="version">v2.5.1</span>"""
        assertEquals("2.5.1", HtmlAdapter.extractWithRegex(html, "v([\\d.]+)"))
    }

    @Test
    fun extractWithRegex_fullMatch() {
        val html = """Version: 3.0.0-beta.1"""
        assertEquals(
            "3.0.0-beta.1",
            HtmlAdapter.extractWithRegex(html, "[\\d]+\\.[\\d]+\\.[\\d]+-[\\w.]+")
        )
    }

    @Test
    fun extractWithRegex_apkLink() {
        val html = """<a href="/downloads/app-release-2.0.apk">Download</a>"""
        assertEquals(
            "/downloads/app-release-2.0.apk",
            HtmlAdapter.extractWithRegex(html, """href="([^"]*\.apk)"""")
        )
    }

    @Test
    fun extractWithRegex_noMatch() {
        val html = """<p>No version info here</p>"""
        assertNull(HtmlAdapter.extractWithRegex(html, "v([\\d.]+)"))
    }

    @Test
    fun extractWithRegex_invalidRegex() {
        val html = """<p>Some content</p>"""
        assertNull(HtmlAdapter.extractWithRegex(html, "[invalid"))
    }

    @Test
    fun resolveRelativeUrl_basic() {
        val resolved = HtmlAdapter.resolveRelativeUrl(
            "https://example.com/releases/",
            "/downloads/app.apk"
        )
        assertEquals("https://example.com/downloads/app.apk", resolved)
    }

    @Test
    fun resolveRelativeUrl_relative() {
        val resolved = HtmlAdapter.resolveRelativeUrl(
            "https://example.com/releases/latest",
            "app.apk"
        )
        assertEquals("https://example.com/releases/app.apk", resolved)
    }

    @Test
    fun resolveRelativeUrl_alreadyAbsolute() {
        val resolved = HtmlAdapter.resolveRelativeUrl(
            "https://example.com",
            "https://cdn.example.com/app.apk"
        )
        assertEquals("https://cdn.example.com/app.apk", resolved)
    }

    @Test
    fun extractWithRegex_multipleMatches_returnsFirst() {
        val html = """
            <p>v1.0.0</p>
            <p>v2.0.0</p>
        """.trimIndent()
        assertEquals("1.0.0", HtmlAdapter.extractWithRegex(html, "v([\\d.]+)"))
    }
}
