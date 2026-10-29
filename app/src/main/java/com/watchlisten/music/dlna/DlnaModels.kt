package com.watchlisten.music.dlna

import java.io.IOException

enum class DlnaService(val id: String, val urn: String, val path: String) {
    TRANSPORT("AVTransport", "urn:schemas-upnp-org:service:AVTransport:1", "avtransport"),
    RENDERING("RenderingControl", "urn:schemas-upnp-org:service:RenderingControl:1", "renderingcontrol"),
    CONNECTION("ConnectionManager", "urn:schemas-upnp-org:service:ConnectionManager:1", "connectionmanager")
}

data class DlnaIdentity(val uuid: String, val name: String, val model: String = "腕上听", val version: String = "0.8.0")

data class DlnaMedia(
    val uri: String, val metadata: String = "", val title: String = "手机投放", val artist: String = "",
    val album: String = "", val artwork: String = "", val durationMs: Long = 0, val mimeType: String = ""
)

data class DlnaSnapshot(
    val media: DlnaMedia? = null, val next: DlnaMedia? = null,
    val transportState: String = "NO_MEDIA_PRESENT", val positionMs: Long = 0, val durationMs: Long = 0,
    val volume: Int = 0, val muted: Boolean = false, val playMode: String = "NORMAL", val failed: Boolean = false,
    val canSeek: Boolean = false, val canNext: Boolean = false, val canPrevious: Boolean = false
)

sealed interface DlnaCommand {
    data class SetUri(val media: DlnaMedia, val next: Boolean = false) : DlnaCommand
    data object Play : DlnaCommand
    data object Pause : DlnaCommand
    data object Stop : DlnaCommand
    data object Next : DlnaCommand
    data object Previous : DlnaCommand
    data class Seek(val positionMs: Long) : DlnaCommand
    data class Volume(val percent: Int) : DlnaCommand
    data class Mute(val muted: Boolean) : DlnaCommand
    data class PlayMode(val mode: String) : DlnaCommand
}

/** HTTP 线程调用；Android 实现负责切换到播放器主线程并校验当前控制手机。 */
interface DlnaTarget {
    fun snapshot(peer: String): DlnaSnapshot
    fun command(peer: String, command: DlnaCommand): DlnaSnapshot
}

class DlnaFault(val code: Int, val description: String) : IOException(description)
