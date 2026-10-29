package com.watchlisten.music.data

import com.watchlisten.music.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher

/** 协议来源 MakcRe/KuGouMusicApi（MIT），固定版本见 docs/account-v6-protocol.md。 */
internal object AccountProtocol {
    private const val PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"

    /** 服务端使用 1024 位 raw RSA，明文右补零；并非 PKCS#1。 */
    fun encryptPayload(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 128) throw MusicFailure(MusicError.InvalidResponse)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.doFinal(bytes.copyOf(128)).joinToString("") { "%02X".format(it.toInt() and 255) }
    }

    fun signature(params: Map<String, String>, body: String = "", web: Boolean = false): String {
        val salt = if (web) "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt" else "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        val text = salt + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + body + salt
        return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun profile(response: JSONObject, vipResponse: JSONObject?, nowMs: Long): UserProfile {
        val data = response.optJSONObject("data") ?: throw MusicFailure(MusicError.InvalidResponse)
        val info = data.optJSONObject("userinfo") ?: data.optJSONObject("user_info") ?: data
        val nickname = text(info, "nickname", "username", "user_name").take(80)
        val avatar = safeAvatar(text(info, "pic", "userpic", "user_pic", "avatar"))
        if (nickname.isBlank() && avatar.isBlank()) throw MusicFailure(MusicError.InvalidResponse)
        val memberships = vipResponse?.optJSONObject("data")?.let { vip ->
            val rows = vip.optJSONArray("busi_vip")
            val products = rows?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let { p -> membership(p, nowMs) } } }
                .orEmpty()
            if (products.isEmpty()) listOf(membership(vip, nowMs))
            else if (vip.optString("is_vip") in listOf("1", "true") &&
                products.none { it.effectiveStatus(nowMs) == MembershipStatus.ACTIVE })
                products + membership(vip, nowMs)
            else products
        }.orEmpty()
        val active = memberships.filter { it.effectiveStatus(nowMs) == MembershipStatus.ACTIVE }
        val primary = active.maxByOrNull { it.expiresAtMs } ?: when {
            memberships.isNotEmpty() && memberships.all { it.effectiveStatus(nowMs) == MembershipStatus.INACTIVE } ->
                Membership("暂无有效会员", MembershipStatus.INACTIVE)
            else -> Membership()
        }
        return UserProfile(nickname, avatar, text(info, "sign", "signature", "intro").take(200), primary, nowMs, memberships)
    }

    private fun membership(value: JSONObject, nowMs: Long): Membership {
        val product = value.optString("product_type")
        val label = when (product) {
            "svip" -> "概念版 VIP"
            "tvip" -> "畅听 VIP"
            "" -> "酷狗会员"
            else -> "会员权益（${product.take(16)}）"
        }
        val expiry = parseExpiry(text(value, "vip_end_time", "end_time", "expire_time"))
        val explicit = value.optString("is_vip")
        val status = when {
            expiry > 0 && expiry <= nowMs -> MembershipStatus.INACTIVE
            explicit == "1" || explicit == "true" -> MembershipStatus.ACTIVE
            explicit == "0" || explicit == "false" -> MembershipStatus.INACTIVE
            else -> MembershipStatus.UNKNOWN
        }
        return Membership(label, status, expiry, value.optInt("vip_level", value.optInt("level")))
    }

    /** 酷狗日期按北京时间解释；仅日期的到期时间包含当天。 */
    fun parseExpiry(raw: String): Long {
        raw.toLongOrNull()?.let { number ->
            return when { number <= 0 -> 0; number < 100000000000L -> number * 1000; else -> number }
        }
        if (raw.isBlank()) return 0
        val zone = ZoneId.of("Asia/Shanghai")
        return runCatching {
            if (raw.length == 10) LocalDate.parse(raw).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            else LocalDateTime.parse(raw.replace('T', ' ').take(19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(zone).toInstant().toEpochMilli()
        }.getOrDefault(0)
    }

    fun safeAvatar(raw: String): String {
        val url = raw.replace("{size}", "400").toHttpUrlOrNull() ?: return ""
        if (!CatalogGateway.allowedMediaHost(url.host)) return ""
        return url.newBuilder().scheme("https").build().toString()
    }

    fun qr(data: JSONObject, mid: String, nowMs: Long): QrCheck = when (data.optInt("status", -1)) {
        0 -> QrCheck(LoginPhase.EXPIRED)
        1 -> QrCheck(LoginPhase.WAITING)
        2 -> QrCheck(LoginPhase.SCANNED)
        4 -> {
            val token = field(data, "token")
            val userId = field(data, "userid")
            if (token.isBlank() || userId.toLongOrNull()?.let { it > 0 } != true) throw MusicFailure(MusicError.InvalidResponse)
            QrCheck(LoginPhase.CONFIRMED, SessionData(token, userId, mid, nowMs))
        }
        else -> throw MusicFailure(MusicError.InvalidResponse)
    }

    private fun text(value: JSONObject, vararg names: String): String = names.firstNotNullOfOrNull { name ->
        value.optString(name).takeUnless { it.isBlank() || it == "null" }
    }.orEmpty()

    private fun field(value: JSONObject, name: String, depth: Int = 0): String {
        text(value, name).takeIf { it.isNotBlank() }?.let { return it }
        if (depth >= 4) return ""
        value.keys().forEach { key -> value.optJSONObject(key)?.let {
            field(it, name, depth + 1).takeIf(String::isNotBlank)?.let { found -> return found }
        } }
        return ""
    }
}
