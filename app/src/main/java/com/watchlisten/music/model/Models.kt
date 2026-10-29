package com.watchlisten.music.model

import org.json.JSONObject
import java.io.IOException
import java.util.UUID

enum class TrackOrigin { KUGOU, DLNA, LOCAL }

data class Track(
    val hash: String, val title: String, val artist: String,
    val albumId: Long = 0, val audioId: Long = 0, val durationMs: Long = 0,
    val payType: Int = -1, val cover: String = "", val rights: TrackRights = if (payType == 0) TrackRights.FREE else TrackRights.UNKNOWN,
    val origin: TrackOrigin = TrackOrigin.KUGOU, val localPath: String = ""
) {
    val isRemote: Boolean get() = origin == TrackOrigin.DLNA
    val isLocal: Boolean get() = origin == TrackOrigin.LOCAL
    val id: String get() = "$hash:$audioId"
    fun json() = JSONObject().put("hash", hash).put("title", title).put("artist", artist)
        .put("albumId", albumId).put("audioId", audioId).put("durationMs", durationMs)
        .put("payType", payType).put("cover", cover).put("rights", rights.name).put("origin", origin.name)
        .put("localPath", localPath)

    companion object {
        fun fromJson(value: JSONObject) = Track(value.getString("hash"), value.getString("title"),
            value.optString("artist"), value.optLong("albumId"), value.optLong("audioId"),
            value.optLong("durationMs"), value.optInt("payType", -1), value.optString("cover"),
            runCatching { TrackRights.valueOf(value.optString("rights")) }.getOrDefault(
                if (value.optInt("payType", -1) == 0) TrackRights.FREE else TrackRights.UNKNOWN),
            runCatching { TrackOrigin.valueOf(value.optString("origin")) }.getOrDefault(TrackOrigin.KUGOU),
            value.optString("localPath"))
    }
}

data class PlaybackEntry(val track: Track, val instanceId: String = UUID.randomUUID().toString(), val castToken: String? = null) {
    fun json() = JSONObject().put("instance", instanceId).put("track", track.json())
}

data class PlayableSource(val url: String, val format: String, val expiresAtMs: Long,
                          val preview: Boolean = false, val reportedDurationMs: Long = 0,
                          val access: ListeningAccess = if (preview) ListeningAccess.PREVIEW else ListeningAccess.UNKNOWN,
                          val bitrateKbps: Int? = null, val qualityLabel: String = "",
                          val qualityNote: String? = null)
data class SearchPage(val tracks: List<Track>, val total: Int)
data class LoginQr(val key: String, val url: String, val createdAtMs: Long)
data class SessionData(val token: String, val userId: String, val mid: String, val validatedAtMs: Long,
                       val profile: UserProfile? = null)

sealed interface MusicError {
    data object ConsentRequired : MusicError
    data object NetworkUnavailable : MusicError
    data object DnsUnavailable : MusicError
    data object SlowNetwork : MusicError
    data object AuthenticationExpired : MusicError
    data object PermissionDenied : MusicError
    data object VerificationRequired : MusicError
    data object MobileDataBlocked : MusicError
    data object TrackUnavailable : MusicError
    data object PlaybackFailure : MusicError
    data object LyricsUnavailable : MusicError
    data object InvalidResponse : MusicError
    data class Unknown(val detail: String = "暂时无法完成操作") : MusicError
}

class MusicFailure(val error: MusicError) : IOException(error.toString())

fun MusicError.message(): String = when (this) {
    MusicError.ConsentRequired -> "请先阅读并同意使用说明"
    MusicError.NetworkUnavailable -> "网络暂不可用，请检查手表连接"
    MusicError.DnsUnavailable -> "暂时解析不到服务器，稍后重试"
    MusicError.SlowNetwork -> "网络响应较慢，请重试"
    MusicError.AuthenticationExpired -> "登录已失效，请重新扫码"
    MusicError.PermissionDenied -> "当前账号无此歌曲的播放权限"
    MusicError.VerificationRequired -> "服务要求验证，可尝试重新登录"
    MusicError.MobileDataBlocked -> "已关闭移动网络播放，可在偏好设置中开启"
    MusicError.TrackUnavailable -> "这首歌暂时无法播放"
    MusicError.PlaybackFailure -> "播放中断，请重试或切换歌曲"
    MusicError.LyricsUnavailable -> "暂时没有可用歌词"
    MusicError.InvalidResponse -> "服务返回了无法识别的数据"
    is MusicError.Unknown -> detail
}
