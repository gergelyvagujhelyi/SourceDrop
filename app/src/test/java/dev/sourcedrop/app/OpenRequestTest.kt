package dev.sourcedrop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRequestTest {

    @Test
    fun packageAndVersion() {
        assertEquals(
            OpenRequest.ByPackage("com.vagujhelyigergely.sotto", "0.8.0"),
            OpenRequest.parse("com.vagujhelyigergely.sotto", "0.8.0", -1L)
        )
    }

    @Test
    fun packageWithoutVersion() {
        assertEquals(
            OpenRequest.ByPackage("com.example.app", null),
            OpenRequest.parse("com.example.app", null, -1L)
        )
    }

    @Test
    fun versionIsTrimmed() {
        assertEquals(
            OpenRequest.ByPackage("com.example.app", "v1.2.3-beta+4"),
            OpenRequest.parse("com.example.app", "  v1.2.3-beta+4 ", -1L)
        )
    }

    @Test
    fun invalidVersionIsDroppedButPackageKept() {
        val expected = OpenRequest.ByPackage("com.example.app", null)
        assertEquals(expected, OpenRequest.parse("com.example.app", "", -1L))
        assertEquals(expected, OpenRequest.parse("com.example.app", "1.0 <b>", -1L))
        assertEquals(expected, OpenRequest.parse("com.example.app", "1.0\n2.0", -1L))
        assertEquals(expected, OpenRequest.parse("com.example.app", "1".repeat(65), -1L))
    }

    @Test
    fun invalidPackageNameIsRejected() {
        assertNull(OpenRequest.parse("", "1.0", -1L))
        assertNull(OpenRequest.parse("noDots", "1.0", -1L))
        assertNull(OpenRequest.parse("1com.example", "1.0", -1L))
        assertNull(OpenRequest.parse("com..example", "1.0", -1L))
        assertNull(OpenRequest.parse("com.example/../x", "1.0", -1L))
        assertNull(OpenRequest.parse("com." + "a".repeat(252), "1.0", -1L))
    }

    @Test
    fun invalidPackageNameDoesNotFallBackToAppId() {
        // An app that sent a package name meant that app; don't open another one instead.
        assertNull(OpenRequest.parse("not a package", null, 7L))
    }

    @Test
    fun appIdFromOwnNotification() {
        assertEquals(OpenRequest.ByAppId(42L), OpenRequest.parse(null, null, 42L))
    }

    @Test
    fun packageTakesPrecedenceOverAppId() {
        assertEquals(
            OpenRequest.ByPackage("com.example.app", null),
            OpenRequest.parse("com.example.app", null, 42L)
        )
    }

    @Test
    fun nothingToOpen() {
        assertNull(OpenRequest.parse(null, null, -1L))
        assertNull(OpenRequest.parse(null, "1.0", 0L))
    }
}
