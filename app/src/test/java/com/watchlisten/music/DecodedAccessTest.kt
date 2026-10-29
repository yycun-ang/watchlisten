package com.watchlisten.music

import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.domain.PlaybackRequest
import com.watchlisten.music.model.*
import com.watchlisten.music.playback.PlaybackSnapshot
import com.watchlisten.music.ui.listeningAccess
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodedAccessTest {
    private val track = Track("decoded-test", "歌曲", "歌手", durationMs = 180000)
    private fun playback(duration: Long) = PlaybackSnapshot(queue = listOf(PlaybackEntry(track)), durationMs = duration)

    @Test fun 实际解码为短片段时不能沿用完整元数据() {
        val state = MusicState(access = mapOf(track.id to ListeningAccess.FULL))
        assertEquals(ListeningAccess.PREVIEW, state.listeningAccess(track, playback(30000)))
    }
    @Test fun 实际整曲可以确认未知源但不能抹掉明确试听标记() {
        assertEquals(ListeningAccess.FULL, MusicState().listeningAccess(track, playback(180000)))
        assertEquals(ListeningAccess.PREVIEW, MusicState(access = mapOf(track.id to ListeningAccess.PREVIEW))
            .listeningAccess(track, playback(180000)))
    }
    @Test fun 旧曲时长不能套到新歌或同歌的新请求() {
        val newTrack = track.copy(hash = "other")
        assertEquals(ListeningAccess.UNKNOWN, MusicState().listeningAccess(newTrack, playback(30000)))
        val pending = MusicState(playRequest = PlaybackRequest.create(track, listOf(track)),
            access = mapOf(track.id to ListeningAccess.FULL))
        assertEquals(ListeningAccess.FULL, pending.listeningAccess(track, playback(30000)))
    }
    @Test fun 尚未解码与无曲库时长时保持未知() {
        assertEquals(ListeningAccess.UNKNOWN, MusicState().listeningAccess(track, playback(0)))
        assertEquals(ListeningAccess.UNKNOWN, MusicState().listeningAccess(track.copy(durationMs = 0), playback(30000)))
    }
}
