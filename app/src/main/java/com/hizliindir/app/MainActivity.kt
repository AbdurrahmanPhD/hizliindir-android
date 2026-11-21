package com.hizliindir.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hizliindir.app.DownloadWorker.Companion.KEY_URI
import com.hizliindir.app.databinding.ActivityMainBinding
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val urlRegex = Regex("""https?://\S+""")
    private val qualities = listOf("En iyi" to "best", "1080p" to "1080", "720p" to "720", "480p" to "480", "360p" to "360", "4K (yeni telefonlar)" to "2160")

    private val adapter = JobAdapter(
        onCancel = { WorkManager.getInstance(this).cancelWorkById(it.info.id) },
        onOpen = { openResult(it) },
        onRetry = { startDownload(it.url, it.audio) },
    )

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        // Kalite seçimi
        b.quality.setSimpleItems(qualities.map { it.first }.toTypedArray())
        b.quality.setText(qualities.first().first, false)

        // Video / MP3
        b.modeGroup.check(R.id.btnVideo)
        b.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) b.qualityLayout.isEnabled = checkedId == R.id.btnVideo
        }

        b.urlLayout.setEndIconOnClickListener { pasteFromClipboard() }
        b.url.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { onDownloadClicked(); true } else false
        }
        b.download.setOnClickListener { onDownloadClicked() }

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null // ilerleme güncellenirken titremesin

        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(DownloadWorker.TAG_ALL)
            .observe(this) { infos ->
                val items = infos.map { JobItem(it) }.sortedByDescending { it.added }
                adapter.submitList(items)
                b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }

        requestPermissionsIfNeeded()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    // ------------------------------------------------------------------ indirme başlatma

    private fun onDownloadClicked() {
        val text = b.url.text?.toString()?.trim().orEmpty()
        val url = urlRegex.find(text)?.value
        if (url == null) {
            b.urlLayout.error = "Geçerli bir bağlantı girin (https:// ile başlamalı)"
            return
        }
        b.urlLayout.error = null
        startDownload(url, b.modeGroup.checkedButtonId == R.id.btnAudio)
        b.url.setText("")
    }

    private fun selectedQuality(): String {
        val label = b.quality.text?.toString()
        return qualities.firstOrNull { it.first == label }?.second ?: "best"
    }

    private fun startDownload(url: String, audio: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_AUDIO to audio,
                    DownloadWorker.KEY_QUALITY to selectedQuality(),
                    DownloadWorker.KEY_PLAYLIST to b.playlist.isChecked,
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DownloadWorker.TAG_ALL)
            .addTag("url:$url")
            .addTag(if (audio) "mode:audio" else "mode:video")
            .addTag("t:${System.currentTimeMillis()}")
            .build()
        WorkManager.getInstance(this).enqueue(request)
        Toast.makeText(this, if (audio) "MP3 indirmesi başladı" else "Video indirmesi başladı", Toast.LENGTH_SHORT).show()
    }

    /** YouTube vb. uygulamalardan "Paylaş > Hızlıİndir" ile gelen bağlantı */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = urlRegex.find(text)?.value ?: return
        intent.action = null // döndürünce tekrar sormasın
        b.url.setText(url)
        MaterialAlertDialogBuilder(this)
            .setTitle("Nasıl indirilsin?")
            .setMessage(url)
            .setPositiveButton("Video") { _, _ -> startDownload(url, false); b.url.setText("") }
            .setNegativeButton("MP3") { _, _ -> startDownload(url, true); b.url.setText("") }
            .setNeutralButton("Vazgeç", null)
            .show()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = urlRegex.find(text)?.value
        if (url != null) b.url.setText(url) else Toast.makeText(this, "Panoda bağlantı yok", Toast.LENGTH_SHORT).show()
    }

    private fun openResult(item: JobItem) {
        val uri = item.info.outputData.getString(KEY_URI)?.let(Uri::parse) ?: return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, "Aç"))
        } catch (e: Exception) {
            Toast.makeText(this, "Bu dosyayı açacak uygulama yok", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ menü

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_update -> updateEngine()
            R.id.action_clear -> WorkManager.getInstance(this).pruneWork()
            R.id.action_folder -> openDownloadsFolder()
            R.id.action_about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun updateEngine() {
        Toast.makeText(this, "İndirme motoru güncelleniyor…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    Engine.ensureInit(applicationContext)
                    val st = YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                    val v = YoutubeDL.getInstance().versionName(applicationContext) ?: ""
                    if (st == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "Zaten güncel ($v)" else "Güncellendi ($v)"
                } catch (e: Exception) {
                    "Güncellenemedi: ${e.message?.take(120)}"
                }
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun openDownloadsFolder() {
        // Dosya yöneticisini İndirilenler klasöründe açmayı dener
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2F" + Storage.FOLDER)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Dosyalar uygulamasında İndirilenler/Hizliindir klasörüne bakın", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAbout() {
        lifecycleScope.launch {
            val v = withContext(Dispatchers.IO) {
                try {
                    Engine.ensureInit(applicationContext)
                    YoutubeDL.getInstance().versionName(applicationContext)
                } catch (e: Exception) {
                    null
                }
            } ?: "?"
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Hızlıİndir")
                .setMessage(
                    "Sürüm ${packageManager.getPackageInfo(packageName, 0).versionName}\n" +
                        "İndirme motoru: yt-dlp $v\n\n" +
                        "Dosyalar: İndirilenler/Hizliindir\n\n" +
                        "Yalnızca indirme izniniz olan içerikleri indirin."
                )
                .setPositiveButton("Tamam", null)
                .show()
        }
    }

    // ------------------------------------------------------------------ izinler

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
