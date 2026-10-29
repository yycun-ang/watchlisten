package com.watchlisten.music.dlna

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** A cast controller may serve its own audio or hand over a public Kugou media URL. */
object RemoteUrlPolicy {
    private const val MAX_URL_LENGTH = 8192
    private val mediaDomains = listOf("kugou.com", "kgmusic.com", "kugou.net", "kgimg.com")
    private val hostLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    @Throws(IOException::class)
    fun validate(raw: String, peer: String): URI {
        controllerAddress(peer)
        if (raw.isEmpty() || raw.length > MAX_URL_LENGTH || raw.any { it <= ' ' || it == '\\' || it == '\u007f' }) reject()
        val uri = try { URI(raw) } catch (_: Exception) { reject() }
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") reject()
        if (uri.isOpaque || uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0) reject()
        val host = uri.host?.lowercase(Locale.US) ?: reject()
        val authority = uri.rawAuthority?.lowercase(Locale.US) ?: reject()
        if (authority != host && (!authority.startsWith("$host:") ||
            authority.substring(host.length + 1).isEmpty() || uri.port == -1)) reject()
        if (host != peer && !isMediaHost(host)) reject()
        if (uri.toASCIIString().length > MAX_URL_LENGTH) reject()
        return uri
    }

    /** Validate every DNS answer before attempting any connection, so DNS cannot expand the allowlist. */
    @Throws(IOException::class)
    fun validateAddress(uri: URI, address: InetAddress, peer: String) {
        val checked = validate(uri.toASCIIString(), peer)
        if (checked.host == peer) {
            if (!address.address.contentEquals(controllerAddress(peer).address)) reject()
            return
        }
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) reject()
        when (address) {
            is Inet4Address -> if (!isPublicIpv4(address.address)) reject()
            is Inet6Address -> if (!isPublicIpv6(address)) reject()
            else -> reject()
        }
    }

    /** Strict numeric parsing avoids DNS, octal notation, shortened addresses and IPv4-mapped aliases. */
    @Throws(IOException::class)
    fun controllerAddress(peer: String): InetAddress {
        val parts = peer.split('.')
        if (parts.size != 4) reject()
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                (part.length > 1 && part.first() == '0')) reject()
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: reject()
        }
        val privateAddress = octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
        if (!privateAddress) reject()
        return InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() })
    }

    private fun isMediaHost(host: String): Boolean {
        if (host.length > 253 || !host.split('.').all { hostLabel.matches(it) }) return false
        return mediaDomains.any { host == it || host.endsWith(".$it") }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 255
        val b = bytes[1].toInt() and 255
        val c = bytes[2].toInt() and 255
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false
        if (a == 100 && b in 64..127) return false
        if (a == 169 && b == 254 || a == 172 && b in 16..31 || a == 192 && b == 168) return false
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false
        if (a == 192 && b == 88 && c == 99) return false
        if (a == 198 && b in 18..19 || a == 198 && b == 51 && c == 100) return false
        if (a == 203 && b == 0 && c == 113) return false
        return true
    }

    private fun isPublicIpv6(address: Inet6Address): Boolean {
        val bytes = address.address.map { it.toInt() and 255 }
        if (address.scopeId != 0 || bytes[0] and 0xe0 != 0x20) return false
        // Documentation and transition ranges are not public audio origins.
        if (bytes[0] == 0x20 && bytes[1] == 0x02) return false
        if (bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)) return false
        if (bytes.take(4) == listOf(0x20, 0x01, 0x00, 0x00)) return false
        if (bytes.take(3) == listOf(0x20, 0x01, 0x00) && bytes[3] in 0x10..0x2f) return false
        if (bytes[0] == 0x3f && bytes[1] == 0xff) return false
        return true
    }

    private fun reject(): Nothing = throw IOException("投放媒体地址不在允许范围内")
}
