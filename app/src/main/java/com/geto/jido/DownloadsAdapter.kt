package com.geto.jido

import android.view.LayoutInflater
import android.view.ViewGroup
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

            binding.downloadProgressBar.apply {
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
