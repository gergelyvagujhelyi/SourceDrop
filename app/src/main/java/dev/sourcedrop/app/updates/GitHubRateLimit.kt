package dev.sourcedrop.app.updates

/**
 * What SourceDrop knows about its GitHub API budget, learned from the rate-limit headers on
 * every GitHub response. Without a token GitHub allows 60 calls an hour per IP address (5,000
 * with one), so once it refuses, nothing should call again until the reset time it gave, and
 * automatic checks leave the last [RESERVE] calls for things the user asks for.
 *
 * Kept in [Store] so the background worker and a restarted app know about it too. The budget
 * belongs to a token (or to having none), so state saved under another token is ignored.
 */
class GitHubRateLimit(
    private val store: Store,
    private val currentToken: () -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * @param remaining calls left until [resetAt], or -1 if unknown
     * @param resetAt when GitHub refills the budget (epoch ms)
     * @param blockedUntil GitHub refused a call; don't call again before this (epoch ms)
     */
    data class State(
        val tokenKey: Int = 0,
        val remaining: Int = -1,
        val resetAt: Long = 0,
        val blockedUntil: Long = 0
    )

    interface Store {
        fun load(): State?
        fun save(state: State)
    }

    /**
     * Records what a GitHub response says about the budget. Call it for every response,
     * successful or not. Returns true if this response is GitHub refusing because of a limit.
     */
    @Synchronized
    fun record(code: Int, header: (String) -> String?): Boolean {
        val now = clock()
        val remaining = header("x-ratelimit-remaining")?.trim()?.toIntOrNull()
        val resetAt = header("x-ratelimit-reset")?.trim()?.toLongOrNull()?.times(1000)
        val retryAfter = header("retry-after")?.trim()?.toLongOrNull()?.times(1000)

        var state = state()
        if (remaining != null && resetAt != null) {
            state = state.copy(remaining = remaining, resetAt = resetAt)
        }
        val limited = isRateLimited(code, remaining, retryAfter)
        if (limited) {
            val until = when {
                retryAfter != null -> now + retryAfter
                resetAt != null && resetAt > now -> resetAt
                else -> now + FALLBACK_BLOCK_MS
            }
            state = state.copy(blockedUntil = maxOf(state.blockedUntil, until))
        }
        store.save(state)
        return limited
    }

    /** When GitHub's refusal ends, or null if it isn't refusing. */
    @Synchronized
    fun blockedUntil(): Long? = state().blockedUntil.takeIf { it > clock() }

    /** When the budget refills, or null if unknown or already past. */
    @Synchronized
    fun resetAt(): Long? = state().resetAt.takeIf { it > clock() }

    /** Whether an automatic check may call GitHub: not refused, and not down to the reserve. */
    @Synchronized
    fun allowsAutomaticCheck(): Boolean {
        val state = state()
        val now = clock()
        if (state.blockedUntil > now) return false
        val budgetKnown = state.remaining >= 0 && state.resetAt > now
        return !(budgetKnown && state.remaining < RESERVE)
    }

    private fun state(): State {
        val key = currentToken().hashCode()
        val saved = store.load()
        return if (saved != null && saved.tokenKey == key) saved else State(tokenKey = key)
    }

    companion object {
        /** Calls automatic checks leave for the user's own actions. */
        const val RESERVE = 10

        /** How long to hold off when GitHub refuses without saying for how long. */
        const val FALLBACK_BLOCK_MS = 60_000L

        /**
         * A 429, or a 403 that says the budget is spent or asks to retry later. Other 403s
         * (e.g. a repository that's forbidden) aren't about the limit.
         */
        fun isRateLimited(code: Int, remaining: Int?, retryAfterMs: Long?): Boolean =
            code == 429 || (code == 403 && (remaining == 0 || retryAfterMs != null))
    }
}
