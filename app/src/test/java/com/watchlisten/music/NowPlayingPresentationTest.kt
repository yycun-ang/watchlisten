package com.watchlisten.music

import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.domain.PlaybackRequest
import com.watchlisten.music.model.MusicError
import com.watchlisten.music.model.PlaybackEntry
import com.watchlisten.music.model.Track
import com.watchlisten.music.playback.PlaybackSnapshot
import com.watchlisten.music.ui.NowPlayingPresentation
import com.watchlisten.music.ui.presentPlayback
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingPresentationTest {
    private val oldTrack = Track("old", "旧歌 A", "歌手 A")
    private val newTrack = Track("new", "新歌 B", "歌手 B")
    private val playingOldTrack = PlaybackSnapshot(
        queue = listOf(PlaybackEntry(oldTrack, "entry-a")),
        playing = true
    )

    @Test
    fun 没有歌曲时展示空状态() {
        assertEquals(NowPlayingPresentation(null, false, false, null),
            MusicState().presentPlayback(PlaybackSnapshot()))
    }

    @Test
    fun 正常播放时所有入口展示同一当前歌曲() {
        assertEquals(NowPlayingPresentation(oldTrack, true, false, null),
            MusicState().presentPlayback(playingOldTrack))
    }

    @Test
    fun 暂停时保留当前歌曲且不显示播放中() {
        assertEquals(NowPlayingPresentation(oldTrack, false, false, null),
            MusicState().presentPlayback(playingOldTrack.copy(playing = false)))
    }

    @Test
    fun 新歌正在取源时旧歌仍播也统一展示新歌准备状态() {
        val music = MusicState(
            playRequest = PlaybackRequest.create(newTrack, listOf(newTrack)),
            preparing = newTrack
        )
        assertEquals(NowPlayingPresentation(newTrack, false, true, null),
            music.presentPlayback(playingOldTrack))
    }

    @Test
    fun 只有请求没有preparing时也不会展示旧曲播放状态() {
        val music = MusicState(playRequest = PlaybackRequest.create(newTrack, listOf(newTrack)))
        assertEquals(NowPlayingPresentation(newTrack, false, true, null),
            music.presentPlayback(playingOldTrack))
    }

    @Test
    fun 准备曲目优先于当前歌曲() {
        assertEquals(NowPlayingPresentation(newTrack, false, true, null),
            MusicState(preparing = newTrack).presentPlayback(playingOldTrack))
    }

    @Test
    fun 请求曲目优先于残留准备曲目() {
        val music = MusicState(
            playRequest = PlaybackRequest.create(newTrack, listOf(newTrack)),
            preparing = oldTrack
        )
        assertEquals(NowPlayingPresentation(newTrack, false, true, null),
            music.presentPlayback(playingOldTrack))
    }

    @Test
    fun 新歌失败时保留新歌和失败原因而不是旧歌的播放错误() {
        val request = PlaybackRequest.create(newTrack, listOf(newTrack))
            .copy(failed = MusicError.PermissionDenied)
        val music = MusicState(playRequest = request, error = MusicError.SlowNetwork)
        val oldPlayback = playingOldTrack.copy(buffering = true, error = MusicError.PlaybackFailure)
        assertEquals(NowPlayingPresentation(newTrack, false, false, MusicError.PermissionDenied),
            music.presentPlayback(oldPlayback))
    }

    @Test
    fun 请求失败后即使残留preparing也停止加载提示() {
        val request = PlaybackRequest.create(newTrack, listOf(newTrack))
            .copy(failed = MusicError.TrackUnavailable)
        val music = MusicState(playRequest = request, preparing = newTrack)
        assertEquals(NowPlayingPresentation(newTrack, false, false, MusicError.TrackUnavailable),
            music.presentPlayback(playingOldTrack))
    }

    @Test
    fun 新请求准备期间不继承旧曲的失败原因() {
        val music = MusicState(
            playRequest = PlaybackRequest.create(newTrack, listOf(newTrack)),
            error = MusicError.SlowNetwork
        )
        assertEquals(NowPlayingPresentation(newTrack, false, true, null),
            music.presentPlayback(playingOldTrack.copy(error = MusicError.PlaybackFailure)))
    }

    @Test
    fun 没有点播请求时领域错误优先于播放器错误() {
        val music = MusicState(error = MusicError.MobileDataBlocked)
        assertEquals(NowPlayingPresentation(oldTrack, true, false, MusicError.MobileDataBlocked),
            music.presentPlayback(playingOldTrack.copy(error = MusicError.PlaybackFailure)))
    }

    @Test
    fun 没有点播请求时显示播放器缓冲与错误() {
        val playback = playingOldTrack.copy(playing = false, buffering = true,
            error = MusicError.PlaybackFailure)
        assertEquals(NowPlayingPresentation(oldTrack, false, true, MusicError.PlaybackFailure),
            MusicState().presentPlayback(playback))
    }

    @Test
    fun 新歌请求完成后恢复当前歌曲的实际播放状态() {
        val playback = playingOldTrack.copy(queue = listOf(PlaybackEntry(newTrack, "entry-b")))
        assertEquals(NowPlayingPresentation(newTrack, true, false, null),
            MusicState().presentPlayback(playback))
    }
}
