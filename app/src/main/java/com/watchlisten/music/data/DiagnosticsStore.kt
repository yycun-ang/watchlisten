package com.watchlisten.music.data

import android.content.Context
import com.watchlisten.music.model.MusicError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class DiagnosticArea { NETWORK, PLAYBACK, CLOUD, TRANSFER, CAST }
enum class DiagnosticCode {
    NONE, WIFI, MOBILE, BLUETOOTH, OFFLINE, CONNECTED, DISCONNECTED, PLAYING, PAUSED, BUFFERING,
    STARTED, STOPPED, CONFIRMED, AWAITING_CONFIRMATION, NETWORK_ERROR, DNS_ERROR, TIMEOUT,
    AUTH_EXPIRED, PERMISSION_DENIED, VERIFICATION_REQUIRED, CONSENT_REQUIRED, MOBILE_BLOCKED,
    UNAVAILABLE, INVALID_RESPONSE, PLAYBACK_ERROR, UNKNOWN_ERROR
}
data class DiagnosticEvent(val timeMs: Long, val area: DiagnosticArea, val code: DiagnosticCode)

/** 白名单事件，只在设备本地保留最近状态；不接受自由文本、地址、歌曲或账号字段。 */
class DiagnosticsStore(context: Context, private val canRecord: () -> Boolean = { true }) {
    private val preferences = context.getSharedPreferences("local_diagnostics", Context.MODE_PRIVATE)
    private val mutableEvents = MutableStateFlow(read())
    val events = mutableEvents.asStateFlow()
    private val mutableExportStatus = MutableStateFlow<String?>(null)
    val exportStatus = mutableExportStatus.asStateFlow()
    fun exportFinished(success: Boolean) { mutableExportStatus.value = if (success) "诊断文件已保存" else "未能保存诊断文件，请重试" }

    @Synchronized fun record(area: DiagnosticArea, code: DiagnosticCode) {
        if (!canRecord()) return
        val before = mutableEvents.value
        if (before.lastOrNull()?.let { it.area == area && it.code == code } == true) return
        mutableEvents.value = (before + DiagnosticEvent(System.currentTimeMillis(), area, code)).takeLast(96)
        persist()
    }

    @Synchronized fun clear() { mutableEvents.value = emptyList(); preferences.edit().remove("events").apply() }

    fun export(version: String, sdk: Int, deviceModel: String, network: NetworkKind,
               playerConnected: Boolean, playing: Boolean, buffering: Boolean, queueCount: Int,
               castEnabled: Boolean, castConnected: Boolean, transferEnabled: Boolean): String = JSONObject()
        .put("schema", 1).put("appVersion", version).put("androidSdk", sdk)
        .put("deviceModel", deviceModel.take(80)).put("createdAtMs", System.currentTimeMillis())
        .put("network", network.name).put("player", JSONObject().put("connected", playerConnected)
            .put("playing", playing).put("buffering", buffering).put("queueCount", queueCount.coerceAtLeast(0)))
        .put("cast", JSONObject().put("enabled", castEnabled).put("connected", castConnected))
        .put("transferEnabled", transferEnabled).put("events", eventJson(mutableEvents.value)).toString(2)

    private fun read(): List<DiagnosticEvent> = runCatching {
        val rows = JSONArray(preferences.getString("events", "[]"))
        (0 until rows.length()).mapNotNull { index -> runCatching {
            val value = rows.getJSONObject(index)
            DiagnosticEvent(value.getLong("timeMs"), DiagnosticArea.valueOf(value.getString("area")),
                DiagnosticCode.valueOf(value.getString("code")))
        }.getOrNull() }.filter { it.timeMs > 0 }.takeLast(96)
    }.getOrDefault(emptyList())

    private fun persist() { preferences.edit().putString("events", eventJson(mutableEvents.value).toString()).apply() }
}

internal fun eventJson(events: List<DiagnosticEvent>) = JSONArray().apply { events.forEach {
    put(JSONObject().put("timeMs", it.timeMs).put("area", it.area.name).put("code", it.code.name))
} }

internal fun diagnosticError(error: MusicError?): DiagnosticCode = when (error) {
    null -> DiagnosticCode.NONE
    MusicError.ConsentRequired -> DiagnosticCode.CONSENT_REQUIRED
    MusicError.NetworkUnavailable -> DiagnosticCode.NETWORK_ERROR
    MusicError.DnsUnavailable -> DiagnosticCode.DNS_ERROR
    MusicError.SlowNetwork -> DiagnosticCode.TIMEOUT
    MusicError.AuthenticationExpired -> DiagnosticCode.AUTH_EXPIRED
    MusicError.PermissionDenied -> DiagnosticCode.PERMISSION_DENIED
    MusicError.VerificationRequired -> DiagnosticCode.VERIFICATION_REQUIRED
    MusicError.MobileDataBlocked -> DiagnosticCode.MOBILE_BLOCKED
    MusicError.TrackUnavailable, MusicError.LyricsUnavailable -> DiagnosticCode.UNAVAILABLE
    MusicError.PlaybackFailure -> DiagnosticCode.PLAYBACK_ERROR
    MusicError.InvalidResponse -> DiagnosticCode.INVALID_RESPONSE
    is MusicError.Unknown -> DiagnosticCode.UNKNOWN_ERROR
}
