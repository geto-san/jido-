package com.geto.jido

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.widget.Toast

/**
 * Fires when Android's DownloadManager finishes (or fails) a download that
 * ClipboardService enqueued. Confirms the outcome, indexes the file into the
 * public gallery immediately, and lets the user know via a Toast.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)

        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return

            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val localUriString = if (uriIndex != -1) cursor.getString(uriIndex) else null

                    Toast.makeText(context, "Download complete", Toast.LENGTH_SHORT).show()

                    localUriString?.let { uriString ->
                        Uri.parse(uriString).path?.let { path ->
                            scanFileForGallery(context, path)
                        }
                    }
                }

                DownloadManager.STATUS_FAILED -> {
                    val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIndex != -1) cursor.getInt(reasonIndex) else -1
                    Toast.makeText(context, "Download failed (code $reason)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun scanFileForGallery(context: Context, filePath: String) {
        MediaScannerConnection.scanFile(context, arrayOf(filePath), null) { _, _ ->
            // No-op: file is now indexed and visible in the gallery/Photos app.
        }
    }
}
