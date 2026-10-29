package com.watchlisten.music.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放超时自愈。
 *
 * 开始取源播放时设定超时闹钟；如果准备过程没有按期完成，
 * 则触发恢复动作，避免播放器长期停留在等待状态。
 * 本类负责计时与恢复回调，实际播放和进程管理由调用方处理。
 *
 * 自愈分两级：先尝试重新取源（多数情况是源过期或连接抖动），仍失败才重启进程。
 * 重启由外部提供动作，本类只负责判定与计时，不直接杀进程。
 */
class PlaybackWatchdog(
    private val context: Context,
    /** 自愈第一步：重新解析当前曲目的来源。返回 false 表示无法重试。 */
    private val onRetrySource: suspend () -> Boolean,
    /** 自愈第二步：应用彻底重启。 */
    private val onRestartApp: () -> Unit
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private val intent = Intent(ACTION_WATCHDOG).setPackage(context.packageName)
    private val pending: PendingIntent? = alarmManager?.let {
        PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private val mutableState = MutableStateFlow(WatchdogState())
    val state = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != ACTION_WATCHDOG) return
            mutableState.value = mutableState.value.copy(triggered = true)
            // 自愈在独立协程里执行，避免阻塞广播主线程。
            scope.launch {
                val recovered = runCatching { onRetrySource() }.getOrDefault(false)
                if (!recovered) {
                    Log.w(TAG, "重新取源未能恢复，重启应用")
                    onRestartApp()
                }
                cancel()
            }
        }
    }

    private var registered = false

    private fun ensureReceiver() {
        if (registered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION_WATCHDOG),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(ACTION_WATCHDOG))
        }
        registered = true
    }

    /**
     * 开始计时。只有存在当前曲目且尚未在计时中才生效，重复调用不会叠加闹钟。
     *
     * @param timeoutMs 期限内未进入可播放状态即触发自愈。
     */
    fun arm(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val target = pending ?: return
        ensureReceiver()
        val triggerAt = System.currentTimeMillis() + timeoutMs
        runCatching {
            alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, target)
        }.onFailure {
            // 部分 ROM 限制精确闹钟，退化为非精确闹钟即可，自愈不依赖精度。
            runCatching { alarmManager?.set(AlarmManager.RTC_WAKEUP, triggerAt, target) }
        }
        mutableState.value = WatchdogState(armed = true, deadlineAtMs = triggerAt)
    }

    /** 播放成功或用户主动操作后取消计时。 */
    fun cancel() {
        pending?.let { runCatching { alarmManager?.cancel(it) } }
        mutableState.value = WatchdogState()
    }

    fun release() {
        cancel()
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        scope.cancel()
    }

    data class WatchdogState(
        val armed: Boolean = false,
        val deadlineAtMs: Long = 0L,
        val triggered: Boolean = false
    )

    companion object {
        const val ACTION_WATCHDOG = "com.watchlisten.music.action.PLAYBACK_WATCHDOG"
        private const val REQUEST_CODE = 4200
        private const val TAG = "PlaybackWatchdog"

        /**
         * 默认期限。要留出取源与首帧解码的余量，同时不至于让用户干等；
         * 取值大于 [MusicError] 的慢网判定，避免与正常慢响应混淆。
         */
        const val DEFAULT_TIMEOUT_MS = 25_000L
    }
}