package dev.sourcedrop.app.util

import java.net.URI

/**
 * Validates URLs to prevent SSRF (Server-Side Request Forgery) by blocking
 * requests to private networks, localhost, and cloud metadata endpoints.
 */
object UrlValidator {

    /**
     * Throws [IllegalArgumentException] if the URL host resolves to a
     * private, loopback, link-local, or otherwise blocked address.
     */
    fun validateHost(url: String) {
        val host = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            throw IllegalArgumentException("Malformed URL")
        } ?: throw IllegalArgumentException("No host in URL")

        if (isBlockedHost(host)) {
            throw IllegalArgumentException("URL points to a blocked address")
        }
    }

    private fun isBlockedHost(host: String): Boolean {
        // Localhost variants
        if (host == "localhost" || host.endsWith(".localhost") || host == "[::1]") return true

        // Local and internal domains
        if (host.endsWith(".local") || host.endsWith(".internal")) return true

        // Cloud metadata endpoints
        if (host == "169.254.169.254") return true

        return isPrivateIp(host)
    }

    private fun isPrivateIp(host: String): Boolean {
        // 127.x.x.x (loopback)
        if (host.startsWith("127.")) return true
        // 10.x.x.x (private class A)
        if (host.startsWith("10.")) return true
        // 172.16-31.x.x (private class B)
        if (host.startsWith("172.")) {
            val second = host.removePrefix("172.").substringBefore(".").toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        // 192.168.x.x (private class C)
        if (host.startsWith("192.168.")) return true
        // 169.254.x.x (link-local)
        if (host.startsWith("169.254.")) return true
        // 0.0.0.0
        if (host == "0.0.0.0") return true
        // IPv6 private/reserved
        if (host.startsWith("[")) {
            val ipv6 = host.removeSurrounding("[", "]").lowercase()
            if (ipv6 == "::1" || ipv6 == "::" ||
                ipv6.startsWith("fe80:") ||
                ipv6.startsWith("fc") || ipv6.startsWith("fd")
            ) return true
        }
        return false
    }
}
