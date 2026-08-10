package com.geto.jido

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.geto.jido.databinding.ItemDownloadBinding

class DownloadsAdapter : ListAdapter<DownloadItem, DownloadsAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DownloadItem) {
            binding.fileNameText.text = item.fileName.ifBlank { item.sourceLink }

            val statusLabel = when (item.status) {
                DownloadStatus.FETCHING -> "Fetching media…"
                DownloadStatus.DOWNLOADING ->
                    if (item.progressPercent >= 0) "Downloading · ${item.progressPercent}%" else "Downloading…"
                DownloadStatus.SUCCESS -> "Done"
                DownloadStatus.FAILED -> "Failed"
            }
            binding.statusText.text = "${item.platform} · $statusLabel"

            val context = binding.root.context
            val (iconRes, colorRes) = when (item.status) {
                DownloadStatus.FETCHING -> R.drawable.ic_status_fetching to R.color.status_fetching
                DownloadStatus.DOWNLOADING -> R.drawable.ic_status_downloading to R.color.status_downloading
                DownloadStatus.SUCCESS -> R.drawable.ic_status_success to R.color.status_success
                DownloadStatus.FAILED -> R.drawable.ic_status_failed to R.color.status_failed
            }
            // Status color drives the icon and progress bar only — statusText stays neutral
            // (text_secondary) so the list doesn't turn into a wall of mixed colored text;
            // the icon carries the at-a-glance signal instead.
            val statusColor = ContextCompat.getColor(context, colorRes)
            binding.statusIcon.setImageResource(iconRes)
            binding.statusIcon.imageTintList = ColorStateList.valueOf(statusColor)

            binding.downloadProgressBar.apply {
                progressTintList = ColorStateList.valueOf(statusColor)
                when (item.status) {
                    DownloadStatus.FETCHING -> {
                        isIndeterminate = true
                    }
                    DownloadStatus.DOWNLOADING -> {
                        if (item.progressPercent >= 0) {
                            isIndeterminate = false
                            progress = item.progressPercent
                        } else {
                            isIndeterminate = true
                        }
                    }
                    DownloadStatus.SUCCESS -> {
                        isIndeterminate = false
                        progress = 100
                    }
                    DownloadStatus.FAILED -> {
                        isIndeterminate = false
                        progress = 0
                    }
                }
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem) = oldItem == newItem
        }
    }
}
