package com.tikview.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Android features the Python engine calls (via Chaquopy's jclass). */
@OptIn(UnstableApi::class)
object Native {
    const val CH_REC = "recording"
    const val CH_LIVE = "live"

    lateinit var app: Application
    val pool: ExecutorService = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val running = mutableSetOf<Transformer>()

    fun downloadsDir() = File(app.filesDir, "tikview/downloads")

    /** Re-packages a recorded .flv/.ts into .mp4 without re-encoding. Blocks until done. */
    @JvmStatic
    fun remux(inPath: String, outPath: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            try {
                lateinit var t: Transformer
                t = Transformer.Builder(app)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            ok = true
                            running.remove(t)
                            latch.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            running.remove(t)
                            latch.countDown()
                        }
                    })
                    .build()
                running.add(t)
                t.start(MediaItem.fromUri(Uri.fromFile(File(inPath))), outPath)
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.HOURS)
        return ok && File(outPath).length() > 0
    }

    /** text == null stops the background service; otherwise shows/updates its notification. */
    @JvmStatic
    fun serviceUpdate(text: String?, recording: Boolean) {
        main.post {
            val svc = RecordService.instance
            if (text == null) {
                svc?.stopSelf()
                return@post
            }
            if (svc != null) {
                svc.update(text, recording)
            } else {
                try {
                    val i = Intent(app, RecordService::class.java)
                        .putExtra("text", text)
                        .putExtra("recording", recording)
                    app.startForegroundService(i)
                } catch (_: Exception) {
                    // Android refuses to start it from the background; it'll start next time the app is open.
                }
            }
        }
    }

    /** "@user is live" notification. */
    @JvmStatic
    fun notifyLive(user: String, title: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = Intent(app, MainActivity::class.java)
            .putExtra("openUser", user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            app, user.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(app, CH_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("@$user is live")
            .setContentText(title.ifBlank { "Tap to watch" })
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java)
            .notify(1000 + (user.hashCode() and 0xffff), n)
    }

    /** Copies a file into the phone's gallery (Movies/TikView). Returns a message for the user. */
    @JvmStatic
    fun saveToGallery(path: String): String {
        val f = File(path)
        if (!f.exists()) return "File not found"
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "flv" -> "video/x-flv"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }
        val r = app.contentResolver
        fun insertInto(collection: Uri, dir: String): Uri? = try {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/TikView")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            r.insert(collection, v)
        } catch (_: Exception) {
            null
        }

        var where = "Gallery → Movies/TikView"
        var uri = insertInto(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES)
        if (uri == null) {  // e.g. .flv isn't accepted as a gallery video
            uri = insertInto(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DOWNLOADS)
            where = "Downloads/TikView"
        }
        if (uri == null) return "Couldn't save the file"
        return try {
            r.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            "Saved to $where ✓"
        } catch (e: Exception) {
            r.delete(uri, null, null)
            "Couldn't save: ${e.message}"
        }
    }
}
