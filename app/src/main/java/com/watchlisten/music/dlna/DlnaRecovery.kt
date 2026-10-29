package com.watchlisten.music.dlna

import java.net.BindException
import java.util.concurrent.TimeUnit

/** Keep the previous LOCATION when possible; an occupied port alone may choose a new one. */
internal fun <T> bindDlnaPort(preferredPort: Int, bind: (Int) -> T): T {
    require(preferredPort == 0 || preferredPort in 1024..65535) { "投放端口无效" }
    return try { bind(preferredPort) }
    catch (failure: BindException) {
        if (preferredPort == 0) throw failure
        bind(0)
    }
}

/** Short receive/accept glitches get a bounded retry; a closed socket is handled by the caller. */
internal class DlnaListenerRetry {
    private var failures = 0
    fun failed(): Long? {
        failures++
        return if (failures >= 6) null else (200L shl (failures - 1)).coerceAtMost(2_000L)
    }
    fun recovered() { failures = 0 }
}

/** Per-subscription backoff retains SID and sequence until success, unsubscribe or lease expiry. */
internal class DlnaEventRetry {
    @Volatile private var failures = 0
    @Volatile private var nextAttemptNanos = Long.MIN_VALUE
    val hasFailures: Boolean get() = failures > 0

    fun ready(nowNanos: Long): Boolean = nowNanos >= nextAttemptNanos

    fun failed(nowNanos: Long) {
        failures = (failures + 1).coerceAtMost(6)
        val delayMs = (1_000L shl (failures - 1)).coerceAtMost(30_000L)
        val delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMs)
        nextAttemptNanos = if (nowNanos > Long.MAX_VALUE - delayNanos) Long.MAX_VALUE else nowNanos + delayNanos
    }

    fun recovered() { failures = 0; nextAttemptNanos = Long.MIN_VALUE }
}
