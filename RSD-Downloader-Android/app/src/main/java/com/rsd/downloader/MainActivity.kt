package com.rsd.downloader

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

/**
 * The whole UI is a local web page (assets/www) shown in a WebView.
 * Native work (clipboard, downloading, saving, sharing) goes through [Bridge].
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pageLoaded = false
    private var pendingShare: String? = null
    private var pendingRequest: DownloadRequest? = null

    private val engineListener: (JSONObject) -> Unit = { e -> pushEvent(e) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#1A1A1A"))
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true      // remembers quality / auto-paste
        web.settings.allowContentAccess = false
        web.settings.allowFileAccess = false       // file:///android_asset is still readable
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            // The UI is fully local: never navigate anywhere.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                pushEvent(Engine.snapshot())
                Engine.refreshComponents()
                pushShare()
                runJs("window.RSD&&window.RSD.onAppFocus()")
            }
        }
        web.loadUrl("file:///android_asset/www/index.html")

        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
        }
    }

    override fun onStart() {
        super.onStart()
        Engine.addListener(engineListener)
        if (pageLoaded) {
            pushEvent(Engine.snapshot())
            Engine.refreshComponents()
        }
    }

    override fun onStop() {
        Engine.removeListener(engineListener)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // Clipboard reads are only allowed while the window has focus (Android 10+).
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pageLoaded) runJs("window.RSD&&window.RSD.onAppFocus()")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            val req = pendingRequest
            pendingRequest = null
            if (req != null && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginDownload(req)
            } else {
                Toast.makeText(this, "Storage permission is needed to save downloads.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /* ---------------- helpers ---------------- */

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                pendingShare = text
                pushShare()
            }
        }
    }

    private fun pushShare() {
        val text = pendingShare ?: return
        if (!pageLoaded) return
        pendingShare = null
        runJs("window.RSD&&window.RSD.onShared(" + JSONObject.quote(text) + ")")
    }

    private fun runJs(script: String) {
        runOnUiThread {
            if (!isDestroyed) web.evaluateJavascript(script, null)
        }
    }

    private fun pushEvent(e: JSONObject) {
        if (!pageLoaded) return
        runJs("window.RSD&&window.RSD.onEvent(" + JSONObject.quote(e.toString()) + ")")
    }

    private fun pushStatus(kind: String, text: String) {
        pushEvent(JSONObject().put("type", "status").put("kind", kind).put("text", text))
    }

    private fun beginDownload(req: DownloadRequest) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRequest = req
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 42)
            return
        }
        if (!Engine.tryReserve()) {
            pushStatus("warn", "A download is already running")
            return
        }
        try {
            DownloadService.start(this, req)
        } catch (e: Throwable) {
            Engine.releaseReservation()
            pushStatus("err", "Could not start the download service: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /* ---------------- JavaScript bridge ---------------- */

    inner class Bridge {

        @JavascriptInterface
        fun requestClipboard() {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                var text = ""
                if (clip != null && clip.itemCount > 0) {
                    text = clip.getItemAt(0).coerceToText(this@MainActivity)?.toString() ?: ""
                }
                web.evaluateJavascript("window.RSD&&window.RSD.onClipboard(" + JSONObject.quote(text) + ")", null)
            }
        }

        @JavascriptInterface
        fun startDownload(url: String, quality: String, startSec: Int, endSec: Int) {
            runOnUiThread { beginDownload(DownloadRequest(url, quality, startSec, endSec)) }
        }

        @JavascriptInterface
        fun cancelDownload() { Engine.cancel() }

        @JavascriptInterface
        fun lookupDuration(url: String, requestId: Int) { Engine.fetchDuration(url, requestId) }

        @JavascriptInterface
        fun updateYtDlp() { Engine.updateYtDlp(applicationContext) }

        @JavascriptInterface
        fun refreshComponents() { Engine.refreshComponents() }

        @JavascriptInterface
        fun openFile(uri: String) {
            runOnUiThread {
                try {
                    val u = Uri.parse(uri)
                    val type = contentResolver.getType(u) ?: "video/*"
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(u, type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No app found to open this file.", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(this@MainActivity, "Could not open the file.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun shareFile(uri: String) {
            runOnUiThread {
                try {
                    val u = Uri.parse(uri)
                    val type = contentResolver.getType(u) ?: "video/*"
                    val intent = Intent(Intent.ACTION_SEND)
                        .setType(type)
                        .putExtra(Intent.EXTRA_STREAM, u)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(intent, "Share"))
                } catch (e: Throwable) {
                    Toast.makeText(this@MainActivity, "Could not share the file.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun getSnapshot() { pushEvent(Engine.snapshot()) }
    }
}
