package com.watchlisten.music.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

internal fun safeLocalExportName(title: String, extension: String): String {
    val safe = title.map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }
        .joinToString("").trim().trim('.').take(120).ifBlank { "音乐" }
    return safe + "." + localAudioExtension("audio.$extension")
}

internal fun localAudioMime(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "wma" -> "audio/x-ms-wma"
    "aiff" -> "audio/aiff"
    "alac" -> "audio/alac"
    else -> "application/octet-stream"
}

fun localStorageSize(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    val (divisor, unit) = when {
        value >= 1024L * 1024 * 1024 -> 1024.0 * 1024 * 1024 to "GiB"
        value >= 1024L * 1024 -> 1024.0 * 1024 to "MiB"
        value >= 1024L -> 1024.0 to "KiB"
        else -> 1.0 to "B"
    }
    return if (unit == "B") "$value B" else String.format(Locale.ROOT, "%.1f %s", value / divisor, unit)
}

internal suspend fun copyLocalAudio(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}
