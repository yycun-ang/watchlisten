package com.watchlisten.music

import com.watchlisten.music.data.*
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AuthorizedQualityPolicyTest {
    private fun source(preview: Boolean = false) = PlayableSource("https://test.kugou.com/audio", "mp3", 1000,
        preview = preview, access = if (preview) ListeningAccess.PREVIEW else ListeningAccess.FULL)

    @Test fun 无损未授权时先取已授权标准整曲再考虑试听() = runBlocking {
        val calls = mutableListOf<Pair<Quality, Boolean>>()
        val result = authorizedQualitySource(Quality.HIGH) { quality, preview ->
            calls += quality to preview
            if (quality == Quality.HIGH) throw MusicFailure(MusicError.PermissionDenied)
            source()
        }
        assertEquals(listOf(Quality.HIGH to false, Quality.STANDARD to false), calls)
        assertEquals(ListeningAccess.FULL, result.access)
        assertNotNull(result.qualityNote)
    }

    @Test fun 高档返回试听时仍查找较低档的正式整曲() = runBlocking {
        val result = authorizedQualitySource(Quality.HIGH) { tier, _ -> source(tier == Quality.HIGH) }
        assertEquals(ListeningAccess.FULL, result.access)
        assertFalse(result.preview)
    }

    @Test fun 所有完整档位拒绝后只使用服务端正式试听() = runBlocking {
        val calls = mutableListOf<Pair<Quality, Boolean>>()
        val result = authorizedQualitySource(Quality.HIGH) { tier, preview ->
            calls += tier to preview
            if (!preview) throw MusicFailure(MusicError.PermissionDenied)
            source(true)
        }
        assertEquals(listOf(Quality.HIGH to false, Quality.STANDARD to false, Quality.THROTTLED to false,
            Quality.THROTTLED to true), calls)
        assertTrue(result.preview)
    }

    @Test fun 没有试听权限时保留拒绝而不是伪造可播放() = runBlocking {
        try {
            authorizedQualitySource(Quality.HIGH) { _, _ -> throw MusicFailure(MusicError.PermissionDenied) }
            fail("未授权音源必须拒绝")
        } catch (failure: MusicFailure) { assertEquals(MusicError.PermissionDenied, failure.error) }
    }

    @Test fun 身份失效和网络限制不继续探测音质() = runBlocking {
        for (error in listOf(MusicError.AuthenticationExpired, MusicError.ConsentRequired,
            MusicError.MobileDataBlocked, MusicError.VerificationRequired, MusicError.NetworkUnavailable)) {
            var calls = 0
            try {
                authorizedQualitySource(Quality.HIGH) { _, _ -> calls++; throw MusicFailure(error) }
                fail("应保留限制")
            } catch (failure: MusicFailure) { assertEquals(error, failure.error) }
            assertEquals(1, calls)
        }
    }

    @Test fun 明确节流偏好不升级请求且实际码率缺失时保持未知() = runBlocking {
        val calls = mutableListOf<Quality>()
        authorizedQualitySource(Quality.THROTTLED) { tier, _ -> calls += tier; source() }
        assertEquals(listOf(Quality.THROTTLED), calls)
        assertNull(sourceBitrateKbps(0))
        assertEquals(320, sourceBitrateKbps(320))
        assertEquals(320, sourceBitrateKbps(320000))
        assertNull(sourceBitrateKbps(900000000))
    }

    @Test fun 取消请求不会被当作降档或试听() = runBlocking {
        var calls = 0
        try {
            authorizedQualitySource(Quality.HIGH) { _, _ -> calls++; throw CancellationException("cancel") }
            fail("必须取消")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }
}
