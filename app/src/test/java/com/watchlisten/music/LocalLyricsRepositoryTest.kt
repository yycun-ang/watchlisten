package com.watchlisten.music

import com.watchlisten.music.data.*
import com.watchlisten.music.model.Track
import com.watchlisten.music.model.TrackOrigin
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import watchmusic.lyrics.LyricsDocument
import watchmusic.lyrics.LyricsParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalLyricsRepositoryTest {
    private val text = "[ar:自有音频]\n[offset:120]\n[00:01.20]晚风经过窗前\n[00:03.50]音乐随腕而行"
    private val track = Track("local-" + "a".repeat(64), "同名歌曲", "歌手", origin = TrackOrigin.LOCAL)

    @Test fun UTF8与BOM歌词保留时间和中文() {
        for (bytes in listOf(text.toByteArray(), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.toByteArray())) {
            val parsed = parseLocalLyrics(bytes).document
            assertEquals(LyricsDocument.Format.LRC, parsed.format)
            assertEquals(120L, parsed.offsetMs)
            assertEquals(1200L, parsed.lines.first().startMs)
            assertEquals("晚风经过窗前", parsed.lines.first().text)
        }
    }

    @Test fun UTF16大小端与GB18030都能正确解码() {
        val bytes = listOf(
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) + text.toByteArray(Charsets.UTF_16BE),
            text.toByteArray(Charset.forName("GB18030")))
        bytes.forEach { assertEquals("音乐随腕而行", parseLocalLyrics(it).document.lines.last().text) }
    }

    @Test fun 无时间文本自由展示而不是生成假时间() {
        val document = parseLocalLyrics("第一句\r\n第二句\r第三句".toByteArray()).document
        assertEquals(LyricsDocument.Format.TEXT, document.format)
        assertEquals(listOf("第一句", "第二句", "第三句"), document.lines.map { it.text })
        assertTrue(document.lines.all { it.startMs == -1L })
    }

    @Test fun 错误时间空文档二进制与过长行被拒绝() {
        for (value in listOf("[00:99.00]错误时间", "[ar:只有标签]", "", "文本\u0000字节", "行".repeat(4097))) {
            assertTrue(value.take(30), runCatching { parseLocalLyrics(value.toByteArray()) }.exceptionOrNull() is IOException)
        }
        assertTrue(runCatching { parseLocalLyrics(byteArrayOf(0xff.toByte())) }.exceptionOrNull() is IOException)
    }

    @Test fun 输入边界只读到上限加一便拒绝() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read() = if (consumed++ < LyricsParser.MAX_BYTES + 100) 'a'.code else -1
        }
        assertTrue(runCatching { readLocalLyricsBytes(stream) }.exceptionOrNull() is IOException)
        assertEquals(LyricsParser.MAX_BYTES + 1, consumed)
    }

    @Test fun 明确曲目标识关联且相同歌名不会串词() = inDirectory { root -> runBlocking {
        val store = LocalLyricsRepository(root)
        val other = track.copy(hash = "local-" + "b".repeat(64))
        store.import(track, ByteArrayInputStream(text.toByteArray()))
        assertNotNull(store.load(track))
        assertNull(store.load(other))
        assertFalse(store.contains(other))
        assertTrue(store.sizeBytes(track) > 0)
        assertTrue(runCatching { store.import(track.copy(origin = TrackOrigin.KUGOU), ByteArrayInputStream(text.toByteArray())) }.exceptionOrNull() is IOException)
    } }

    @Test fun 非法曲目标识不能通过路径关联任意文件() = inDirectory { root -> runBlocking {
        val store = LocalLyricsRepository(root)
        for (hash in listOf("../../outside", "local-../outside", "local-" + "x".repeat(64), "a".repeat(64))) {
            assertTrue(runCatching { store.import(track.copy(hash = hash), ByteArrayInputStream(text.toByteArray())) }.exceptionOrNull() is IOException)
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    } }

    @Test fun 无效替换不会破坏上次可用歌词和版本() = inDirectory { root -> runBlocking {
        val store = LocalLyricsRepository(root)
        store.import(track, ByteArrayInputStream(text.toByteArray()))
        val before = store.revision.value
        assertTrue(runCatching { store.import(track, ByteArrayInputStream("[00:66]无效".toByteArray())) }.exceptionOrNull() is IOException)
        assertEquals(before, store.revision.value)
        assertEquals("晚风经过窗前", store.load(track)!!.lines.first().text)
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    } }

    @Test fun 替换和重新创建仓储后仍读到完整关联歌词() = inDirectory { root -> runBlocking {
        val store = LocalLyricsRepository(root)
        store.import(track, ByteArrayInputStream(text.toByteArray()))
        store.import(track, ByteArrayInputStream("[00:00]替换后的歌词".toByteArray(Charsets.UTF_16)))
        assertEquals(2L, store.revision.value)
        assertEquals("替换后的歌词", LocalLyricsRepository(root).load(track)!!.lines.first().text)
        assertEquals(1, root.listFiles().orEmpty().size)
        store.remove(track)
        assertNull(store.load(track))
        assertEquals(0L, store.sizeBytes(track))
    } }

    @Test fun 并发导入不同歌曲不相互覆盖() = inDirectory { root -> runBlocking {
        val store = LocalLyricsRepository(root)
        val other = track.copy(hash = "local-" + "b".repeat(64))
        listOf(track, other).map { current -> async(Dispatchers.Default) {
            store.import(current, ByteArrayInputStream("[00:01]${current.hash.last()}".toByteArray()))
        } }.awaitAll()
        assertEquals("a", store.load(track)!!.lines.first().text)
        assertEquals("b", store.load(other)!!.lines.first().text)
        assertEquals(2L, store.revision.value)
    } }

    @Test fun 取消中的导入不会替换已保存歌词() = inDirectory { root -> runBlocking {
        val store = LocalLyricsRepository(root)
        store.import(track, ByteArrayInputStream(text.toByteArray()))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val input = object : ByteArrayInputStream("[00:01]不得覆盖".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
        val job = launch(Dispatchers.IO) { store.import(track, input) }
        assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
        job.cancel(); release.countDown(); job.join()
        assertTrue(job.isCancelled)
        assertEquals("晚风经过窗前", store.load(track)!!.lines.first().text)
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    } }

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("watch-local-lyrics").toFile()
        try { block(directory) } finally { directory.walkBottomUp().forEach { it.delete() } }
    }
}
