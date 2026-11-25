package com.tikview.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pageReady = false
    private val pendingJs = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(getColor(R.color.bg))
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            // Links to websites open in the browser, not inside the app
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingJs.forEach { web.evaluateJavascript(it, null) }
                pendingJs.clear()
            }
        }

        handleIntent(intent)

        // Make sure the engine is ready, then show the UI
        Native.pool.execute {
            try { core().callAttr("init", filesDir.absolutePath) } catch (_: Throwable) { }
            runOnUiThread { web.loadUrl("file:///android_asset/index.html") }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Back: close the viewer if open, otherwise go to the home screen (recordings keep running)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.handleBack ? window.handleBack() : false") { r ->
                    if (r != "true") moveTaskToBack(true)
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun runJs(js: String) {
        if (pageReady) web.evaluateJavascript(js, null) else pendingJs.add(js)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        i.getStringExtra("openUser")?.let { runJs("window.openUser(${JSONObject.quote(it)})") }
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = Regex("https?://\\S*tiktok\\.com\\S*").find(text)?.value ?: return
            runJs("window.openShared(${JSONObject.quote(url)})")
        }
    }

    private fun safeFile(name: String): File? {
        if (name.contains('/') || name.contains("..")) return null
        return File(Native.downloadsDir(), name).takeIf { it.exists() }
    }

    /** Functions the web UI can call as window.Android.* */
    inner class Bridge {
        @JavascriptInterface
        fun request(id: Int, method: String, path: String, body: String) {
            Native.pool.execute {
                val res = try {
                    core().callAttr("handle", method, path, body).toString()
                } catch (e: Throwable) {
                    JSONObject().put("status", 500)
                        .put("body", JSONObject().put("error", e.message ?: "Error")).toString()
                }
                runOnUiThread {
                    web.evaluateJavascript("window.__bridgeResolve($id, ${JSONObject.quote(res)})", null)
                }
            }
        }

        @JavascriptInterface
        fun playFile(name: String, title: String) {
            val f = safeFile(name) ?: return
            startActivity(
                Intent(this@MainActivity, PlayerActivity::class.java)
                    .putExtra("url", Uri.fromFile(f).toString())
                    .putExtra("title", title)
            )
        }

        @JavascriptInterface
        fun playStream(url: String, headersJson: String, title: String) {
            startActivity(
                Intent(this@MainActivity, PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("headers", headersJson)
                    .putExtra("title", title)
                    .putExtra("live", true)
            )
        }

        /** Light/dark chosen in TikView: match the phone's status and navigation bars. */
        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun setBars(dark: Boolean) {
            runOnUiThread {
                val c = if (dark) 0xFF0F0F12.toInt() else 0xFFF6F6F8.toInt()
                window.statusBarColor = c
                window.navigationBarColor = c
                web.setBackgroundColor(c)
                WindowInsetsControllerCompat(window, web).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }

        @JavascriptInterface
        fun saveToPhone(name: String) {
            Native.pool.execute {
                val f = safeFile(name)
                val msg = if (f == null) "File not found" else Native.saveToGallery(f.absolutePath)
                runOnUiThread { web.evaluateJavascript("toast(${JSONObject.quote(msg)}, 5000)", null) }
            }
        }
    }
}
