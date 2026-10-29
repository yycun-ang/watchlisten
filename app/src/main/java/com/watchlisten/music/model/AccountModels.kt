package com.watchlisten.music.model

import org.json.JSONObject

enum class LoginPhase { IDLE, LOADING, WAITING, SCANNED, EXPIRED, ERROR, CONFIRMED }
data class QrCheck(val phase: LoginPhase, val session: SessionData? = null)

enum class ListeningAccess(val label: String) {
    UNKNOWN("播放权限待确认"), FULL("可完整播放"), PREVIEW("仅可试听"), DENIED("当前账号无播放权限")
}

enum class TrackRights(val label: String, val detail: String) {
    FREE("免费", "普通音质免费，高音质仍以授权为准"),
    LIMITED_FREE("限免", "限免可能有时间或账号条件，以本次授权为准"),
    VIP("VIP", "会员歌曲；非会员能否试听以服务端返回为准"),
    UNKNOWN("权益待确认", "服务端未提供明确标记，点击播放后确认完整、试听或无权限")
}

enum class MembershipStatus { ACTIVE, INACTIVE, UNKNOWN }
data class Membership(
    val label: String = "会员信息待确认", val status: MembershipStatus = MembershipStatus.UNKNOWN,
    val expiresAtMs: Long = 0, val level: Int = 0
) {
    fun effectiveStatus(nowMs: Long = System.currentTimeMillis()): MembershipStatus =
        if (status == MembershipStatus.ACTIVE && expiresAtMs > 0 && expiresAtMs <= nowMs)
            MembershipStatus.INACTIVE else status
    fun json() = JSONObject().put("label", label).put("status", status.name)
        .put("expires", expiresAtMs).put("level", level)
    companion object {
        fun fromJson(value: JSONObject) = Membership(value.optString("label", "会员信息待确认"),
            runCatching { MembershipStatus.valueOf(value.optString("status")) }.getOrDefault(MembershipStatus.UNKNOWN),
            value.optLong("expires"), value.optInt("level"))
    }
}

data class UserProfile(
    val nickname: String = "", val avatarUrl: String = "", val signature: String = "",
    val vip: Membership = Membership(), val updatedAtMs: Long = 0,
    val memberships: List<Membership> = emptyList()
) {
    fun json() = JSONObject().put("nickname", nickname).put("avatar", avatarUrl)
        .put("signature", signature).put("vip", vip.json()).put("updated", updatedAtMs)
        .put("memberships", org.json.JSONArray().apply { memberships.forEach { put(it.json()) } })
    companion object {
        fun fromJson(value: JSONObject) = UserProfile(value.optString("nickname"), value.optString("avatar"),
            value.optString("signature"), value.optJSONObject("vip")?.let(Membership::fromJson) ?: Membership(),
            value.optLong("updated"), value.optJSONArray("memberships")?.let { rows ->
                (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(Membership::fromJson) }
            }.orEmpty())
    }
}
