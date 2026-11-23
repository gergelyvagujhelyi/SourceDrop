package dev.sourcedrop.app.updates

import dev.sourcedrop.app.testutil.InMemoryRateLimitStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRateLimitTest {

    private var now = 1_000_000_000L // epoch ms
    private var token = ""
    private val store = InMemoryRateLimitStore()
    private val limit = GitHubRateLimit(store, { token }, { now })

    private fun headers(vararg pairs: Pair<String, Any>): (String) -> String? {
        val map = pairs.associate { (k, v) -> k to v.toString() }
        return { map[it] }
    }

    private val resetSeconds get() = (now + 30 * 60_000) / 1000

    @Test
    fun `nothing known allows automatic checks`() {
        assertTrue(limit.allowsAutomaticCheck())
        assertNull(limit.blockedUntil())
    }

    @Test
    fun `plenty left allows automatic checks`() {
        assertFalse(limit.record(200, headers("x-ratelimit-remaining" to 50, "x-ratelimit-reset" to resetSeconds)))
        assertTrue(limit.allowsAutomaticCheck())
    }

    @Test
    fun `down to the reserve pauses automatic checks until the reset`() {
        val reset = resetSeconds
        limit.record(200, headers("x-ratelimit-remaining" to GitHubRateLimit.RESERVE - 1, "x-ratelimit-reset" to reset))

        assertFalse(limit.allowsAutomaticCheck())
        assertNull("still allowed for the user's own checks", limit.blockedUntil())
        assertEquals(reset * 1000, limit.resetAt())

        now = reset * 1000 + 1
        assertTrue(limit.allowsAutomaticCheck())
    }

    @Test
    fun `exactly the reserve left still allows automatic checks`() {
        limit.record(200, headers("x-ratelimit-remaining" to GitHubRateLimit.RESERVE, "x-ratelimit-reset" to resetSeconds))
        assertTrue(limit.allowsAutomaticCheck())
    }

    @Test
    fun `403 with nothing left blocks until the reset`() {
        val reset = resetSeconds
        assertTrue(limit.record(403, headers("x-ratelimit-remaining" to 0, "x-ratelimit-reset" to reset)))

        assertEquals(reset * 1000, limit.blockedUntil())
        assertFalse(limit.allowsAutomaticCheck())

        now = reset * 1000
        assertNull(limit.blockedUntil())
    }

    @Test
    fun `429 with retry-after blocks for that long`() {
        assertTrue(limit.record(429, headers("retry-after" to 30)))
        assertEquals(now + 30_000, limit.blockedUntil())
    }

    @Test
    fun `429 without headers blocks for the fallback time`() {
        assertTrue(limit.record(429, headers()))
        assertEquals(now + GitHubRateLimit.FALLBACK_BLOCK_MS, limit.blockedUntil())
    }

    @Test
    fun `403 that isn't about the limit doesn't block`() {
        assertFalse(limit.record(403, headers("x-ratelimit-remaining" to 42, "x-ratelimit-reset" to resetSeconds)))
        assertNull(limit.blockedUntil())
        assertTrue(limit.allowsAutomaticCheck())
    }

    @Test
    fun `a later success doesn't lift an earlier block`() {
        val reset = resetSeconds
        limit.record(403, headers("x-ratelimit-remaining" to 0, "x-ratelimit-reset" to reset))
        limit.record(200, headers("x-ratelimit-remaining" to 0, "x-ratelimit-reset" to reset))
        assertEquals(reset * 1000, limit.blockedUntil())
    }

    @Test
    fun `state saved under another token is ignored`() {
        limit.record(403, headers("x-ratelimit-remaining" to 0, "x-ratelimit-reset" to resetSeconds))
        assertTrue(limit.blockedUntil() != null)

        token = "ghp_new"
        assertNull(limit.blockedUntil())
        assertTrue(limit.allowsAutomaticCheck())
    }

    @Test
    fun `state survives a new instance on the same store`() {
        val reset = resetSeconds
        limit.record(403, headers("x-ratelimit-remaining" to 0, "x-ratelimit-reset" to reset))

        val afterRestart = GitHubRateLimit(store, { token }, { now })
        assertEquals(reset * 1000, afterRestart.blockedUntil())
    }

    @Test
    fun `isRateLimited distinguishes limit refusals from other errors`() {
        assertTrue(GitHubRateLimit.isRateLimited(429, null, null))
        assertTrue(GitHubRateLimit.isRateLimited(403, 0, null))
        assertTrue(GitHubRateLimit.isRateLimited(403, null, 60_000))
        assertFalse(GitHubRateLimit.isRateLimited(403, null, null))
        assertFalse(GitHubRateLimit.isRateLimited(403, 5, null))
        assertFalse(GitHubRateLimit.isRateLimited(404, 0, null))
        assertFalse(GitHubRateLimit.isRateLimited(200, 0, null))
    }
}
