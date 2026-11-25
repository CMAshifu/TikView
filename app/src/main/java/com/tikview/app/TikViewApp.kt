package com.tikview.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** The Python engine (tikview_core.py). */
fun core(): PyObject = Python.getInstance().getModule("tikview_core")

class TikViewApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Native.app = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Native.CH_REC, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(Native.CH_LIVE, "Live alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        // Start the engine early so auto-record keeps working if Android restarts the app.
        Native.pool.execute {
            try { core().callAttr("init", filesDir.absolutePath) } catch (_: Throwable) { }
        }
    }
}
