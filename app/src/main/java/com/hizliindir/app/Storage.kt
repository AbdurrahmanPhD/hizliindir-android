package com.hizliindir.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** İndirilen dosyaları telefonun "İndirilenler/Hizliindir" klasörüne taşır. */
object Storage {

    const val FOLDER = "Hizliindir"

    fun publish(context: Context, dir: File): List<Uri> {
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .filterNot { f ->
                val n = f.name
                n.startsWith(".") || n.endsWith(".part") || n.endsWith(".ytdl") ||
                    n.endsWith(".temp") || n.contains(".part-Frag")
            }
            .toList()
        return files.map { save(context, it) }
    }

    private fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"

    private fun save(context: Context, f: File): Uri {
        return if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(f))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Dosya İndirilenler klasörüne kaydedilemedi")
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("Dosya İndirilenler klasörüne yazılamadı")
            out.use { o -> f.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            outDir.mkdirs()
            var target = File(outDir, f.name)
            var n = 2
            while (target.exists()) {
                target = File(outDir, "${f.nameWithoutExtension} ($n).${f.extension}")
                n++
            }
            f.copyTo(target)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            FileProvider.getUriForFile(context, context.packageName + ".files", target)
        }
    }
}
