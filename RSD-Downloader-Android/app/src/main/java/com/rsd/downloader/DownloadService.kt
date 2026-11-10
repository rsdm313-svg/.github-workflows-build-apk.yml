package com.rsd.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Keeps the download alive when you leave the app, shows progress in the
 * notification shade, and offers a Cancel button.
 */
class DownloadService : Service() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_START = "start"
        private const val EXTRA_END = "end"
        private const val ACTION_CANCEL = "com.rsd.downloader.CANCEL"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ONGOING = 1001
        private const val NOTIF_RESULT = 1002

        fun start(ctx: Context, req: DownloadRequest) {
            val intent = Intent(ctx, DownloadService::class.java)
                .putExtra(EXTRA_URL, req.url)
                .putExtra(EXTRA_QUALITY, req.quality)
                .putExtra(EXTRA_START, req.startSec)
                .putExtra(EXTRA_END, req.endSec)
            ContextCompat.startForegroundService(ctx, intent)
        }
    }

    private var listener: ((JSONObject) -> Unit)? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_CANCEL) {
            Engine.cancel()
            return START_NOT_STICKY
        }
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val req = DownloadRequest(
            url = intent.getStringExtra(EXTRA_URL) ?: "",
            quality = intent.getStringExtra(EXTRA_QUALITY) ?: "best",
            startSec = intent.getIntExtra(EXTRA_START, -1),
            endSec = intent.getIntExtra(EXTRA_END, -1)
        )

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ONGOING, ongoing("Starting…", 0, true), type)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rsd:download").also {
            it.acquire(2 * 60 * 60 * 1000L)
        }

        val l: (JSONObject) -> Unit = { e ->
            when (e.optString("type")) {
                "progress" -> notifyOngoing("Downloading " + e.optInt("pct") + "%", e.optInt("pct"), false)
                "status" -> {
                    val kind = e.optString("kind")
                    if (kind == "info") notifyOngoing(e.optString("text"), Engine.snapshot().optInt("pct"), false)
                }
                else -> { }
            }
        }
        listener = l
        Engine.addListener(l)

        thread(name = "rsd-download") {
            try {
                Engine.runDownload(applicationContext, req)
            } finally {
                Engine.removeListener(l)
                val text = Engine.snapshot().optJSONObject("status")?.optString("text") ?: "Finished"
                try { wakeLock?.release() } catch (e: Throwable) { }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notifyResult(text)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        listener?.let { Engine.removeListener(it) }
        super.onDestroy()
    }

    /* ---------------- notifications ---------------- */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun ongoing(text: String, pct: Int, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("RSD Downloader")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun notifyOngoing(text: String, pct: Int, indeterminate: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ONGOING, ongoing(text, pct, indeterminate))
        } catch (e: Throwable) { }
    }

    private fun notifyResult(text: String) {
        try {
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("RSD Downloader")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_RESULT, n)
        } catch (e: Throwable) { }
    }
}
