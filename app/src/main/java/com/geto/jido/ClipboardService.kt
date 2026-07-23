package com.geto.jido

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

/**
 * Persistent foreground service that watches the system clipboard for links
 * from supported platforms, resolves the direct media URL via RapidAPI, and
 * hands the download to DownloadManager — while publishing live status into
 * DownloadRepository so MainActivity can show it.
 */
class ClipboardService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private val httpClient by lazy { OkHttpClient() }
    private val mediaResolver by lazy { MediaResolver(httpClient) }

    private lateinit var clipboardManager: ClipboardManager
    private var lastHandledClip: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clipText = clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?: return@OnPrimaryClipChangedListener

        // Avoid re-processing the same clip repeatedly (listener can fire more
        // than once for the same clipboard change on some OEM ROMs).
        if (clipText == lastHandledClip) return@OnPrimaryClipChangedListener

        val platform = SupportedLinks.detect(clipText) ?: return@OnPrimaryClipChangedListener
        lastHandledClip = clipText

        Log.d(TAG, "Detected $platform link on clipboard, starting download pipeline")
        updateNotification("Found a $platform link — fetching media…")
        serviceScope.launch { handleLink(clipText, platform) }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manualLink = intent?.getStringExtra(EXTRA_MANUAL_LINK)
        if (!manualLink.isNullOrBlank()) {
            handleManualLink(manualLink)
        }
        // Restart automatically if the system kills the process under memory pressure.
        return START_STICKY
    }

    /**
     * Entry point for links submitted through the "Paste link" UI in
     * MainActivity, instead of being picked up by the clipboard listener.
     * Reuses the exact same fetch/download pipeline as the clipboard path.
     */
    private fun handleManualLink(link: String) {
        val platform = SupportedLinks.detect(link)
        if (platform == null) {
            Log.w(TAG, "Manual link isn't a supported platform: $link")
            DownloadRepository.addItem(
                DownloadItem(
                    id = UUID.randomUUID().toString(),
                    platform = "Unknown",
                    sourceLink = link,
                    status = DownloadStatus.FAILED
                )
            )
            return
        }

        lastHandledClip = link // keep the clipboard listener from re-triggering on the same text
        Log.d(TAG, "Manual link submitted for $platform, starting download pipeline")
        updateNotification("Found a $platform link — fetching media…")
        serviceScope.launch { handleLink(link, platform) }
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------
    // Link -> API -> DownloadManager pipeline
    // ---------------------------------------------------------------------

    private suspend fun handleLink(link: String, platform: String) {
        val itemId = UUID.randomUUID().toString()
        DownloadRepository.addItem(
            DownloadItem(id = itemId, platform = platform, sourceLink = link, status = DownloadStatus.FETCHING)
        )

        var directUrl: String? = null
        
        // If the link is already a direct media URL (e.g. from yt-dlp), use it directly.
        if (link.contains("googlevideo.com") || link.contains("tiktokcdn.com") || 
            link.contains("cdninstagram.com") || link.endsWith(".mp4") || link.contains(".mp4?")) {
            directUrl = link
        }

        var retryCount = 0
        val maxRetries = 3

        while (directUrl.isNullOrBlank() && retryCount < maxRetries) {
            if (retryCount > 0) {
                Log.d(TAG, "Retrying resolution for $link (attempt ${retryCount + 1})")
                delay(2000) // wait 2s before retry
            }
            directUrl = withContext(Dispatchers.IO) { mediaResolver.resolve(link, platform) }
            retryCount++
        }

        if (directUrl.isNullOrBlank()) {
            Log.w(TAG, "Could not resolve a direct media URL for $link after $maxRetries attempts")
            DownloadRepository.updateItem(itemId) { it.copy(status = DownloadStatus.FAILED) }
            updateNotification("Listening for copied media links…")
            return
        }

        withContext(Dispatchers.Main) {
            try {
                val (downloadManagerId, fileName) = enqueueDownload(directUrl, platform)
                DownloadRepository.updateItem(itemId) {
                    it.copy(
                        status = DownloadStatus.DOWNLOADING,
                        fileName = fileName,
                        downloadManagerId = downloadManagerId,
                        progressPercent = 0
                    )
                }
                trackDownloadProgress(itemId, downloadManagerId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue download for $directUrl", e)
                DownloadRepository.updateItem(itemId) { it.copy(status = DownloadStatus.FAILED) }
            }
            updateNotification("Listening for copied media links…")
        }
    }

    /** Returns the DownloadManager request id and the chosen file name. */
    private fun enqueueDownload(directUrl: String, platform: String): Pair<Long, String> {
        val extension = MimeTypeMap.getFileExtensionFromUrl(directUrl)?.takeIf { it.isNotBlank() } ?: "mp4"
        val fileName = "${platform}_${System.currentTimeMillis()}.$extension"

        val request = DownloadManager.Request(Uri.parse(directUrl)).apply {
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            setTitle("Jido — $platform download")
            setDescription("Saving media to Downloads…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = downloadManager.enqueue(request)
        return id to fileName
    }

    /** Polls DownloadManager for progress until the download reaches a terminal state. */
    private fun trackDownloadProgress(itemId: String, downloadManagerId: Long) {
        serviceScope.launch(Dispatchers.IO) {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var finished = false

            while (!finished) {
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadManagerId))
                if (cursor == null) {
                    finished = true
                    continue
                }

                cursor.use {
                    if (!it.moveToFirst()) {
                        finished = true
                        return@use
                    }

                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloadedBytes =
                        it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val totalBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else -1

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            DownloadRepository.updateItem(itemId) {
                                it.copy(status = DownloadStatus.SUCCESS, progressPercent = 100)
                            }
                            finished = true
                        }
                        DownloadManager.STATUS_FAILED -> {
                            DownloadRepository.updateItem(itemId) { it.copy(status = DownloadStatus.FAILED) }
                            finished = true
                        }
                        else -> {
                            DownloadRepository.updateItem(itemId) { it.copy(progressPercent = percent) }
                        }
                    }
                }

                if (!finished) delay(750)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Foreground notification plumbing
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Link listener",
            NotificationManager.IMPORTANCE_LOW // low importance = silent, no heads-up popup
        ).apply {
            description = "Keeps Jido running so it can catch clipboard change"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jido")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_j)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "ClipboardService"
        private const val CHANNEL_ID = "jido_link_listener"
        private const val NOTIFICATION_ID = 101

        /** Intent extra key used by the "Paste link" UI to submit a link manually. */
        const val EXTRA_MANUAL_LINK = "com.geto.jido.extra.MANUAL_LINK"

        /** Builds the Intent MainActivity sends to run a manually-entered link through the pipeline. */
        fun manualLinkIntent(context: android.content.Context, link: String): Intent =
            Intent(context, ClipboardService::class.java).putExtra(EXTRA_MANUAL_LINK, link)
    }
}

/** Simple pattern matching for the platforms Jido currently supports. */
object SupportedLinks {
    private val patterns = mapOf(
        "Pinterest" to Regex("""pinterest\.com|pin\.it""", RegexOption.IGNORE_CASE),
        "Instagram" to Regex("""instagram\.com""", RegexOption.IGNORE_CASE),
        "TikTok" to Regex("""tiktok\.com|vm\.tiktok\.com""", RegexOption.IGNORE_CASE),
        "YouTube" to Regex("""youtube\.com|youtu\.be""", RegexOption.IGNORE_CASE),
        "Spotify" to Regex("""spotify\.com""", RegexOption.IGNORE_CASE)
    )

    /** Returns the matched platform name, or null if the text isn't a supported link. */
    fun detect(text: String): String? {
        if (!text.contains("http")) return null
        if (text.contains("googlevideo.com")) return "YouTube"
        if (text.contains("tiktokcdn.com")) return "TikTok"
        if (text.contains("cdninstagram.com")) return "Instagram"
        return patterns.entries.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.key
    }
}
