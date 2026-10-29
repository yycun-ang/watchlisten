package com.watchlisten.music.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.watchlisten.music.R
import com.watchlisten.music.WatchApplication
import com.watchlisten.music.ui.MainActivity

class LocalTransferService : Service() {
    private val transfer get() = (application as WatchApplication).graph.transfer
    private var runtimeSession = -1L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = intent?.getLongExtra(EXTRA_SESSION, -1L) ?: -1L
        if (intent?.action == ACTION_STOP) {
            if (transfer.stopIfSession(session) || !transfer.state.value.enabled) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!transfer.state.value.enabled || session != transfer.sessionRevision) {
            if (runtimeSession != transfer.sessionRevision) stopSelf(startId)
            return START_NOT_STICKY
        }
        runtimeSession = session
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "手机传歌", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 50, Intent(this, MainActivity::class.java)
            .putExtra("page", "transfer"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 51, Intent(this, javaClass).setAction(ACTION_STOP).putExtra(EXTRA_SESSION, session),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_brand)
            .setContentTitle("手机传歌已开启").setContentText("同一 Wi-Fi 下传歌，15 分钟后自动关闭")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止传歌", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, notification)
            transfer.startRuntime()
        } catch (_: Exception) { if (transfer.stopIfSession(session, "无法开启传歌，请保持应用在前台后重试")) stopSelf() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { transfer.stopIfSession(runtimeSession, transfer.state.value.error); super.onDestroy() }

    companion object {
        private const val CHANNEL = "local-transfer"
        private const val NOTIFICATION = 403
        private const val ACTION_STOP = "com.watchlisten.music.transfer.STOP"
        internal const val EXTRA_SESSION = "transfer_session"
    }
}
