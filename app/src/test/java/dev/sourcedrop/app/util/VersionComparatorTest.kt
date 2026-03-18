package dev.sourcedrop.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun equalVersions() {
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"))
    }

    @Test
    fun newerMajor() {
        assertTrue(VersionComparator.compare("2.0.0", "1.0.0") > 0)
    }

    @Test
    fun olderMajor() {
        assertTrue(VersionComparator.compare("1.0.0", "2.0.0") < 0)
    }

    @Test
    fun newerMinor() {
        assertTrue(VersionComparator.compare("1.2.0", "1.1.0") > 0)
    }

    @Test
    fun newerPatch() {
        assertTrue(VersionComparator.compare("1.0.2", "1.0.1") > 0)
    }

    @Test
    fun numericComparisonNotLexicographic() {
        assertTrue(VersionComparator.compare("1.0.10", "1.0.9") > 0)
    }

    @Test
    fun preReleaseIsOlderThanRelease() {
        assertTrue(VersionComparator.compare("1.0.0-alpha", "1.0.0") < 0)
    }

    @Test
    fun releaseIsNewerThanPreRelease() {
        assertTrue(VersionComparator.compare("1.0.0", "1.0.0-beta") > 0)
    }

    @Test
    fun preReleaseOrdering() {
        assertTrue(VersionComparator.compare("1.0.0-alpha", "1.0.0-beta") < 0)
    }

    @Test
    fun preReleaseWithNumbers() {
        assertTrue(VersionComparator.compare("1.0.0-alpha.2", "1.0.0-alpha.1") > 0)
    }

    @Test
    fun prefixedVIsStripped() {
        assertEquals(0, VersionComparator.compare("v1.0.0", "1.0.0"))
    }

    @Test
    fun uppercaseVIsStripped() {
        assertEquals(0, VersionComparator.compare("V1.0.0", "1.0.0"))
    }

    @Test
    fun unequalSegmentCounts() {
        assertTrue(VersionComparator.compare("1.0.0.1", "1.0.0") > 0)
    }

    @Test
    fun shorterVersionTreatedAsZeroFilled() {
        assertEquals(0, VersionComparator.compare("1.0", "1.0.0"))
    }

    @Test
    fun isNewer_returnsTrueWhenLatestIsHigher() {
        assertTrue(VersionComparator.isNewer("1.0.0", "1.1.0"))
    }

    @Test
    fun isNewer_returnsFalseWhenEqual() {
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun isNewer_returnsFalseWhenCurrentIsHigher() {
        assertFalse(VersionComparator.isNewer("2.0.0", "1.0.0"))
    }

    @Test
    fun twoSegmentVersions() {
        assertTrue(VersionComparator.compare("1.2", "1.1") > 0)
    }

    @Test
    fun singleSegmentVersions() {
        assertTrue(VersionComparator.compare("2", "1") > 0)
    }

    @Test
    fun whitespaceIsTrimmed() {
        assertEquals(0, VersionComparator.compare(" 1.0.0 ", "1.0.0"))
    }

    @Test
    fun rcPreRelease() {
        assertTrue(VersionComparator.compare("1.0.0-rc.1", "1.0.0-beta.1") > 0)
    }
}
