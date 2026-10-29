package com.watchlisten.music.data

import com.watchlisten.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import watchmusic.lyrics.LyricsDocument
import watchmusic.lyrics.LyricsParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class LocalLyricsFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 只关联用户明确选定的本地曲目；文件名不参与猜配。 */
class LocalLyricsRepository(private val directory: File) {
    private val mutex = Mutex()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    suspend fun import(track: Track, input: InputStream): LyricsDocument = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = fileFor(track) ?: throw LocalLyricsFailure("仅支持为已导入音乐关联歌词")
            val parsed = parseLocalLyrics(readLocalLyricsBytes(input))
            currentCoroutineContext().ensureActive()
            directory.mkdirs()
            val temporary = File(directory, ".${UUID.randomUUID()}.part")
            try {
                temporary.outputStream().use { output ->
                    output.write(parsed.text.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
                mutableRevision.value++
                parsed.document
            } finally { temporary.delete() }
        }
    }

    suspend fun load(track: Track): LyricsDocument? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = fileFor(track)?.takeIf(File::isFile) ?: return@withContext null
            file.inputStream().use { parseLocalLyrics(readLocalLyricsBytes(it)).document }
        }
    }

    fun contains(track: Track): Boolean = fileFor(track)?.isFile == true
    fun sizeBytes(track: Track): Long = fileFor(track)?.takeIf(File::isFile)?.length() ?: 0

    suspend fun remove(track: Track) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = fileFor(track) ?: return@withContext
            if (file.exists() && !file.delete()) throw LocalLyricsFailure("歌词文件未能删除")
            mutableRevision.value++
        }
    }

    private fun fileFor(track: Track): File? {
        if (!track.isLocal || !track.hash.matches(Regex("local-[a-fA-F0-9]{64}"))) return null
        return File(directory, track.hash.lowercase() + ".lrc")
    }
}

internal data class ParsedLocalLyrics(val text: String, val document: LyricsDocument)

internal fun readLocalLyricsBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, LyricsParser.MAX_BYTES - output.size() + 1))
        if (count < 0) return output.toByteArray()
        if (output.size() + count > LyricsParser.MAX_BYTES) throw LocalLyricsFailure("歌词文件超过 1 MB")
        output.write(buffer, 0, count)
    }
}

/** 有 BOM 时按声明解码；其余先严格 UTF-8，失败再严格 GB18030。 */
internal fun parseLocalLyrics(bytes: ByteArray): ParsedLocalLyrics {
    if (bytes.isEmpty() || bytes.size > LyricsParser.MAX_BYTES) throw LocalLyricsFailure("歌词文件为空或过大")
    val text = decodeLocalLyrics(bytes).removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    if (text.any { it.isISOControl() && it != '\n' && it != '\t' }) throw LocalLyricsFailure("文件包含无法识别的文本")
    val parser = LyricsParser()
    val document = try { parser.parseLrc(text) }
    catch (error: LyricsParser.ParseException) {
        if (error.failure != LyricsParser.Failure.NO_TIMED_LINES) throw LocalLyricsFailure("歌词时间或长度不正确", error)
        try { parser.parseText(text) }
        catch (plainError: LyricsParser.ParseException) { throw LocalLyricsFailure("文件中没有可用歌词", plainError) }
    }
    return ParsedLocalLyrics(text, document)
}

private fun decodeLocalLyrics(bytes: ByteArray): String {
    fun decode(charset: Charset, offset: Int = 0): String = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    val prefix = bytes.take(3).map { it.toInt() and 255 }
    return try {
        when {
            prefix.take(2) == listOf(255, 254) -> decode(Charsets.UTF_16LE, 2)
            prefix.take(2) == listOf(254, 255) -> decode(Charsets.UTF_16BE, 2)
            prefix == listOf(239, 187, 191) -> decode(Charsets.UTF_8, 3)
            else -> runCatching { decode(Charsets.UTF_8) }.getOrElse { decode(Charset.forName("GB18030")) }
        }
    } catch (error: Exception) { throw LocalLyricsFailure("歌词编码暂不支持，请使用 UTF-8、UTF-16 或 GB18030 文本", error) }
}
