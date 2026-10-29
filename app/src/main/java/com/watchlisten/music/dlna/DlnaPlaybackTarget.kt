package com.watchlisten.music.dlna

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.watchlisten.music.AppGraph
import com.watchlisten.music.model.PlaybackEntry
import com.watchlisten.music.model.Track
import com.watchlisten.music.model.TrackOrigin
import com.watchlisten.music.playback.entry
import com.watchlisten.music.playback.mediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** All MediaController operations, including synchronous UPnP reads, run on its application looper. */
internal class DlnaPlaybackTarget(
    private val graph: AppGraph,
    context: Context,
    private val sources: CastSourceStore,
    private val allowed: () -> Boolean,
    private val onPeer: (String?) -> Unit,
    private val onActivity: () -> Unit
) : DlnaTarget {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaController? = null
    private var peer: String? = null
    private val media = mutableMapOf<String, DlnaMedia>()
    private var playMode = "NORMAL"
    private var previousRepeat = Player.REPEAT_MODE_OFF
    private var previousShuffle = false
    private var hadCast = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { localSelection() }
    }

    fun attach(player: MediaController) { this.player = player; player.addListener(listener) }

    override fun snapshot(peer: String): DlnaSnapshot = main {
        authorize(peer, claim = false)
        read()
    }

    override fun command(peer: String, command: DlnaCommand): DlnaSnapshot = main {
        authorize(peer, claim = false)
        val current = player ?: throw DlnaFault(501, "Player unavailable")
        if (command is DlnaCommand.SetUri) {
            if (command.media.uri.isNotEmpty()) {
                try { RemoteUrlPolicy.validate(command.media.uri, peer) }
                catch (_: Exception) { throw DlnaFault(714, "Unsupported media URL") }
            }
            if (command.next && current.currentMediaItem?.entry()?.track?.isRemote != true)
                throw DlnaFault(701, "No current media")
            authorize(peer, claim = !command.next && command.media.uri.isNotEmpty())
            if (command.next) setNext(current, command.media, peer)
            else if (command.media.uri.isEmpty()) disconnect()
            else setCurrent(current, command.media, peer)
        } else {
            // Volume is allowed during discovery; transport never takes over an ordinary Kugou queue.
            if (command !is DlnaCommand.Volume && command !is DlnaCommand.Mute &&
                current.currentMediaItem?.entry()?.track?.isRemote != true) throw DlnaFault(701, "No current media")
            authorize(peer, claim = current.currentMediaItem?.entry()?.track?.isRemote == true)
            when (command) {
                DlnaCommand.Play -> { if (current.playbackState == Player.STATE_IDLE || current.playbackState == Player.STATE_ENDED) current.prepare(); current.play() }
                DlnaCommand.Pause -> current.pause()
                DlnaCommand.Stop -> { current.pause(); current.stop() }
                DlnaCommand.Next -> { if (!current.hasNextMediaItem()) throw DlnaFault(711, "No next track"); current.seekToNextMediaItem(); current.prepare() }
                DlnaCommand.Previous -> { if (!current.hasPreviousMediaItem()) throw DlnaFault(711, "No previous track"); current.seekToPreviousMediaItem(); current.prepare() }
                is DlnaCommand.Seek -> {
                    if (!current.isCurrentMediaItemSeekable) throw DlnaFault(710, "Seek unsupported")
                    val duration = current.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
                    if (command.positionMs > duration) throw DlnaFault(711, "Seek beyond duration")
                    current.seekTo(command.positionMs.coerceAtLeast(0))
                }
                is DlnaCommand.Volume -> audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * command.percent / 100f).toInt(), 0)
                is DlnaCommand.Mute -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (command.muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                is DlnaCommand.PlayMode -> {
                    playMode = command.mode
                    current.shuffleModeEnabled = command.mode in setOf("SHUFFLE", "SHUFFLE_NOREPEAT", "RANDOM")
                    current.repeatMode = when (command.mode) {
                        "REPEAT_ONE" -> Player.REPEAT_MODE_ONE
                        "REPEAT_ALL" -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
                is DlnaCommand.SetUri -> Unit
            }
        }
        onActivity()
        read()
    }

    private fun authorize(candidate: String, claim: Boolean) {
        if (!allowed()) throw DlnaFault(501, "Receiver disabled")
        if (peer != null && peer != candidate) throw DlnaFault(606, "Another controller is connected")
        if (claim && peer == null) { peer = candidate; onPeer(candidate) }
    }

    private fun setCurrent(player: MediaController, value: DlnaMedia, peer: String) {
        graph.music.beginRemotePlayback()
        if (player.currentMediaItem?.entry()?.track?.isRemote != true) {
            previousRepeat = player.repeatMode
            previousShuffle = player.shuffleModeEnabled
            val original = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).entry() }
            graph.library.saveQueue(original, player.currentMediaItem?.mediaId, player.currentPosition)
            graph.library.savePlaybackMode(previousRepeat, previousShuffle)
        }
        val item = entry(value, peer)
        media.clear()
        media[item.instanceId] = value
        playMode = "NORMAL"
        player.pause()
        hadCast = true
        player.setMediaItem(item.mediaItem())
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        player.prepare()
        prune(player)
    }

    private fun setNext(player: MediaController, value: DlnaMedia, peer: String) {
        val index = player.currentMediaItemIndex
        if (index + 1 < player.mediaItemCount) player.removeMediaItems(index + 1, player.mediaItemCount)
        if (value.uri.isNotEmpty()) {
            val item = entry(value, peer)
            media[item.instanceId] = value
            player.addMediaItem(item.mediaItem())
        }
        if (index > 1) player.removeMediaItems(0, index - 1)
        prune(player)
    }

    private fun entry(value: DlnaMedia, peer: String): PlaybackEntry {
        val token = sources.put(value, peer)
        // Coil/ordinary cover requests must not receive arbitrary phone URLs.
        val artwork = runCatching {
            val uri = RemoteUrlPolicy.validate(value.artwork, peer)
            if (uri.host == peer) "" else "https:" + uri.toASCIIString().substringAfter(':')
        }.getOrDefault("")
        return PlaybackEntry(Track("dlna-${UUID.randomUUID()}", value.title.ifBlank { "手机投放" },
            value.artist, durationMs = value.durationMs, cover = artwork, origin = TrackOrigin.DLNA), castToken = token)
    }

    private fun prune(player: MediaController) {
        val entries = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).entry() }
        sources.retain(entries.mapNotNull { it.castToken }.toSet())
        media.keys.retainAll(entries.map { it.instanceId }.toSet())
    }

    /** Called on main when a local selection replaces the temporary remote session. */
    fun localSelection() {
        if (hadCast && player?.currentMediaItem?.entry()?.track?.isRemote != true) {
            hadCast = false
            peer = null
            player?.repeatMode = previousRepeat
            player?.shuffleModeEnabled = previousShuffle
            media.clear(); sources.clear(); onPeer(null)
        }
    }

    /** Restores the original persisted queue in paused state, without resolving any old URL. */
    fun disconnect() {
        hadCast = false
        player?.let { current ->
            if (current.currentMediaItem?.entry()?.track?.isRemote == true) {
                current.pause()
                current.stop()
                current.repeatMode = previousRepeat
                current.shuffleModeEnabled = previousShuffle
                val saved = graph.library.restoredQueue()
                if (saved.isEmpty()) current.clearMediaItems()
                else current.setMediaItems(saved.map { it.mediaItem() }, graph.library.restoredIndex(saved), graph.library.restoredPosition(saved))
            }
        }
        peer = null; media.clear(); sources.clear(); onPeer(null)
    }

    fun release() { disconnect(); player?.removeListener(listener); player = null }

    private fun read(): DlnaSnapshot {
        val p = player
        val entry = p?.currentMediaItem?.entry()?.takeIf { it.track.isRemote }
        val current = entry?.let { media[it.instanceId] }
        val next = if (entry != null && p.hasNextMediaItem()) p.getMediaItemAt(p.nextMediaItemIndex).entry()?.let { media[it.instanceId] } else null
        val state = when {
            current == null -> "NO_MEDIA_PRESENT"
            p.playbackState == Player.STATE_BUFFERING -> "TRANSITIONING"
            p.isPlaying -> "PLAYING"
            p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED -> "STOPPED"
            else -> "PAUSED_PLAYBACK"
        }
        return DlnaSnapshot(current, next, state, if (entry != null) p.currentPosition.coerceAtLeast(0) else 0,
            p?.duration?.takeIf { entry != null && it > 0 && it != C.TIME_UNSET } ?: current?.durationMs ?: 0,
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1),
            audio.isStreamMute(AudioManager.STREAM_MUSIC), playMode, p?.playerError != null,
            entry != null && p.isCurrentMediaItemSeekable, entry != null && p.hasNextMediaItem(), entry != null && p.hasPreviousMediaItem())
    }

    private fun <T> main(block: () -> T): T = runBlocking {
        withTimeout(7000) { withContext(Dispatchers.Main.immediate) { block() } }
    }
}
