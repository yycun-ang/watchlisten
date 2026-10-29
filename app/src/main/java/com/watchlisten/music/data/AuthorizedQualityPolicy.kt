package com.watchlisten.music.data

import com.watchlisten.music.model.*
import kotlinx.coroutines.CancellationException

internal fun Quality.authorizedFallbacks(): List<Quality> = when (this) {
    Quality.HIGH -> listOf(Quality.HIGH, Quality.STANDARD, Quality.THROTTLED)
    Quality.STANDARD -> listOf(Quality.STANDARD, Quality.THROTTLED)
    Quality.THROTTLED -> listOf(Quality.THROTTLED)
}

/** 所有档位均走同一账号的正式授权接口，确认完整音源不可用后才采用试听。 */
internal suspend fun authorizedQualitySource(
    preferred: Quality,
    request: suspend (Quality, Boolean) -> PlayableSource
): PlayableSource {
    var preview: PlayableSource? = null
    var failure: MusicFailure? = null
    for (candidate in preferred.authorizedFallbacks()) {
        try {
            val source = request(candidate, false)
            if (source.preview || source.access == ListeningAccess.PREVIEW) {
                if (preview == null) preview = source
                continue
            }
            return if (candidate == preferred) source else source.copy(
                qualityNote = "所选音质暂不可用，已选择当前账号可播放的音质")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: MusicFailure) {
            if (error.error != MusicError.PermissionDenied && error.error != MusicError.TrackUnavailable) throw error
            if (failure?.error != MusicError.PermissionDenied) failure = error
        }
    }
    preview?.let { return it.copy(qualityNote = "当前来源为试听片段") }
    try {
        return request(Quality.THROTTLED, true).copy(qualityNote = "当前来源为试听片段")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: MusicFailure) {
        if (error.error in setOf(MusicError.AuthenticationExpired, MusicError.VerificationRequired,
                MusicError.ConsentRequired, MusicError.MobileDataBlocked)) throw error
        throw failure ?: error
    }
}

/** 接口可能以 bit/s 或 kbit/s 返回，未提供时不把请求档位当作实际码率。 */
internal fun sourceBitrateKbps(value: Long): Int? = when {
    value <= 0 -> null
    value > 10000 -> (value / 1000).takeIf { it in 1..10000 }?.toInt()
    else -> value.toInt()
}
