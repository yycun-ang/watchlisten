package com.watchlisten.music.data

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal fun selectCoverUrl(vararg candidates: String): String = candidates.firstNotNullOfOrNull {
    normalizeCoverUrl(it).takeIf(String::isNotEmpty)
}.orEmpty()

/** 兼容服务端尺寸占位符，以及旧收藏中仍使用 HTTP 的官方图片地址。 */
internal fun normalizeCoverUrl(raw: String): String {
    val value = raw.trim().replace("{size}", COVER_REQUEST_SIZE.toString())
    if (value.isEmpty()) return ""
    val uri = try { URI(value) } catch (_: URISyntaxException) { return "" }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return ""
    if (scheme !in listOf("http", "https") || uri.rawUserInfo != null || uri.port !in -1..65535) return ""

    val upgrade = scheme == "http" && isOfficialCoverHost(host)
    val authority = if (upgrade && uri.port == 80) uri.rawAuthority.removeSuffix(":80") else uri.rawAuthority
    return buildString {
        append(if (upgrade) "https" else scheme).append("://").append(authority)
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}

/** 只改写已核实的公开 CDN 尺寸段；保留原地址以便高分辨率来源失效时回退。 */
internal fun coverUrlCandidates(raw: String): List<String> {
    val normalized = normalizeCoverUrl(raw).takeIf(String::isNotEmpty) ?: return emptyList()
    val uri = URI(normalized)
    val host = uri.host.lowercase(Locale.ROOT).removeSuffix(".")
    if (!isOfficialCoverHost(host) || uri.rawQuery != null) return listOf(normalized)
    val match = sizedCoverPath.matchEntire(uri.rawPath.orEmpty()) ?: return listOf(normalized)
    val currentSize = match.groupValues[2].toIntOrNull() ?: return listOf(normalized)
    if (currentSize !in knownCoverSizes) return listOf(normalized)
    fun atSize(size: Int): String = buildString {
        append(uri.scheme).append("://").append(uri.rawAuthority)
        append(uri.rawPath.replaceRange(match.groups[2]!!.range, size.toString()))
        uri.rawFragment?.let { append('#').append(it) }
    }
    return when {
        currentSize < COVER_REQUEST_SIZE -> listOf(atSize(COVER_REQUEST_SIZE), normalized)
        currentSize == COVER_REQUEST_SIZE -> listOf(normalized, atSize(400))
        else -> listOf(normalized, atSize(COVER_REQUEST_SIZE), atSize(400))
    }.distinct()
}

private fun isOfficialCoverHost(host: String): Boolean =
    host == "kgimg.com" || host.endsWith(".kgimg.com") || host == "imge.kugou.com"

private const val COVER_REQUEST_SIZE = 800
private val knownCoverSizes = setOf(64, 100, 120, 150, 160, 200, 240, 320, 400, 480, 640, 800, 1024)
private val sizedCoverPath = Regex("^/(stdmusic|custom)/(\\d{2,4})/\\d{8}/[^/]+$")
