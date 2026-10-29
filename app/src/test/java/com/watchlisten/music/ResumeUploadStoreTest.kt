package com.watchlisten.music

import com.watchlisten.music.data.*
import com.watchlisten.music.model.Track
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class ResumeUploadStoreTest {
    @Test fun 中断只回滚当前块而此前确认块可继续且字节一致() = inDirectory { directory ->
        ResumeUploadStore(directory).use { store ->
            val task = store.start("音乐.flac", 6)
            store.append(task.id, 0, 3, "abc".byteInputStream())
            assertTrue(runCatching { store.append(task.id, 3, 3, "d".byteInputStream()) }.isFailure)
            assertEquals(3L, store.state(task.id).offset)
            store.append(task.id, 3, 3, "def".byteInputStream())
            assertArrayEquals("abcdef".toByteArray(), store.fileForCompletion(task.id).readBytes())
        }
    }

    @Test fun 错位置超大块和越过总长度都不读取请求体() = inDirectory { directory ->
        ResumeUploadStore(directory).use { store ->
            val task = store.start("a.wav", 6)
            val denied = object : InputStream() { override fun read(): Int = throw AssertionError("不得读取无效请求体") }
            assertEquals(409, failure { store.append(task.id, 1, 3, denied) }.status)
            assertEquals(413, failure { store.append(task.id, 0, ResumeUploadStore.MAX_CHUNK_BYTES + 1, denied) }.status)
            assertEquals(413, failure { store.append(task.id, 0, 7, denied) }.status)
            assertEquals(0L, store.state(task.id).offset)
        }
    }

    @Test fun 文件未完成不能进入导入阶段() = inDirectory { directory ->
        ResumeUploadStore(directory).use { store ->
            val task = store.start("a.mp3", 5)
            store.append(task.id, 0, 2, byteArrayOf(1, 2).inputStream())
            assertEquals(409, failure { store.fileForCompletion(task.id) }.status)
        }
    }

    @Test fun 完成结果幂等保留且回收临时文件() = inDirectory { directory ->
        ResumeUploadStore(directory).use { store ->
            val task = store.start("a.wav", 3)
            store.append(task.id, 0, 3, "abc".byteInputStream())
            val file = store.fileForCompletion(task.id)
            val track = Track("fixture", "音频", "")
            assertEquals(track, store.complete(task.id, track).completed)
            assertFalse(file.exists())
            assertEquals(track, store.complete(task.id, track.copy(title = "不能替换已完成结果")).completed)
            assertEquals(3L, store.state(task.id).offset)
        }
    }

    @Test fun 创建响应丢失时相同客户端任务不重复分配且不能换文件() = inDirectory { directory ->
        ResumeUploadStore(directory).use { store ->
            val key = "a".repeat(32)
            val first = store.start("a.wav", 3, key)
            assertEquals(first.id, store.start("a.wav", 3, key).id)
            assertEquals(1, directory.listFiles()!!.size)
            assertEquals(409, failure { store.start("b.wav", 3, key) }.status)
            assertEquals(409, failure { store.start("a.wav", 4, key) }.status)
        }
    }

    @Test fun 暂存容量按待传总长度约束而不预分配大文件() = inDirectory { directory ->
        ResumeUploadStore(directory).use { store ->
            store.start("large.flac", LocalTransferProtocol.MAX_FILE_BYTES)
            assertEquals(0L, directory.listFiles()!!.single().length())
            assertEquals(409, failure { store.start("second.flac", 1) }.status)
        }
    }

    @Test fun 当前会话过期清理分块且旧任务不能查询() = inDirectory { directory ->
        var now = 0L
        val store = ResumeUploadStore(directory, { now }, 10)
        val task = store.start("a.wav", 6)
        store.append(task.id, 0, 3, "abc".byteInputStream())
        now = 10
        assertEquals(410, failure { store.state(task.id) }.status)
        assertTrue(directory.listFiles().isNullOrEmpty())
        store.close()
    }

    @Test fun 写块中恰好过期也不会留下Windows打开句柄中的半文件() = inDirectory { directory ->
        var now = 0L
        val store = ResumeUploadStore(directory, { now }, 10)
        val task = store.start("a.wav", 3)
        val input = object : ByteArrayInputStream("abc".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val read = super.read(buffer, offset, length)
                now = 10
                return read
            }
        }
        assertEquals(410, failure { store.append(task.id, 0, 3, input) }.status)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test fun 关闭只清理本实例的临时文件且新会话不认识旧任务() = inDirectory { directory ->
        val unrelated = File(directory, "unrelated.txt").apply { writeText("保留") }
        val first = ResumeUploadStore(directory)
        val task = first.start("a.wav", 3)
        first.append(task.id, 0, 1, "a".byteInputStream())
        first.close()
        assertEquals("保留", unrelated.readText())
        assertEquals(listOf(unrelated), directory.listFiles()!!.toList())
        ResumeUploadStore(directory).use { second -> assertEquals(404, failure { second.state(task.id) }.status) }
    }

    @Test fun 取消单个任务清理文件并保留其他任务() = inDirectory { directory ->
        ResumeUploadStore(directory).use { store ->
            val first = store.start("a.wav", 3)
            val second = store.start("b.wav", 3)
            store.append(first.id, 0, 1, "a".byteInputStream())
            store.cancel(first.id)
            assertEquals(404, failure { store.state(first.id) }.status)
            assertEquals(second.id, store.state(second.id).id)
            assertEquals(1, directory.listFiles()!!.size)
        }
    }

    private fun failure(block: () -> Unit): TransferHttpError =
        runCatching(block).exceptionOrNull().let { assertTrue(it is TransferHttpError); it as TransferHttpError }

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("watch-resume-test-").toFile()
        try { block(directory) } finally { directory.walkBottomUp().forEach(File::delete) }
    }
}
