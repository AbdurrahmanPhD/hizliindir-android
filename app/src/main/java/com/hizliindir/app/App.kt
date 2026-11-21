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

/** yt-dlp ve ffmpeg'i bir kez başlatır. */
object Engine {
    @Volatile
    private var ready = false

    @Synchronized
    fun ensureInit(context: Context) {
        if (ready) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ready = true
    }
}
