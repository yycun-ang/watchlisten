package com.watchlisten.music

import android.content.SharedPreferences
import com.watchlisten.music.data.LocalFileAction
import com.watchlisten.music.data.PendingLocalFileActions
import com.watchlisten.music.data.completeConfirmedLocalRemoval
import com.watchlisten.music.model.Track
import com.watchlisten.music.model.TrackOrigin
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.ArrayDeque

class LocalFileActionRecoveryTest {
    private val first = localTrack(1)
    private val second = localTrack(2)

    @Test fun 组件重建后只从已保存ID重新解析当前歌曲() {
        val preferences = PreferencesFixture()
        assertTrue(PendingLocalFileActions(preferences.value).save(LocalFileAction.LYRICS, first.id))
        assertEquals(mapOf(LocalFileAction.LYRICS.key to first.id), preferences.saved)
        val current = first.copy(title = "更新后的本机歌曲", localPath = "/fixture/current-audio.mp3")
        val lookups = mutableListOf<String>()
        val restored = PendingLocalFileActions(preferences.value).target(LocalFileAction.LYRICS) { id ->
            lookups += id
            current.takeIf { it.id == id }
        }
        assertEquals(listOf(first.id), lookups)
        assertSame(current, restored)
        assertFalse(preferences.saved.values.contains(first.title))
        assertFalse(preferences.saved.values.contains(first.localPath))
    }

    @Test fun 歌词与导出目标相互隔离且重新选择只替换对应目标() {
        val preferences = PreferencesFixture()
        val actions = PendingLocalFileActions(preferences.value)
        assertTrue(actions.save(LocalFileAction.LYRICS, first.id))
        assertTrue(actions.save(LocalFileAction.EXPORT, second.id))
        val restored = PendingLocalFileActions(preferences.value)
        assertEquals(first, restored.target(LocalFileAction.LYRICS, ::resolve))
        assertEquals(second, restored.target(LocalFileAction.EXPORT, ::resolve))
        assertTrue(restored.save(LocalFileAction.EXPORT, first.id))
        assertEquals(first, restored.target(LocalFileAction.LYRICS, ::resolve))
        assertEquals(first, PendingLocalFileActions(preferences.value).target(LocalFileAction.EXPORT, ::resolve))
    }

    @Test fun 取消文件选择会清除持久化目标但保留另一种操作() {
        val preferences = PreferencesFixture()
        val actions = PendingLocalFileActions(preferences.value)
        actions.save(LocalFileAction.LYRICS, first.id)
        actions.save(LocalFileAction.EXPORT, second.id)
        assertTrue(actions.clear(LocalFileAction.LYRICS))
        val restored = PendingLocalFileActions(preferences.value)
        assertNull(restored.target(LocalFileAction.LYRICS, ::resolve))
        assertEquals(second, restored.target(LocalFileAction.EXPORT, ::resolve))
        assertTrue(restored.clear(LocalFileAction.EXPORT))
        assertNull(PendingLocalFileActions(preferences.value).target(LocalFileAction.EXPORT, ::resolve))
        assertTrue(preferences.saved.isEmpty())
    }

    @Test fun 已移除歌曲不能恢复且失效清理会保留有效目标() {
        val preferences = PreferencesFixture()
        val actions = PendingLocalFileActions(preferences.value)
        actions.save(LocalFileAction.LYRICS, first.id)
        actions.save(LocalFileAction.EXPORT, second.id)
        val restored = PendingLocalFileActions(preferences.value)
        val remaining: (String) -> Track? = { id -> second.takeIf { it.id == id } }
        assertNull(restored.target(LocalFileAction.LYRICS, remaining))
        assertTrue(restored.clearInvalid(remaining))
        assertFalse(preferences.saved.containsKey(LocalFileAction.LYRICS.key))
        assertEquals(second, restored.target(LocalFileAction.EXPORT, remaining))
        assertNull(PendingLocalFileActions(preferences.value).target(LocalFileAction.LYRICS, ::resolve))
    }

    @Test fun 保存提交失败时返回false并清理已进入内存的未确认目标() {
        val preferences = PreferencesFixture().apply { commitResults.addAll(listOf(false, true)) }
        val actions = PendingLocalFileActions(preferences.value)
        assertFalse(actions.save(LocalFileAction.LYRICS, first.id))
        assertEquals(2, preferences.commits)
        assertTrue(preferences.saved.isEmpty())
        assertNull(PendingLocalFileActions(preferences.value).target(LocalFileAction.LYRICS, ::resolve))
    }

    @Test fun 清除及失效清理提交失败均返回false且能够重试() {
        val preferences = PreferencesFixture().apply { applyFailedCommits = false }
        val actions = PendingLocalFileActions(preferences.value)
        actions.save(LocalFileAction.EXPORT, first.id)
        preferences.commitResults.add(false)
        assertFalse(actions.clear(LocalFileAction.EXPORT))
        assertEquals(first, PendingLocalFileActions(preferences.value).target(LocalFileAction.EXPORT, ::resolve))
        preferences.commitResults.add(false)
        assertFalse(actions.clearInvalid { null })
        assertTrue(preferences.saved.containsKey(LocalFileAction.EXPORT.key))
        assertTrue(PendingLocalFileActions(preferences.value).clearInvalid { null })
        assertTrue(preferences.saved.isEmpty())
    }

    @Test fun 删除保存失败时不会通知播放器或清理文件() = runBlocking {
        val events = mutableListOf<String>()
        val removed = completeConfirmedLocalRemoval(persist = { events += "persist"; false },
            onConfirmed = { events += "confirmed" }, cleanup = { events += "cleanup" })
        assertFalse(removed)
        assertEquals(listOf("persist"), events)
    }

    @Test fun 删除保存成功后先通知再执行物理清理() = runBlocking {
        val events = mutableListOf<String>()
        val removed = completeConfirmedLocalRemoval(persist = { events += "persist"; true },
            onConfirmed = { events += "confirmed" }, cleanup = { events += "cleanup" })
        assertTrue(removed)
        assertEquals(listOf("persist", "confirmed", "cleanup"), events)
    }

    @Test fun 提交成功瞬间取消仍完成通知与清理() = runBlocking {
        val events = mutableListOf<String>()
        lateinit var removal: Job
        removal = launch(start = CoroutineStart.LAZY) {
            completeConfirmedLocalRemoval(persist = {
                events += "persist"
                removal.cancel()
                true
            }, onConfirmed = { events += "confirmed" }, cleanup = { events += "cleanup" })
        }
        removal.start()
        withTimeout(1_000) { removal.join() }
        assertTrue(removal.isCancelled)
        assertEquals(listOf("persist", "confirmed", "cleanup"), events)
    }

    @Test fun 通知回调挂起时取消仍等待通知完成再清理() = runBlocking {
        val events = mutableListOf<String>()
        val callbackStarted = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        val removal = launch(start = CoroutineStart.UNDISPATCHED) {
            completeConfirmedLocalRemoval(persist = { events += "persist"; true }, onConfirmed = {
                events += "confirmed-start"
                callbackStarted.complete(Unit)
                releaseCallback.await()
                events += "confirmed-end"
            }, cleanup = { events += "cleanup" })
        }
        try {
            withTimeout(1_000) { callbackStarted.await() }
            removal.cancel()
            assertEquals(listOf("persist", "confirmed-start"), events)
            releaseCallback.complete(Unit)
            withTimeout(1_000) { removal.join() }
            assertTrue(removal.isCancelled)
            assertEquals(listOf("persist", "confirmed-start", "confirmed-end", "cleanup"), events)
        } finally {
            releaseCallback.complete(Unit)
            removal.cancelAndJoin()
        }
    }

    private fun resolve(id: String): Track? = listOf(first, second).firstOrNull { it.id == id }

    private fun localTrack(index: Int) = Track("local-" + index.toString(16).padStart(64, '0'),
        "虚构本机歌曲 $index", "虚构歌手", origin = TrackOrigin.LOCAL, localPath = "/fixture/audio-$index.mp3")

    /** Android commit 失败也可能已经更新进程内存；允许两种行为以覆盖失败后的保守处理。 */
    private class PreferencesFixture {
        val saved = linkedMapOf<String, String>()
        val commitResults = ArrayDeque<Boolean>()
        var commits = 0
        var applyFailedCommits = true
        val value: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "getString" -> saved[arguments!![0] as String] ?: arguments[1]
                "getAll" -> saved.toMap()
                "contains" -> saved.containsKey(arguments!![0] as String)
                "edit" -> editor()
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "equals" -> proxy === arguments?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "LocalFileActionPreferencesFixture"
                else -> error("未实现的 SharedPreferences 方法：${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val updates = linkedMapOf<String, String?>()
            var clearAll = false
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, arguments ->
                when (method.name) {
                    "putString" -> { updates[arguments!![0] as String] = arguments[1] as String?; proxy }
                    "remove" -> { updates[arguments!![0] as String] = null; proxy }
                    "clear" -> { clearAll = true; proxy }
                    "commit" -> {
                        commits++
                        val accepted = if (commitResults.isEmpty()) true else commitResults.removeFirst()
                        if (accepted || applyFailedCommits) {
                            if (clearAll) saved.clear()
                            updates.forEach { (key, value) -> if (value == null) saved.remove(key) else saved[key] = value }
                        }
                        accepted
                    }
                    "equals" -> proxy === arguments?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "LocalFileActionEditorFixture"
                    else -> error("未实现的 SharedPreferences.Editor 方法：${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
