package dev.sourcedrop.app.util

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Executes user-supplied regex patterns with input size limits and a hard timeout
 * to prevent ReDoS (Regular Expression Denial of Service) attacks.
 */
object SafeRegex {

    private const val MAX_INPUT_LENGTH = 512 * 1024 // 512 KB
    private const val TIMEOUT_MS = 2_000L

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true }
    }

    /**
     * Finds the first match of [pattern] in [input] with size and time limits.
     * Returns null if the pattern is invalid, the input is too large, or the match times out.
     */
    fun find(
        pattern: String,
        input: String,
        options: Set<RegexOption> = emptySet()
    ): MatchResult? {
        val safeInput = input.take(MAX_INPUT_LENGTH)
        val future = executor.submit<MatchResult?> {
            try {
                Regex(pattern, options).find(safeInput)
            } catch (_: Exception) {
                null
            }
        }
        return try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks if [pattern] has a match in [input] with size and time limits.
     * Returns false on timeout, invalid pattern, or oversized input.
     */
    fun containsMatch(
        pattern: String,
        input: String,
        options: Set<RegexOption> = emptySet()
    ): Boolean {
        val safeInput = input.take(MAX_INPUT_LENGTH)
        val future = executor.submit<Boolean> {
            try {
                Regex(pattern, options).containsMatchIn(safeInput)
            } catch (_: Exception) {
                false
            }
        }
        return try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            false
        } catch (_: Exception) {
            false
        }
    }
}
