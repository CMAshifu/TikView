package com.tikview.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Keeps TikView alive in the background while recording / watching for lives. */
class RecordService : Service() {
    companion object {
        @Volatile
        var instance: RecordService? = null
        private const val ID = 1
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "TikView is running"
        val n = build(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        locks(intent?.getBooleanExtra("recording", false) ?: false)
        return START_STICKY
    }

    fun update(text: String, recording: Boolean) {
        getSystemService(NotificationManager::class.java).notify(ID, build(text))
        locks(recording)
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, Native.CH_REC)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("TikView")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    /** While recording, keep the CPU and Wi-Fi awake even with the screen off. */
    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun locks(on: Boolean) {
        if (on) {
            if (wake == null) {
                wake = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TikView:record")
                    .apply { acquire() }
            }
            if (wifi == null) {
                wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TikView:record")
                    .apply { acquire() }
            }
        } else {
            wake?.let { if (it.isHeld) it.release() }
            wake = null
            wifi?.let { if (it.isHeld) it.release() }
            wifi = null
        }
    }

    override fun onDestroy() {
        locks(false)
        instance = null
        super.onDestroy()
    }
}
