package com.watchlisten.music

import com.watchlisten.music.data.*
import com.watchlisten.music.model.MusicError
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsPrivacyTest {
    @Test fun 未知错误的自由文本不能进入事件() {
        val secret = "token=private; http://private.invalid/song; UID=123"
        val code = diagnosticError(MusicError.Unknown(secret))
        assertEquals(DiagnosticCode.UNKNOWN_ERROR, code)
        assertFalse(eventJson(listOf(DiagnosticEvent(100, DiagnosticArea.PLAYBACK, code))).toString().contains(secret))
    }
    @Test fun 事件只有时间区域和白名单状态() {
        val row = eventJson(listOf(DiagnosticEvent(123, DiagnosticArea.CLOUD, DiagnosticCode.AUTH_EXPIRED))).getJSONObject(0)
        assertEquals(setOf("timeMs", "area", "code"), row.keys().asSequence().toSet())
        assertEquals("AUTH_EXPIRED", row.getString("code"))
    }
    @Test fun 网络和权限错误保留可诊断类型() {
        assertEquals(DiagnosticCode.DNS_ERROR, diagnosticError(MusicError.DnsUnavailable))
        assertEquals(DiagnosticCode.PERMISSION_DENIED, diagnosticError(MusicError.PermissionDenied))
        assertEquals(DiagnosticCode.NONE, diagnosticError(null))
    }
}
