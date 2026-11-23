package dev.sourcedrop.app

import android.content.Intent
import dev.sourcedrop.app.notifications.NotificationHelper

/**
 * A request, carried by the intent that opened SourceDrop, to show one tracked app.
 *
 * Other apps that update through SourceDrop (e.g. Sotto) can add [EXTRA_PACKAGE_NAME] and,
 * optionally, [EXTRA_VERSION] to the intent they launch SourceDrop with. SourceDrop then opens
 * that app's page and checks its source right away, so the new version shows up without a
 * manual refresh.
 *
 * The version is only a hint, used for the progress text and to explain when the source
 * doesn't list it yet. It never decides what gets downloaded: that always comes from the
 * source the user configured.
 */
sealed interface OpenRequest {

    /** From SourceDrop's own update notification; the data is fresh, no check needed. */
    data class ByAppId(val appId: Long) : OpenRequest

    /** From another app: show [packageName] and check it, expecting [announcedVersion]. */
    data class ByPackage(val packageName: String, val announcedVersion: String?) : OpenRequest

    companion object {
        const val EXTRA_PACKAGE_NAME = "dev.sourcedrop.app.extra.PACKAGE_NAME"
        const val EXTRA_VERSION = "dev.sourcedrop.app.extra.VERSION"

        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val VERSION = Regex("[0-9A-Za-z._+\\-]{1,64}")

        fun from(intent: Intent?): OpenRequest? {
            if (intent == null) return null
            return parse(
                packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME),
                version = intent.getStringExtra(EXTRA_VERSION),
                appId = intent.getLongExtra(NotificationHelper.EXTRA_APP_ID, -1L)
            )
        }

        /** [from] without the [Intent], so it can be unit tested. */
        internal fun parse(packageName: String?, version: String?, appId: Long): OpenRequest? {
            if (packageName != null) {
                if (packageName.length > 255 || !PACKAGE_NAME.matches(packageName)) return null
                return ByPackage(packageName, version?.trim()?.takeIf { VERSION.matches(it) })
            }
            if (appId > 0) return ByAppId(appId)
            return null
        }
    }
}
