package com.watchlisten.music.ui

import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.model.MusicError
import com.watchlisten.music.model.Track
import com.watchlisten.music.model.ListeningAccess
import com.watchlisten.music.playback.PlaybackSnapshot

/** 首页、搜索和主播放器共同展示用户最近点播的歌曲。 */
data class NowPlayingPresentation(
    val track: Track?,
    val playing: Boolean,
    val buffering: Boolean,
    val error: MusicError?
)

fun MusicState.presentPlayback(playback: PlaybackSnapshot): NowPlayingPresentation {
    val request = playRequest
    return NowPlayingPresentation(
        track = request?.track ?: preparing ?: playback.current?.track,
        playing = request == null && preparing == null && playback.playing,
        buffering = if (request != null) request.failed == null else preparing != null || playback.buffering,
        // 旧曲可能仍在播放；它的错误不能盖过这次点播的准备或失败状态。
        error = if (request != null) request.failed else error ?: playback.error
    )
}

/** 解码器的实际片段长度可纠正曲库/取源元数据；旧曲长度不能用于新点播。 */
internal fun MusicState.listeningAccess(track: Track, playback: PlaybackSnapshot): ListeningAccess {
    if (track.isRemote) return ListeningAccess.UNKNOWN
    val reported = access[track.id] ?: ListeningAccess.UNKNOWN
    if (playRequest != null || preparing != null || playback.current?.track?.id != track.id ||
        playback.durationMs <= 0 || track.durationMs <= 0) return reported
    if (playback.durationMs + 5000 < track.durationMs) return ListeningAccess.PREVIEW
    if (reported == ListeningAccess.UNKNOWN && kotlin.math.abs(playback.durationMs - track.durationMs) <= 5000)
        return ListeningAccess.FULL
    return reported
}
