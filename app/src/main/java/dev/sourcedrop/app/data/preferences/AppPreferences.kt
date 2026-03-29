package dev.sourcedrop.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
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
        const val DEFAULT_CHECK_INTERVAL_HOURS = 12
    }
}
