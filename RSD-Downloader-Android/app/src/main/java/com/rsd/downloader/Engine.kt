package com.rsd.downloader

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class DownloadRequest(
    val url: String,
    val quality: String,   // "best", "1080", "720", "480", "bestaudio"
    val startSec: Int,     // -1 = whole video
    val endSec: Int        // -1 = whole video
)

internal data class Saved(val uri: String, val display: String)

/**
 * Everything that touches the yt-dlp library lives here, so the rest of the app
 * only deals with plain events (JSON) and never with the library directly.
 */
object Engine {
    private const val DOWNLOAD_ID = "rsd-download"
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav")

    @Volatile var ready = false
    @Volatile var initError: String? = null
    @Volatile var busy = false
    @Volatile private var cancelled = false
    @Volatile private var metaId = ""

    private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    @Volatile private var lastStatus: JSONObject? = null
    @Volatile private var lastPct = 0
    @Volatile private var lastEta = ""
    @Volatile private var lastDone: JSONObject? = null

    /* ---------------- events ---------------- */

    fun addListener(l: (JSONObject) -> Unit) { listeners.add(l) }
    fun removeListener(l: (JSONObject) -> Unit) { listeners.remove(l) }

    private fun emit(o: JSONObject) {
        for (l in listeners) {
            try { l(o) } catch (e: Throwable) { /* a broken listener must not stop a download */ }
        }
    }

    private fun status(kind: String, text: String) {
        val o = JSONObject().put("type", "status").put("kind", kind).put("text", text)
        lastStatus = o
        emit(o)
    }

    private fun log(text: String) {
        emit(JSONObject().put("type", "log").put("text", text))
    }

    private fun setBusy(b: Boolean) {
        busy = b
        emit(JSONObject().put("type", "state").put("busy", b))
    }

    fun snapshot(): JSONObject {
        val o = JSONObject()
            .put("type", "snapshot")
            .put("busy", busy)
            .put("pct", lastPct)
            .put("eta", lastEta)
        lastStatus?.let { o.put("status", it) }
        lastDone?.let { o.put("done", it) }
        return o
    }

    /** Claims the "one download at a time" slot. Returns false if a download is already running. */
    @Synchronized
    fun tryReserve(): Boolean {
        if (busy) return false
        busy = true
        return true
    }

    @Synchronized
    fun releaseReservation() { busy = false }

    /* ---------------- initialisation ---------------- */

    fun init(app: Application) {
        thread(name = "rsd-init") {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                ready = true
            } catch (e: Throwable) {
                initError = e.message ?: e.javaClass.simpleName
            }
            emit(componentsEvent())
        }
    }

    private fun awaitReady(): Boolean {
        var waited = 0
        while (!ready && initError == null && waited < 120_000) {
            Thread.sleep(250)
            waited += 250
        }
        return ready
    }

    private fun componentsEvent(): JSONObject {
        var version: String? = null
        if (ready) {
            try {
                val request = YoutubeDLRequest(emptyList<String>())
                request.addOption("--version")
                val r = YoutubeDL.getInstance().execute(request)
                version = r.out.trim().lines().lastOrNull { it.isNotBlank() }?.trim()
            } catch (e: Throwable) { /* shown as missing */ }
        }
        return JSONObject()
            .put("type", "components")
            .put("ready", ready)
            .put("ytdlp", version ?: JSONObject.NULL)
            .put("ffmpeg", ready)
            .put("error", initError ?: JSONObject.NULL)
    }

    fun refreshComponents() {
        thread(name = "rsd-components") {
            awaitReady()
            emit(componentsEvent())
        }
    }

    /* ---------------- duration lookup (for "Selected Area") ---------------- */

    fun fetchDuration(url: String, requestId: Int) {
        thread(name = "rsd-meta") {
            var seconds: Double? = null
            if (awaitReady()) {
                val previous = metaId
                val id = "rsd-meta-$requestId"
                metaId = id
                try {
                    if (previous.isNotEmpty()) YoutubeDL.getInstance().destroyProcessById(previous)
                } catch (e: Throwable) { }
                try {
                    val request = YoutubeDLRequest(url)
                    request.addOption("--no-warnings")
                    request.addOption("--no-playlist")
                    request.addOption("--skip-download")
                    request.addOption("--print", "%(duration)s")
                    val r = YoutubeDL.getInstance().execute(request, id)
                    val number = Regex("^\\d+(\\.\\d+)?$")
                    val line = r.out.lines().map { it.trim() }.lastOrNull { number.matches(it) }
                    seconds = line?.toDouble()
                } catch (e: Throwable) { /* unknown duration */ }
            }
            emit(
                JSONObject().put("type", "duration").put("id", requestId)
                    .put("seconds", seconds ?: JSONObject.NULL)
            )
        }
    }

    /* ---------------- yt-dlp updater ---------------- */

    fun updateYtDlp(ctx: Context) {
        thread(name = "rsd-update") {
            emit(JSONObject().put("type", "updating").put("on", true))
            try {
                if (!awaitReady()) throw IllegalStateException(initError ?: "Components are not ready yet")
                status("info", "Updating yt-dlp…")
                YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext)
                status("ok", "yt-dlp is up to date ✓")
            } catch (e: Throwable) {
                status("err", "Update failed: " + shortError(e))
            }
            emit(JSONObject().put("type", "updating").put("on", false))
            emit(componentsEvent())
        }
    }

    /* ---------------- download ---------------- */

    fun cancel() {
        cancelled = true
        try { YoutubeDL.getInstance().destroyProcessById(DOWNLOAD_ID) } catch (e: Throwable) { }
    }

    /** Blocking. Call from a worker thread (DownloadService does). */
    fun runDownload(ctx: Context, req: DownloadRequest) {
        cancelled = false
        lastDone = null
        lastPct = 0
        lastEta = ""
        setBusy(true)
        status("info", "Preparing…")

        val workDir = File(ctx.cacheDir, "dl/" + System.currentTimeMillis())
        try {
            if (!awaitReady()) {
                throw IllegalStateException(initError ?: "Downloader components are not ready yet")
            }
            workDir.mkdirs()
            val request = buildRequest(req, workDir)

            status("info", "Downloading…")
            YoutubeDL.getInstance().execute(request, DOWNLOAD_ID) { progress, eta, line ->
                handleOutput(progress, eta, line)
            }

            val file = pickOutput(workDir)
                ?: throw IllegalStateException("Download finished, but no media file was produced.")

            status("info", "Saving to your phone…")
            val isAudio = req.quality == "bestaudio" || AUDIO_EXT.contains(file.extension.lowercase())
            val saved = saveToPhone(ctx, file, isAudio)

            lastPct = 100
            val done = JSONObject()
                .put("type", "done")
                .put("name", file.name)
                .put("display", saved.display)
                .put("uri", saved.uri)
            lastDone = done
            emit(done)
            status("ok", "Saved ✓  " + file.name)
        } catch (e: Throwable) {
            if (cancelled) {
                status("warn", "Cancelled")
            } else {
                log("\n[error] " + (e.message ?: e.javaClass.simpleName) + "\n")
                status("err", "Failed: " + shortError(e))
            }
        } finally {
            try { workDir.deleteRecursively() } catch (e: Throwable) { }
            setBusy(false)
        }
    }

    private fun handleOutput(progress: Float, eta: Long, line: String) {
        if (progress >= 0f) {
            lastPct = progress.toInt()
            lastEta = if (eta > 0) formatEta(eta) else ""
            emit(JSONObject().put("type", "progress").put("pct", lastPct).put("eta", lastEta))
            status("info", "Downloading $lastPct%")
        }
        val t = line.trim()
        if (t.isNotEmpty()) log(t + "\n")
        if (t.contains("[Merger]") || t.contains("Merging")) status("info", "Merging…")
        if (t.contains("[ExtractAudio]")) status("info", "Converting to MP3…")
    }

    private fun buildRequest(req: DownloadRequest, dir: File): YoutubeDLRequest {
        val request = YoutubeDLRequest(req.url)
        val ranged = req.startSec >= 0 && req.endSec > req.startSec

        val suffix = if (ranged) {
            " [selection " + timecode(req.startSec).replace(':', '-') + "-" + timecode(req.endSec).replace(':', '-') + "]"
        } else ""
        request.addOption("-o", File(dir, "%(title).80s" + suffix + ".%(ext)s").absolutePath)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("--windows-filenames")
        request.addOption("--newline")

        if (ranged) {
            // On a phone the cut starts at the nearest keyframe (no re-encode, so it is fast).
            request.addOption("--download-sections", "*" + timecode(req.startSec) + "-" + timecode(req.endSec))
        }

        if (req.quality == "bestaudio") {
            request.addOption("-f", "bestaudio/best")
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
        } else {
            val h = req.quality.toIntOrNull()
            val selector = if (h != null) "bv*[height<=$h]+ba/b[height<=$h]" else "bv*+ba/b"
            request.addOption("-f", selector)
            // Prefer H.264 + AAC so the result plays everywhere (gallery, editors) without re-encoding.
            request.addOption("-S", "vcodec:h264,res,acodec:m4a")
            request.addOption("--merge-output-format", "mp4")
        }
        return request
    }

    private fun pickOutput(dir: File): File? {
        val skip = listOf(".part", ".ytdl", ".json", ".temp", ".txt", ".tmp")
        return dir.listFiles()
            ?.filter { f -> f.isFile && skip.none { f.name.endsWith(it) } }
            ?.maxByOrNull { it.length() }
    }

    /* ---------------- saving into Movies / Music ---------------- */

    private fun mimeFor(file: File, audio: Boolean): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> if (audio) "audio/webm" else "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        else -> if (audio) "audio/mpeg" else "video/mp4"
    }

    private fun saveToPhone(ctx: Context, file: File, audio: Boolean): Saved {
        val name = file.name
        val mime = mimeFor(file, audio)
        val folder = if (audio) "Music" else "Movies"
        val relative = "$folder/RSD Downloader"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val collection = if (audio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Could not create the file in $relative")
            try {
                val out = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open the output file")
                out.use { stream -> file.inputStream().use { input -> input.copyTo(stream) } }
                val done = ContentValues()
                done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, done, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
            return Saved(uri.toString(), "$relative/$name")
        }

        // Android 7–9: write straight into the public folder.
        val base = Environment.getExternalStoragePublicDirectory(
            if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        )
        val dir = File(base, "RSD Downloader")
        dir.mkdirs()
        val target = uniqueFile(dir, name)
        file.copyTo(target, overwrite = true)

        val latch = CountDownLatch(1)
        var scannedUri: String? = null
        MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime)) { _, uri ->
            scannedUri = uri?.toString()
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return Saved(scannedUri ?: "", "$relative/${target.name}")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return f
    }

    /* ---------------- helpers ---------------- */

    private fun timecode(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun formatEta(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun shortError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        val lines = msg.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.lastOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull() ?: msg
        return line.take(200)
    }
}
