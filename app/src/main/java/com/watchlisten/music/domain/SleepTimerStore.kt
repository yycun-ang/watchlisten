package com.watchlisten.music.domain

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

data class SleepTimerSpec(val minutes: Int, val deadlineElapsedMs: Long, val deadlineWallMs: Long,
                          val bootCount: Int, val fadeOut: Boolean)

/** 单次启动内使用单调时钟；重启设备后不继续沿用旧定时。 */
class SleepTimerStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)
    private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun save(minutes: Int, fadeOut: Boolean): SleepTimerSpec {
        val delay = minutes.coerceAtLeast(0) * 60000L
        return SleepTimerSpec(minutes, if (minutes > 0) SystemClock.elapsedRealtime() + delay else 0,
            if (minutes > 0) System.currentTimeMillis() + delay else 0, bootCount(), fadeOut).also(::write)
    }

    fun restore(): SleepTimerSpec? {
        val minutes = preferences.getInt("minutes", 0)
        if (minutes == 0) return null
        val value = SleepTimerSpec(minutes, preferences.getLong("elapsed", 0), preferences.getLong("wall", 0),
            preferences.getInt("boot", -2), preferences.getBoolean("fade", false))
        return value.takeIf { it.bootCount >= 0 && it.bootCount == bootCount() }
            .also { if (it == null) clear() }
    }

    fun setFade(enabled: Boolean) { preferences.edit().putBoolean("fade", enabled).apply() }
    fun clear() { preferences.edit().remove("minutes").remove("elapsed").remove("wall").remove("boot").apply() }
    private fun write(value: SleepTimerSpec) {
        preferences.edit().putInt("minutes", value.minutes).putLong("elapsed", value.deadlineElapsedMs)
            .putLong("wall", value.deadlineWallMs).putInt("boot", value.bootCount).putBoolean("fade", value.fadeOut).apply()
    }
}
