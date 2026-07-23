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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Persistent foreground service that watches the system clipboard for links
 * from supported platforms and kicks off an automated download when it
 * spots one.
 */
class ClipboardService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

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
        // Restart automatically if the system kills the process under memory pressure.
        return START_STICKY
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
        val directUrl = withContext(Dispatchers.IO) { fetchDirectMediaUrl(link) }

        if (directUrl.isNullOrBlank()) {
            Log.w(TAG, "Could not resolve a direct media URL for $link")
            updateNotification("Listening for copied media links…")
            return
        }

        withContext(Dispatchers.Main) {
            enqueueDownload(directUrl, platform)
            updateNotification("Listening for copied media links…")
        }
    }

    /**
     * Calls the third-party RapidAPI downloader endpoint and pulls the
     * direct asset URL out of the JSON response.
     *
     * PASTE YOUR RAPIDAPI CREDENTIALS BELOW:
     *   - RAPIDAPI_KEY  -> your personal RapidAPI key
     *   - RAPIDAPI_HOST -> the "X-RapidAPI-Host" value for whichever
     *                       downloader API you subscribe to
     *   - API_ENDPOINT  -> the full POST endpoint for that API
     *
     * Different RapidAPI downloader listings use different response shapes
     * (some return {"download_url": "..."}, others return an array of
     * qualities under {"medias": [...]}). Adjust parseDirectUrl() below to
     * match the exact API you pick.
     */
    private fun fetchDirectMediaUrl(sourceLink: String): String? {
        val RAPIDAPI_KEY = "PASTE_YOUR_RAPIDAPI_KEY_HERE"
        val RAPIDAPI_HOST = "PASTE_YOUR_RAPIDAPI_HOST_HERE" // e.g. "pinterest-video-downloader6.p.rapidapi.com"
        val API_ENDPOINT = "https://$RAPIDAPI_HOST/index.php" // adjust path per the API you subscribe to

        return try {
            val connection = (URL(API_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("X-RapidAPI-Key", RAPIDAPI_KEY)
                setRequestProperty("X-RapidAPI-Host", RAPIDAPI_HOST)
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write("url=" + URLEncoder.encode(sourceLink, "UTF-8"))
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "API returned HTTP ${connection.responseCode}")
                return null
            }

            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }

            parseDirectUrl(body)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDirectMediaUrl failed", e)
            null
        }
    }

    /** Adjust this to match the exact JSON shape of the API you subscribe to. */
    private fun parseDirectUrl(responseBody: String): String? = try {
        val json = JSONObject(responseBody)
        json.optString("download_url").ifBlank { null }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse API response: $responseBody", e)
        null
    }

    private fun enqueueDownload(directUrl: String, platform: String) {
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
        downloadManager.enqueue(request)
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
            description = "Keeps Jido running so it can catch copied media links"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
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
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jido")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "ClipboardService"
        private const val CHANNEL_ID = "jido_link_listener"
        private const val NOTIFICATION_ID = 101
    }
}

/** Simple pattern matching for the platforms Jido currently supports. */
object SupportedLinks {
    private val patterns = mapOf(
        "Pinterest" to Regex("""pinterest\.com|pin\.it""", RegexOption.IGNORE_CASE),
        "Instagram" to Regex("""instagram\.com""", RegexOption.IGNORE_CASE),
        "TikTok" to Regex("""tiktok\.com|vm\.tiktok\.com""", RegexOption.IGNORE_CASE)
    )

    /** Returns the matched platform name, or null if the text isn't a supported link. */
    fun detect(text: String): String? {
        if (!text.contains("http")) return null
        return patterns.entries.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.key
    }
}
