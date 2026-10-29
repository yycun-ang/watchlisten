package com.watchlisten.music

import com.watchlisten.music.data.CatalogGateway
import com.watchlisten.music.model.MusicError
import com.watchlisten.music.model.message
import com.watchlisten.music.playback.MAX_CONSECUTIVE_SKIPS
import com.watchlisten.music.playback.PlaybackRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryTest {

    @Test
    fun 移动网络限制立即停止且不刷新或跳过队列() {
        listOf(false, true).forEach { refreshed ->
            assertEquals(PlaybackRecovery.Decision.GiveUp,
                PlaybackRecovery.decide(MusicError.MobileDataBlocked, refreshed, 0, 100, true))
        }
    }

    @Test
    fun 网络类错误且未用过刷新时重新取源() {
        assertEquals(PlaybackRecovery.Decision.RefreshSource,
            PlaybackRecovery.decide(MusicError.SlowNetwork, false, 0, 5, true))
        assertEquals(PlaybackRecovery.Decision.RefreshSource,
            PlaybackRecovery.decide(MusicError.NetworkUnavailable, false, 0, 5, true))
    }

    @Test
    fun 明确不可用不浪费一次刷新() {
        assertEquals(PlaybackRecovery.Decision.SkipNext,
            PlaybackRecovery.decide(MusicError.TrackUnavailable, false, 0, 5, true))
        assertEquals(PlaybackRecovery.Decision.SkipNext,
            PlaybackRecovery.decide(MusicError.PermissionDenied, false, 0, 5, true))
        assertEquals(PlaybackRecovery.Decision.SkipNext,
            PlaybackRecovery.decide(MusicError.InvalidResponse, false, 0, 5, true))
    }

    @Test
    fun 刷新只用一次() {
        assertEquals(PlaybackRecovery.Decision.SkipNext,
            PlaybackRecovery.decide(MusicError.SlowNetwork, true, 0, 5, true))
    }

    @Test
    fun 队列末尾直接停止而不是循环() {
        assertEquals(PlaybackRecovery.Decision.GiveUp,
            PlaybackRecovery.decide(MusicError.TrackUnavailable, false, 1, 5, false))
    }

    @Test
    fun 连续失败达到上限后停止() {
        assertEquals(PlaybackRecovery.Decision.SkipNext,
            PlaybackRecovery.decide(MusicError.TrackUnavailable, false, 1, 20, true))
        assertEquals(PlaybackRecovery.Decision.GiveUp,
            PlaybackRecovery.decide(MusicError.TrackUnavailable, false, MAX_CONSECUTIVE_SKIPS, 20, true))
    }

    @Test
    fun 短队列按队列长度封顶() {
        assertEquals(PlaybackRecovery.Decision.SkipNext,
            PlaybackRecovery.decide(MusicError.TrackUnavailable, false, 1, 2, true))
        assertEquals(PlaybackRecovery.Decision.GiveUp,
            PlaybackRecovery.decide(MusicError.TrackUnavailable, false, 2, 2, true))
    }

    @Test
    fun 空队列不会除零且立即停止() {
        assertEquals(PlaybackRecovery.Decision.GiveUp,
            PlaybackRecovery.decide(MusicError.TrackUnavailable, false, 1, 0, false))
    }
}

class CatalogGatewayHostTest {

    @Test
    fun 只接受酷狗自有域名() {
        listOf("kugou.com", "fs.open.kugou.com", "dl.stream.kugou.net", "img.kgimg.com",
            "a.kugou.com").forEach { assertTrue(it, CatalogGateway.allowedMediaHost(it)) }
    }

    @Test
    fun 拒绝相似与恶意域名() {
        listOf("evil.com", "kugou.com.evil.net", "notkugou.com", "kugou.cn", "",
            "xkugou.com", "kugou.net.attacker.org").forEach { assertFalse(it, CatalogGateway.allowedMediaHost(it)) }
    }
}

class NetworkFailureTest {

    @Test
    fun 超时归为慢网而不是断网() {
        assertEquals("网络响应较慢，请重试", MusicError.SlowNetwork.message())
        assertEquals("暂时解析不到服务器，稍后重试", MusicError.DnsUnavailable.message())
    }

    @Test
    fun 每种错误都有可读提示() {
        listOf(MusicError.NetworkUnavailable, MusicError.DnsUnavailable, MusicError.SlowNetwork,
            MusicError.AuthenticationExpired, MusicError.PermissionDenied, MusicError.VerificationRequired,
            MusicError.TrackUnavailable, MusicError.PlaybackFailure, MusicError.LyricsUnavailable,
            MusicError.InvalidResponse, MusicError.Unknown("详情")).forEach {
            val text = it.message()
            assertTrue("$it 应有非空中文提示", text.isNotBlank())
            assertFalse("$it 提示不应泄露技术细节", text.contains("Exception") || text.contains("http"))
        }
    }
}
