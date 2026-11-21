package com.hizliindir.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import com.hizliindir.app.DownloadWorker.Companion.KEY_COUNT
import com.hizliindir.app.DownloadWorker.Companion.KEY_ERROR
import com.hizliindir.app.DownloadWorker.Companion.KEY_ETA
import com.hizliindir.app.DownloadWorker.Companion.KEY_PROGRESS
import com.hizliindir.app.DownloadWorker.Companion.KEY_TITLE
import com.hizliindir.app.databinding.ItemJobBinding

/** WorkManager'daki bir indirme işinin listede gösterilen hâli. */
data class JobItem(val info: WorkInfo) {
    val url: String = info.tags.firstOrNull { it.startsWith("url:") }?.removePrefix("url:") ?: ""
    val audio: Boolean = "mode:audio" in info.tags
    val added: Long = info.tags.firstOrNull { it.startsWith("t:") }?.removePrefix("t:")?.toLongOrNull() ?: 0L
    val title: String = info.outputData.getString(KEY_TITLE)
        ?: info.progress.getString(KEY_TITLE)
        ?: url
}

class JobAdapter(
    private val onCancel: (JobItem) -> Unit,
    private val onOpen: (JobItem) -> Unit,
    private val onRetry: (JobItem) -> Unit,
) : ListAdapter<JobItem, JobAdapter.VH>(DIFF) {

    class VH(val b: ItemJobBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemJobBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = getItem(position)
        val info = item.info
        val b = h.b
        b.kind.text = if (item.audio) "MP3" else "VİDEO"
        b.title.text = item.title

        when (info.state) {
            WorkInfo.State.RUNNING -> {
                val p = info.progress.getFloat(KEY_PROGRESS, -1f)
                val eta = info.progress.getLong(KEY_ETA, -1L)
                showProgress(b, p)
                b.status.text = DownloadWorker.statusText(p, eta)
                action(b, "İptal") { onCancel(item) }
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                showProgress(b, -1f)
                b.status.text = "Sırada (internet bağlantısı bekleniyor)"
                action(b, "İptal") { onCancel(item) }
            }
            WorkInfo.State.SUCCEEDED -> {
                b.progress.visibility = View.GONE
                val count = info.outputData.getInt(KEY_COUNT, 1)
                b.status.text = if (count > 1) "Tamamlandı · $count dosya · İndirilenler/Hizliindir"
                else "Tamamlandı · İndirilenler/Hizliindir"
                action(b, "Aç") { onOpen(item) }
            }
            WorkInfo.State.FAILED -> {
                b.progress.visibility = View.GONE
                b.status.text = "Hata: " + (info.outputData.getString(KEY_ERROR) ?: "bilinmeyen hata")
                action(b, "Tekrar") { onRetry(item) }
            }
            WorkInfo.State.CANCELLED -> {
                b.progress.visibility = View.GONE
                b.status.text = "İptal edildi"
                action(b, "Tekrar") { onRetry(item) }
            }
        }
    }

    private fun showProgress(b: ItemJobBinding, p: Float) {
        val indeterminate = p < 0f || p >= 100f
        if (b.progress.isIndeterminate != indeterminate) {
            // Göstergenin türünü değiştirmeden önce gizlemek gerekiyor
            b.progress.visibility = View.INVISIBLE
            b.progress.isIndeterminate = indeterminate
        }
        if (!indeterminate) b.progress.setProgressCompat(p.toInt(), true)
        b.progress.visibility = View.VISIBLE
    }

    private fun action(b: ItemJobBinding, text: String, fn: () -> Unit) {
        b.action.text = text
        b.action.setOnClickListener { fn() }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<JobItem>() {
            override fun areItemsTheSame(a: JobItem, b: JobItem) = a.info.id == b.info.id
            override fun areContentsTheSame(a: JobItem, b: JobItem) = a.info == b.info
        }
    }
}
