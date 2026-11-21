package com.hizliindir.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // İlk açılışta motor (python + yt-dlp + ffmpeg) birkaç saniyede hazırlanır
        Thread {
            try {
                Engine.ensureInit(this)
                Engine.updateIfStale(this)
            } catch (e: Exception) {
                Log.e(TAG, "Motor başlatılamadı", e)
            }
        }.start()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "İndirme ilerlemesi", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Biten indirmeler", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    companion object {
        const val TAG = "Hizliindir"
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
    }
}

/** yt-dlp ve ffmpeg'i başlatır ve yt-dlp'yi güncel tutar. */
object Engine {
    @Volatile
    private var ready = false
    private val updateLock = Any()
    private const val PREFS = "engine"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val UPDATE_INTERVAL_MS = 12L * 60 * 60 * 1000 // 12 saat

    @Synchronized
    fun ensureInit(context: Context) {
        if (ready) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ready = true
    }

    /**
     * YouTube sık değiştiği için APK'nın içindeki yt-dlp kısa sürede eskir.
     * Son güncellemenin üzerinden 12 saat geçtiyse (ya da force ise) en yeni sürümü indirir.
     * @return güncelleme yapıldıysa true
     */
    fun updateIfStale(context: Context, force: Boolean = false): Boolean {
        return synchronized(updateLock) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_UPDATE, 0L) < UPDATE_INTERVAL_MS) return false
        return try {
            ensureInit(app)
            val status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
            Log.i(App.TAG, "yt-dlp güncelleme: $status, sürüm ${YoutubeDL.getInstance().versionName(app)}")
            status == YoutubeDL.UpdateStatus.DONE
        } catch (e: Exception) {
            Log.w(App.TAG, "yt-dlp güncellenemedi", e)
            false
        }
        }
    }
}
