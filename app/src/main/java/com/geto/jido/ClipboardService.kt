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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Persistent foreground service that watches the system clipboard for links
 * from supported platforms, resolves the direct media URL via RapidAPI, and
 * hands the download to a fast multi-connection downloader — while publishing
 * live status into DownloadRepository so MainActivity can show it.
 */
class ClipboardService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    // Tuned for racing several fallback resolver APIs concurrently: fails fast
    // into the next candidate rather than sitting on OkHttp's 10s defaults, and
    // allows enough concurrent requests per host that racing isn't itself the
    // bottleneck.
    private val apiClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8; maxRequests = 32 })
            .connectionPool(ConnectionPool(8, 60, TimeUnit.SECONDS))
            .build()
    }

    // Separate client for the actual file transfer: longer timeouts appropriate
    // for large media, and a bigger per-host limit + connection pool since
    // SegmentedDownloader deliberately opens several concurrent range requests
    // against the same CDN host.
    private val downloadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8; maxRequests = 32 })
            .connectionPool(ConnectionPool(8, 60, TimeUnit.SECONDS))
            .build()
    }

    private val mediaResolver by lazy { MediaResolver(apiClient) }
    private val segmentedDownloader by lazy { SegmentedDownloader(downloadClient) }

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
        showDownloadAddedNotification(platform)
        serviceScope.launch { handleLink(clipText, platform) }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()

        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        
        // Initialize lastHandledClip with current clipboard content to avoid 
        // downloading what's already there upon service startup.
        lastHandledClip = clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()

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
        Log.d(TAG, "Link submitted for $platform, starting download pipeline")
        showDownloadAddedNotification(platform)
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
            DownloadItem(
                id = itemId,
                platform = platform,
                sourceLink = link,
                status = DownloadStatus.FETCHING,
            )
        )

        var directUrl: String? = null
        
        // If the link is already a direct media URL (e.g. from yt-dlp), use it directly.
        if (link.contains("googlevideo.com") || link.contains("tiktokcdn.com") || 
            link.contains("cdninstagram.com") || link.endsWith(".mp4") || link.contains(".mp4?")) {
            directUrl = link
        }

        // resolve() already races every fallback endpoint concurrently (see
        // MediaResolver), so a single attempt already gets the benefit of
        // redundancy across providers — this outer retry only needs to cover
        // a genuinely transient failure (e.g. a momentary network blip), not
        // compensate for one dead endpoint blocking the others like before.
        var retryCount = 0
        val maxRetries = 2

        while (directUrl.isNullOrBlank() && (retryCount < maxRetries)) {
            if (retryCount > 0) {
                Log.d(TAG, "Retrying resolution for $link (attempt ${retryCount + 1})")
                delay(400L)
            }
            directUrl = mediaResolver.resolve(link, platform)
            retryCount++
        }

        if (directUrl.isNullOrBlank()) {
            Log.w(TAG, "Could not resolve a direct media URL for $link after $maxRetries attempts")
            DownloadRepository.updateItem(itemId) { it.copy(status = DownloadStatus.FAILED) }
            updateNotification("Listening for copied media links…")
            return
        }

        DownloadRepository.updateItem(itemId) {
            it.copy(status = DownloadStatus.DOWNLOADING, progressPercent = 0)
        }

        try {
            downloadFast(itemId, directUrl, platform)
        } catch (e: Exception) {
            Log.w(TAG, "Fast segmented download failed for $directUrl, falling back to DownloadManager: ${e.message}")
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
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "DownloadManager fallback also failed for $directUrl", fallbackError)
                    DownloadRepository.updateItem(itemId) { it.copy(status = DownloadStatus.FAILED) }
                }
            }
        }
        updateNotification("Listening for copied media links…")
    }

    /**
     * Fast path: pulls the file with [SegmentedDownloader] (multiple concurrent
     * byte-range connections when the server supports it) instead of handing
     * off to Android's single-connection DownloadManager. Throws on any
     * failure so the caller can fall back to DownloadManager.
     */
    private suspend fun downloadFast(itemId: String, directUrl: String, platform: String) {
        val result = segmentedDownloader.download(
            context = this,
            url = directUrl,
            platform = platform,
        ) { progress ->
            val percent = if (progress.totalBytes > 0) {
                ((progress.downloadedBytes * 100) / progress.totalBytes).toInt().coerceIn(0, 100)
            } else -1
            DownloadRepository.updateItem(itemId) {
                it.copy(
                    progressPercent = percent,
                    speedBytesPerSecond = progress.bytesPerSecond,
                    totalBytes = progress.totalBytes,
                )
            }
        }
        DownloadRepository.updateItem(itemId) {
            it.copy(
                status = DownloadStatus.SUCCESS,
                fileName = result.displayName,
                progressPercent = 100,
                speedBytesPerSecond = 0,
            )
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val listenerChannel = NotificationChannel(
            CHANNEL_ID,
            "Link listener",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Jido running so it can catch clipboard changes"
        }
        manager.createNotificationChannel(listenerChannel)

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Download Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows pop-ups when a download is added or completed"
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(alertChannel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Listening for copied media links…")
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
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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

    private fun showDownloadAddedNotification(platform: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("🚀 Jido: Download Added!")
            .setContentText("Detected a $platform link. Processing…")
            .setSmallIcon(R.drawable.ic_notification_j)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVibrate(longArrayOf(0, 250))
            .setAutoCancel(true)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        private const val TAG = "ClipboardService"
        private const val CHANNEL_ID = "jido_link_listener"
        private const val ALERT_CHANNEL_ID = "jido_download_alerts"
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
