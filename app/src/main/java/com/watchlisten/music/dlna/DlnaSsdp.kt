package com.watchlisten.music.dlna

import java.net.Inet4Address
import java.net.InetAddress
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Applies the selected Wi-Fi interface's actual prefix, rather than assuming /24. */
internal class DlnaPeerPolicy(private val address: Inet4Address, prefixLength: Int) {
    private val prefix = prefixLength.also { require(it in 1..30) }
    private val mask = -1 shl (32 - prefix)
    private val network = ipv4Number(address) and mask
    private val broadcast = network or mask.inv()

    fun allows(peer: InetAddress): Boolean {
        if (peer !is Inet4Address || peer.isAnyLocalAddress || peer.isLoopbackAddress ||
            peer.isLinkLocalAddress || peer.isMulticastAddress) return false
        val number = ipv4Number(peer)
        return number and mask == network && number != network && number != broadcast
    }

    companion object {
        private fun ipv4Number(address: Inet4Address): Int = address.address.fold(0) { value, octet ->
            (value shl 8) or (octet.toInt() and 255)
        }
    }
}

internal data class DlnaSearch(val target: String, val mxSeconds: Int)

internal object DlnaSsdp {
    const val GROUP = "239.255.255.250"
    const val PORT = 1900
    const val MAX_AGE_SECONDS = 120
    private const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    fun uuid(identity: DlnaIdentity): String = "uuid:${UUID.fromString(identity.uuid.removePrefix("uuid:"))}"

    fun targets(identity: DlnaIdentity): List<String> =
        listOf("upnp:rootdevice", uuid(identity), RENDERER) + DlnaService.entries.map { it.urn }

    fun parseSearch(packet: String): DlnaSearch? {
        if (packet.length > 8192 || !packet.endsWith("\r\n\r\n")) return null
        val lines = packet.split("\r\n")
        if (!lines.first().equals("M-SEARCH * HTTP/1.1", ignoreCase = true)) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1).takeWhile { it.isNotEmpty() }) {
            val colon = line.indexOf(':')
            if (colon <= 0 || line.first().isWhitespace()) return null
            val name = line.substring(0, colon).lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            if (headers.put(name, value) != null || value.any { it.code < 32 || it.code == 127 }) return null
        }
        if (!headers["man"].equals("\"ssdp:discover\"", ignoreCase = true)) return null
        if (headers["host"] != "$GROUP:$PORT") return null
        val target = headers["st"]?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
        val mx = headers["mx"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return DlnaSearch(target, mx.coerceIn(1, 3).toInt())
    }

    fun matchingTargets(search: DlnaSearch, identity: DlnaIdentity): List<String> {
        val supported = targets(identity)
        return if (search.target.equals("ssdp:all", ignoreCase = true)) supported
        else supported.filter { it.equals(search.target, ignoreCase = true) }
    }

    fun response(identity: DlnaIdentity, location: String, target: String): String = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\n")
        append("DATE: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC))}\r\n")
        append("EXT:\r\nLOCATION: $location\r\nSERVER: ${server(identity)}\r\n")
        append("ST: $target\r\nUSN: ${usn(identity, target)}\r\n\r\n")
    }

    fun notification(identity: DlnaIdentity, location: String, target: String, alive: Boolean): String = buildString {
        append("NOTIFY * HTTP/1.1\r\nHOST: $GROUP:$PORT\r\n")
        if (alive) {
            append("CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\n")
            append("LOCATION: $location\r\nSERVER: ${server(identity)}\r\n")
        }
        append("NT: $target\r\nNTS: ssdp:${if (alive) "alive" else "byebye"}\r\n")
        append("USN: ${usn(identity, target)}\r\n\r\n")
    }

    fun server(identity: DlnaIdentity): String {
        val version = identity.version.filter { it.isLetterOrDigit() || it in ".-_" }.take(32).ifEmpty { "1.0" }
        return "Android/11 UPnP/1.0 WatchListen/$version"
    }

    private fun usn(identity: DlnaIdentity, target: String): String =
        if (target == uuid(identity)) target else "${uuid(identity)}::$target"
}
