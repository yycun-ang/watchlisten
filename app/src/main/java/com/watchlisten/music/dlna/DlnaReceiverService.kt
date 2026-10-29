package com.watchlisten.music.dlna

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

class DlnaReceiverService : Service() {
    private val receiver get() = (application as WatchApplication).graph.dlna

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !receiver.state.value.enabled) { receiver.disable(); stopSelf(); return START_NOT_STICKY }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "手机投放", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 30, Intent(this, MainActivity::class.java).putExtra("page", "dlna"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 31, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_brand)
            .setContentTitle("手机投放已开启").setContentText("同一 Wi-Fi 下可用酷狗投放到手表")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "关闭投放", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, notification)
            receiver.startRuntime()
        } catch (_: Exception) { receiver.stopRuntime("无法开启投放，请保持应用在前台后重试"); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { receiver.stopRuntime(receiver.state.value.error); super.onDestroy() }

    companion object {
        private const val CHANNEL = "phone-cast"
        private const val NOTIFICATION = 402
        private const val ACTION_STOP = "com.watchlisten.music.dlna.STOP"
    }
}
