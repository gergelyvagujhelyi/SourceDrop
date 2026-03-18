package dev.sourcedrop.app.util

object VersionComparator {

    /**
     * Compares two version strings.
     * Returns negative if v1 < v2, zero if equal, positive if v1 > v2.
     *
     * Handles:
     * - "v" prefix (v1.2.3 -> 1.2.3)
     * - Numeric segment comparison (1.10 > 1.9)
     * - Pre-release suffixes (1.0.0-alpha < 1.0.0)
     * - Unequal segment counts (1.0.0.1 > 1.0.0)
     */
    fun compare(v1: String, v2: String): Int {
        val clean1 = normalize(v1)
        val clean2 = normalize(v2)

        val parts1 = splitVersion(clean1)
        val parts2 = splitVersion(clean2)

        val main1 = parts1.first
        val main2 = parts2.first
        val pre1 = parts1.second
        val pre2 = parts2.second

        // Compare main version segments
        val maxLen = maxOf(main1.size, main2.size)
        for (i in 0 until maxLen) {
            val seg1 = main1.getOrNull(i) ?: "0"
            val seg2 = main2.getOrNull(i) ?: "0"

            val num1 = seg1.toLongOrNull()
            val num2 = seg2.toLongOrNull()

            val cmp = if (num1 != null && num2 != null) {
                num1.compareTo(num2)
            } else {
                seg1.compareTo(seg2)
            }

            if (cmp != 0) return cmp
        }

        // Main versions are equal — compare pre-release
        // No pre-release is "greater" than having one (1.0.0 > 1.0.0-alpha)
        return when {
            pre1 == null && pre2 == null -> 0
            pre1 == null -> 1   // v1 is release, v2 is pre-release
            pre2 == null -> -1  // v1 is pre-release, v2 is release
            else -> comparePreRelease(pre1, pre2)
        }
    }

    fun isNewer(current: String, latest: String): Boolean {
        return compare(latest, current) > 0
    }

    private fun normalize(version: String): String {
        return version.trim().removePrefix("v").removePrefix("V")
    }

    /**
     * Splits "1.2.3-alpha.1" into (["1","2","3"], "alpha.1")
     */
    private fun splitVersion(version: String): Pair<List<String>, String?> {
        val hyphenIndex = version.indexOf('-')
        return if (hyphenIndex >= 0) {
            val main = version.substring(0, hyphenIndex)
            val pre = version.substring(hyphenIndex + 1)
            Pair(main.split("."), pre.ifEmpty { null })
        } else {
            Pair(version.split("."), null)
        }
    }

    private fun comparePreRelease(pre1: String, pre2: String): Int {
        val parts1 = pre1.split(".")
        val parts2 = pre2.split(".")

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val seg1 = parts1.getOrNull(i) ?: return -1
            val seg2 = parts2.getOrNull(i) ?: return 1

            val num1 = seg1.toLongOrNull()
            val num2 = seg2.toLongOrNull()

            val cmp = if (num1 != null && num2 != null) {
                num1.compareTo(num2)
            } else {
                seg1.compareTo(seg2)
            }

            if (cmp != 0) return cmp
        }
        return 0
    }
}
