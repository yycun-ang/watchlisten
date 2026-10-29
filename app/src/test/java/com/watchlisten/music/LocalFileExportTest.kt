package com.watchlisten.music

import com.watchlisten.music.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LocalFileExportTest {
    @Test fun 导出名称拒绝路径字符并保留真实受控后缀() {
        val name = safeLocalExportName("../夜曲\\测试:*?\u0000", "FLAC")
        assertTrue(name.endsWith(".flac"))
        assertFalse(name.any { it in "/\\:*?\"<>|" || it.isISOControl() })
        assertEquals("音乐.wav", safeLocalExportName("...", "wav"))
        assertTrue(safeLocalExportName("文件", "../../secret").endsWith(".audio"))
    }

    @Test fun 音频导出完整复制原始字节不转码() = runBlocking {
        val bytes = ByteArray(300_000) { ((it * 31 + 7) % 256).toByte() }
        val output = ByteArrayOutputStream()
        copyLocalAudio(ByteArrayInputStream(bytes), output)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun 已取消导出不能继续写入目标() = runBlocking {
        val output = ByteArrayOutputStream()
        val job = launch(start = CoroutineStart.LAZY) { copyLocalAudio(ByteArrayInputStream(byteArrayOf(1)), output) }
        job.cancel(); job.start(); job.join()
        assertEquals(0, output.size())
    }

    @Test fun 文件大小明确使用二进制单位且异常负值不会显示负空间() {
        assertEquals("0 B", localStorageSize(-1))
        assertEquals("1023 B", localStorageSize(1023))
        assertEquals("1.0 KiB", localStorageSize(1024))
        assertEquals("1.5 MiB", localStorageSize(1572864))
        assertEquals("1.0 GiB", localStorageSize(1073741824))
    }
}
