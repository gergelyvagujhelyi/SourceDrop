package dev.sourcedrop.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dev.sourcedrop.app.updates.GitHubRateLimit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()

    var globalCheckIntervalHours: Int
        get() = prefs.getInt(KEY_CHECK_INTERVAL, DEFAULT_CHECK_INTERVAL_HOURS)
        set(value) = prefs.edit().putInt(KEY_CHECK_INTERVAL, value.coerceIn(1, 168)).apply()

    var backgroundChecksEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_CHECKS, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_CHECKS, value).apply()

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var githubApiToken: String
        get() = prefs.getString(KEY_GITHUB_API_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_API_TOKEN, value.trim()).apply()

    /** Where [GitHubRateLimit] keeps what it learned, so the worker and a restart see it too. */
    val githubRateLimitStore: GitHubRateLimit.Store = object : GitHubRateLimit.Store {
        override fun load(): GitHubRateLimit.State? {
            if (!prefs.contains(KEY_GH_TOKEN_KEY)) return null
            return GitHubRateLimit.State(
                tokenKey = prefs.getInt(KEY_GH_TOKEN_KEY, 0),
                remaining = prefs.getInt(KEY_GH_REMAINING, -1),
                resetAt = prefs.getLong(KEY_GH_RESET_AT, 0),
                blockedUntil = prefs.getLong(KEY_GH_BLOCKED_UNTIL, 0)
            )
        }

        override fun save(state: GitHubRateLimit.State) {
            prefs.edit()
                .putInt(KEY_GH_TOKEN_KEY, state.tokenKey)
                .putInt(KEY_GH_REMAINING, state.remaining)
                .putLong(KEY_GH_RESET_AT, state.resetAt)
                .putLong(KEY_GH_BLOCKED_UNTIL, state.blockedUntil)
                .apply()
        }
    }

    fun observeChanges(): Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) trySend(key)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val PREFS_NAME = "sourcedrop_prefs"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_CHECK_INTERVAL = "check_interval_hours"
        private const val KEY_BACKGROUND_CHECKS = "background_checks_enabled"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_GITHUB_API_TOKEN = "github_api_token"
        private const val KEY_GH_TOKEN_KEY = "github_rate_limit_token_key"
        private const val KEY_GH_REMAINING = "github_rate_limit_remaining"
        private const val KEY_GH_RESET_AT = "github_rate_limit_reset_at"
        private const val KEY_GH_BLOCKED_UNTIL = "github_rate_limit_blocked_until"
        const val DEFAULT_CHECK_INTERVAL_HOURS = 12
    }
}
