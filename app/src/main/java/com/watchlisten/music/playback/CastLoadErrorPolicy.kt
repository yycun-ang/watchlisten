package com.watchlisten.music.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/** Install only on the cast media-source factory: minimum retry count has no URI parameter. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CastLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(CAST_MAX_RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (!loadErrorInfo.loadEventInfo.uri.scheme.equals("watchlisten-cast", ignoreCase = true))
            return super.getRetryDelayMsFor(loadErrorInfo)
        if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
        return castRetryDelayMs(loadErrorInfo.exception, loadErrorInfo.errorCount)
    }
}

internal const val CAST_MAX_RETRIES = 6

/** Six consecutive failures without sample progress; Media3 resets its error count when loading advances. */
internal fun castRetryDelayMs(error: IOException, errorCount: Int): Long {
    if (errorCount !in 1..CAST_MAX_RETRIES) return C.TIME_UNSET
    var cause: Throwable? = error
    repeat(16) {
        val current = cause ?: return@repeat
        if (current is CastStreamException && !current.kind.retryable) return C.TIME_UNSET
        cause = current.cause?.takeUnless { it === current }
    }
    return (500L shl (errorCount - 1).coerceAtMost(3)).coerceAtMost(4_000L)
}
