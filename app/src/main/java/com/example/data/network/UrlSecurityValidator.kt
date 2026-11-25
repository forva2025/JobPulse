package com.example.data.network

import java.net.InetAddress
import java.net.URI
import java.util.Locale

object UrlSecurityValidator {

    private val blockedHostnames = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback",
        "metadata.google.internal",
        "metadata"
    )

    fun normalizeAndValidateSourceUrl(rawInput: String): Result<String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Website URL cannot be empty."))
        }

        val normalized = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> {
                return Result.failure(
                    IllegalArgumentException("Only secure HTTPS job website URLs are supported.")
                )
            }
            !trimmed.contains("://") -> "https://$trimmed"
            else -> {
                return Result.failure(
                    IllegalArgumentException("Unsupported URL scheme. Use https://.")
                )
            }
        }

        if (normalized.length > 500) {
            return Result.failure(IllegalArgumentException("URL exceeds maximum length of 500 characters."))
        }

        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("Invalid URL syntax."))
        }

        val host = uri.host?.lowercase(Locale.US)
            ?: return Result.failure(IllegalArgumentException("URL must include a valid domain name."))

        if (!isPublicHostSafe(host)) {
            return Result.failure(
                IllegalArgumentException("Private, loopback, or local network addresses are not permitted.")
            )
        }

        return Result.success(normalized)
    }

    fun isSafeExternalJobUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.length < 10 || trimmed.length > 1000) return false
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return isPublicHostSafe(host)
    }

    fun isPublicHostSafe(host: String): Boolean {
        val cleanHost = host.trim().lowercase(Locale.US).trimEnd('.')
        if (cleanHost.isEmpty() || !cleanHost.contains('.')) return false
        if (cleanHost in blockedHostnames) return false
        if (cleanHost.endsWith(".local") || cleanHost.endsWith(".internal") || cleanHost.endsWith(".localhost")) {
            return false
        }

        // Reject literal private/loopback/link-local IPv4 & IPv6 addresses
        if (isLiteralIpAddress(cleanHost)) {
            return try {
                val addr = InetAddress.getByName(cleanHost)
                !isPrivateOrRestrictedAddress(addr)
            } catch (_: Exception) {
                false
            }
        }
        return true
    }

    fun isResolvedAddressSafe(address: InetAddress): Boolean =
        !isPrivateOrRestrictedAddress(address)

    private fun isLiteralIpAddress(host: String): Boolean {
        val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4Regex.matches(host) || host.contains(':')
    }

    private fun isPrivateOrRestrictedAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val bytes = addr.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 100.64.0.0/10 carrier-grade NAT & 169.254.0.0/16 metadata
            if (b0 == 100 && b1 in 64..127) return true
            if (b0 == 169 && b1 == 254) return true
            if (b0 == 0 || b0 == 127 || b0 == 10) return true
            if (b0 == 172 && b1 in 16..31) return true
            if (b0 == 192 && b1 == 168) return true
        }
        return false
    }
}
