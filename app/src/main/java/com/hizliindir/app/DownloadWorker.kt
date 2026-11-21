package com.hizliindir.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bir indirmeyi arka planda yapar (uygulama kapansa bile devam eder).
 * Masaüstündeki core/media.py'nin Android karşılığı.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifId = id.hashCode()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(KEY_URL) ?: "", -1f, "Hazırlanıyor")

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val audio = inputData.getBoolean(KEY_AUDIO, false)
        val quality = inputData.getString(KEY_QUALITY) ?: "best"
        val playlist = inputData.getBoolean(KEY_PLAYLIST, false)
        val processId = id.toString()
        val tmp = File(applicationContext.cacheDir, "dl/$processId")
        var title = url

        try {
            setForeground(foregroundInfo(title, -1f, "Hazırlanıyor"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bazı cihazlar arka planda ön plan servisine izin vermez; indirme yine de denenir
        }

        try {
            tmp.deleteRecursively()
            tmp.mkdirs()
            setProgress(progressData(title, -1f, -1, "İndirme motoru hazırlanıyor…"))
            withContext(Dispatchers.IO) { Engine.ensureInit(applicationContext) }

            // Başlığı öğren (listede güzel görünsün)
            if (!playlist) {
                title = fetchTitle(url) ?: url
            }
            setProgress(progressData(title, -1f, -1, "Başlıyor…"))

            val request = YoutubeDLRequest(url).apply {
                addOption("-o", "${tmp.absolutePath}/%(title).120B [%(id)s].%(ext)s")
                addOption("--no-mtime")
                addOption("--no-cache-dir")
                addOption(if (playlist) "--yes-playlist" else "--no-playlist")
                if (audio) {
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-metadata")
                } else {
                    val f = if (quality == "best") "bv*+ba/b"
                    else "bv*[height<=$quality]+ba/b[height<=$quality]/b"
                    addOption("-f", f)
                    addOption("--merge-output-format", "mp4")
                }
            }

            var last = 0L
            try {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, processId, false) { progress, eta, line ->
                        val now = SystemClock.uptimeMillis()
                        if (now - last > 700) {
                            last = now
                            setProgressAsync(progressData(title, progress, eta, line))
                            setForegroundAsync(foregroundInfo(title, progress, statusText(progress, eta)))
                        }
                    }
                }
            } catch (e: CancellationException) {
                YoutubeDL.getInstance().destroyProcessById(processId)
                throw e
            }

            setProgress(progressData(title, 100f, -1, "Kaydediliyor…"))
            val saved = withContext(Dispatchers.IO) { Storage.publish(applicationContext, tmp) }
            if (saved.isEmpty()) throw IllegalStateException("İndirilen dosya bulunamadı")

            notifyDone(title, true, null)
            return Result.success(
                workDataOf(KEY_TITLE to title, KEY_URI to saved.first().toString(), KEY_COUNT to saved.size)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = friendlyError(e)
            notifyDone(title, false, msg)
            return Result.failure(workDataOf(KEY_TITLE to title, KEY_ERROR to msg))
        } finally {
            tmp.deleteRecursively()
        }
    }

    private suspend fun fetchTitle(url: String): String? {
        return try {
            runInterruptible(Dispatchers.IO) {
                val req = YoutubeDLRequest(url).addOption("--no-playlist")
                YoutubeDL.getInstance().getInfo(req).title
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ bildirimler

    private fun openAppIntent(): PendingIntent {
        val i = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            applicationContext, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun foregroundInfo(title: String, progress: Float, text: String): ForegroundInfo {
        val cancel = androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n: Notification = NotificationCompat.Builder(applicationContext, App.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setProgress(100, progress.coerceIn(0f, 100f).toInt(), progress < 0f)
            .addAction(0, "İptal", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    private fun notifyDone(title: String, ok: Boolean, error: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(applicationContext, App.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (ok) "İndirme tamamlandı" else "İndirme başarısız")
            .setContentText(if (ok) title else (error ?: title))
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (ok) title else "$title\n$error"))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        NotificationManagerCompat.from(applicationContext).notify(notifId + 1, n)
    }

    companion object {
        const val TAG_ALL = "hizliindir"
        const val KEY_URL = "url"
        const val KEY_AUDIO = "audio"
        const val KEY_QUALITY = "quality"
        const val KEY_PLAYLIST = "playlist"
        const val KEY_TITLE = "title"
        const val KEY_PROGRESS = "progress"
        const val KEY_ETA = "eta"
        const val KEY_LINE = "line"
        const val KEY_URI = "uri"
        const val KEY_COUNT = "count"
        const val KEY_ERROR = "error"

        fun progressData(title: String, progress: Float, eta: Long, line: String) = workDataOf(
            KEY_TITLE to title, KEY_PROGRESS to progress, KEY_ETA to eta, KEY_LINE to line.take(200)
        )

        fun statusText(progress: Float, eta: Long): String = when {
            progress < 0f -> "Hazırlanıyor"
            progress >= 100f -> "İşleniyor (birleştirme / MP3'e çevirme)"
            eta > 0 -> "%%%.1f · kalan %s".format(progress, formatEta(eta))
            else -> "%%%.1f".format(progress)
        }

        fun formatEta(sec: Long): String = when {
            sec < 60 -> "$sec sn"
            sec < 3600 -> "${sec / 60} dk ${sec % 60} sn"
            else -> "${sec / 3600} sa ${(sec % 3600) / 60} dk"
        }

        fun friendlyError(e: Exception): String {
            val raw = e.message ?: e.toString()
            val line = raw.lines().lastOrNull { it.contains("ERROR") } ?: raw.lines().firstOrNull() ?: raw
            val clean = line.substringAfter("ERROR:").trim()
            return when {
                clean.contains("Unsupported URL", true) -> "Bu site desteklenmiyor."
                clean.contains("Video unavailable", true) -> "Video kullanılamıyor (kaldırılmış ya da gizli)."
                clean.contains("Sign in to confirm", true) -> "YouTube giriş istiyor. Menüden indirme motorunu güncelleyip tekrar deneyin."
                clean.contains("Unable to download", true) || clean.contains("timed out", true) ->
                    "Bağlantı hatası. İnternetinizi kontrol edip tekrar deneyin."
                else -> clean.take(300)
            }
        }
    }
}
