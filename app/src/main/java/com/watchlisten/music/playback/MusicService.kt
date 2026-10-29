package com.watchlisten.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.watchlisten.music.WatchApplication
import com.watchlisten.music.model.Track
import com.watchlisten.music.ui.MainActivity
import kotlinx.coroutines.*
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null
    private var remoteSession = false
    private var cachedEntries: List<com.watchlisten.music.model.PlaybackEntry> = emptyList()
    private var queueSaved = false
    private val graph get() = (application as WatchApplication).graph

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpDataSource.Factory(graph.http.media).setUserAgent("WatchListen/0.1")
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == "file") {
                val file = java.io.File(spec.uri.path.orEmpty()).canonicalFile
                val directory = java.io.File(filesDir, "local-music").canonicalFile
                if (file.parentFile != directory || !file.isFile) throw java.io.IOException("Local audio is unavailable")
            }
            if (spec.uri.scheme != "watchlisten") spec else {
                graph.gateway.checkPlaybackNetwork()
                val text = spec.uri.getQueryParameter("track") ?: throw java.io.IOException("Missing track")
                val track = Track.fromJson(JSONObject(text))
                val source = runBlocking { graph.gateway.source(track) }
                spec.withUri(android.net.Uri.parse(source.url))
            }
        }
        val castData = DataSource.Factory {
            CastStreamDataSource(graph.dlna.sources::resolve) {
                graph.consent.accepted.value && graph.dlna.state.value.ready
            }
        }
        val sources = OriginRoutingMediaSourceFactory(DefaultMediaSourceFactory(resolving),
            ProgressiveMediaSource.Factory(castData).setLoadErrorHandlingPolicy(CastLoadErrorPolicy()))
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            repeatMode = graph.library.savedRepeatMode()
            shuffleModeEnabled = graph.library.savedShuffle()
        }
        val activity = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java)
            .putExtra("page", "player").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
        scope.launch {
            graph.consent.accepted.collect { accepted ->
                if (!accepted) {
                    player.playWhenReady = false
                    player.stop()
                }
            }
        }
        val entries = graph.library.restoredQueue()
        cachedEntries = entries
        if (entries.isNotEmpty()) player.setMediaItems(entries.map { it.mediaItem() },
            graph.library.restoredIndex(entries), graph.library.restoredPosition(entries))
        player.addListener(object : Player.Listener {
            override fun onRepeatModeChanged(repeatMode: Int) { if (!remoteSession && !hasRemoteQueue()) graph.library.savePlaybackMode(repeatMode, player.shuffleModeEnabled) }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { if (!remoteSession && !hasRemoteQueue()) graph.library.savePlaybackMode(player.repeatMode, shuffleModeEnabled) }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                persist()
                if (naturalTrackTransition(reason)) graph.music.sleepTimerExpired()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && player.currentMediaItem != null) graph.music.sleepTimerExpired()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                persist()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                persist()
                if (isPlaying) player.currentMediaItem?.entry()?.track?.let { graph.library.remember(it) }
                saveJob?.cancel()
                if (isPlaying) saveJob = scope.launch { while (isActive) { delay(15000); persist() } }
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || cachedEntries.size != player.mediaItemCount) {
                    cachedEntries = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).entry() }
                    queueSaved = false
                }
                persist()
            }
        })
    }

    private fun persist() {
        val player = session?.player ?: return
        val entries = cachedEntries
        if (entries.isNotEmpty()) remoteSession = entries.any { it.track.isRemote }
        if (remoteSession) return
        if (!queueSaved) {
            graph.library.saveQueue(entries, player.currentMediaItem?.mediaId, player.currentPosition)
            queueSaved = true
        } else graph.library.savePosition(player.currentMediaItem?.mediaId, player.currentPosition)
    }

    private fun hasRemoteQueue() = cachedEntries.any { it.track.isRemote }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (graph.consent.accepted.value &&
            (controllerInfo.packageName == packageName || controllerInfo.isTrusted)) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!graph.dlna.state.value.enabled && session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        persist()
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

internal fun naturalTrackTransition(reason: Int): Boolean =
    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
