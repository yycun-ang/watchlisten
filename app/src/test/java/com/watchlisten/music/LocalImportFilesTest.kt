package com.watchlisten.music

import com.watchlisten.music.data.LocalImportFiles
import com.watchlisten.music.data.isPrivateFile
import com.watchlisten.music.data.localAudioExtension
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalImportFilesTest {
    @Test fun 保存音乐库失败时音频封面临时文件全部回收() = inDirectory { root ->
        val temporary = File(root, ".import.part").apply { writeText("audio") }
        val target = File(root, "audio.mp3")
        val cover = File(root, "cover.image")
        runCatching {
            LocalImportFiles(temporary, target, cover).use {
                it.promoteAudio(); it.storeCover("picture".toByteArray())
                throw java.io.IOException("模拟偏好写入失败")
            }
        }
        assertFalse(temporary.exists())
        assertFalse(target.exists())
        assertFalse(cover.exists())
    }

    @Test fun 已提交导入保留音频封面并清理临时文件() = inDirectory { root ->
        val temporary = File(root, ".import.part").apply { writeText("audio") }
        val target = File(root, "audio.flac")
        val cover = File(root, "cover.image")
        LocalImportFiles(temporary, target, cover).use { it.promoteAudio(); it.storeCover(byteArrayOf(1, 2)); it.commit() }
        assertFalse(temporary.exists())
        assertEquals("audio", target.readText())
        assertArrayEquals(byteArrayOf(1, 2), cover.readBytes())
    }

    @Test fun 重名目标与已有封面不能被覆盖或作为失败回收对象() = inDirectory { root ->
        val temporary = File(root, ".import.part").apply { writeText("new") }
        val target = File(root, "audio.mp3").apply { writeText("existing") }
        val cover = File(root, "cover.image").apply { writeText("existing picture") }
        assertTrue(runCatching { LocalImportFiles(temporary, target, cover).use { it.promoteAudio() } }.isFailure)
        assertEquals("existing", target.readText())
        assertEquals("existing picture", cover.readText())
        val secondTemp = File(root, ".second.part").apply { writeText("new") }
        val secondTarget = File(root, "new.mp3")
        assertTrue(runCatching { LocalImportFiles(secondTemp, secondTarget, cover).use { it.promoteAudio(); it.storeCover(byteArrayOf(1)) } }.isFailure)
        assertFalse(secondTarget.exists())
        assertEquals("existing picture", cover.readText())
    }

    @Test fun 私有文件校验拒绝父目录与同名前缀兄弟目录() = inDirectory { root ->
        val directory = File(root, "local-music").apply { mkdir() }
        assertTrue(isPrivateFile(File(directory, "a.mp3"), directory))
        assertFalse(isPrivateFile(File(directory, "../outside.mp3"), directory))
        assertFalse(isPrivateFile(File(root, "local-music-other/a.mp3"), directory))
        assertFalse(isPrivateFile(File(directory, "nested/a.mp3"), directory))
    }

    @Test fun 扩展名大小写统一且非法路径后缀使用受控扩展名() {
        assertEquals("flac", localAudioExtension("音乐.FLAC"))
        assertEquals("mp3", localAudioExtension("音乐.Mp3"))
        assertEquals("audio", localAudioExtension("音乐.mp3/../../name"))
        assertEquals("audio", localAudioExtension("无扩展名"))
        assertEquals("audio", localAudioExtension("音乐.exe"))
    }

    private fun inDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("watch-local-import-test").toFile()
        try { block(root) } finally { root.walkBottomUp().forEach { it.delete() } }
    }
}
