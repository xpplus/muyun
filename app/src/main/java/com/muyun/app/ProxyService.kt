package com.muyun.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** 前台服务：保证代理隧道在切后台后不被系统冻结 */
class ProxyService : Service() {
    companion object {
        /** 由 MainVm 维护：当前是否真的有需要常驻的东西 */
        @Volatile var needed: Boolean = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须先 startForeground（5 秒内），否则系统抛
        // ForegroundServiceDidNotStartInTimeException 直接崩溃
        val ret = startForegroundInternal()
        if (!needed) stopSelf()
        return ret
    }

    private fun startForegroundInternal(): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = "clouddeck_proxy"
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(ch, "运行状态", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val n: Notification = Notification.Builder(this, ch)
                .setContentTitle("牧云运行中")
                .setContentText("灵脉已贯通 · 守护结界在线")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build()
            runCatching { startForeground(1001, n) }
                .onFailure { stopSelf() }
        }
        // 不要自动复活：避免"没开任何功能但通知常驻"
        return START_NOT_STICKY
    }
}
