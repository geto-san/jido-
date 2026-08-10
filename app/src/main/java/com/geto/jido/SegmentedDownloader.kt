package com.geto.jido

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-connection segmented downloader, in the spirit of Free Download
 * Manager: when the server advertises byte-range support, splits the file
 * into several concurrent range requests instead of pulling it over one
 * connection. This is the actual throughput lever — a single HTTP stream
 * rarely saturates a fast link on its own, especially against CDNs that
 * throttle per-connection. Falls back to a single-stream transfer when
 * ranges aren't supported, or the content length is too small to bother
 * splitting.
 *
 * The caller (ClipboardService) treats any exception from [download] as a
 * signal to fall back to Android's DownloadManager, so nothing here needs to
 * be bulletproof against every device/CDN quirk — it only needs to be
 * reliably *faster* than DownloadManager on the common case, and safe to
 * abandon when it isn't.
 */
class SegmentedDownloader(private val client: OkHttpClient) {

    data class Progress(val downloadedBytes: Long, val totalBytes: Long, val bytesPerSecond: Long)

    /** Where the finished file ended up — a MediaStore Uri (API 29+) or a plain path (API 26-28). */
    data class DownloadResult(val displayName: String, val contentUri: Uri?, val filePath: String?)

    suspend fun download(
        context: Context,
        url: String,
        platform: String,
        onProgress: suspend (Progress) -> Unit,
    ): DownloadResult = coroutineScope {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)?.takeIf { it.isNotBlank() } ?: "mp4"
        val displayName = "${platform}_${System.currentTimeMillis()}.$extension"
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"

        val probeResult = probe(url)
        val destination = openDestination(context, displayName, mimeType)

        val downloadedBytes = AtomicLong(0)
        var lastReportTime = System.currentTimeMillis()
        var lastReportedBytes = 0L

        suspend fun reportProgress() {
            val now = System.currentTimeMillis()
            val elapsedMs = now - lastReportTime
            if (elapsedMs < PROGRESS_THROTTLE_MS) return
            val bytesNow = downloadedBytes.get()
            val speed = ((bytesNow - lastReportedBytes) * 1000) / elapsedMs.coerceAtLeast(1)
            lastReportTime = now
            lastReportedBytes = bytesNow
            onProgress(Progress(bytesNow, probeResult.contentLength, speed))
        }

        try {
            if (probeResult.supportsRanges && probeResult.contentLength >= MIN_BYTES_TO_SPLIT) {
                downloadSegmented(url, destination.channel, probeResult.contentLength, downloadedBytes, ::reportProgress)
            } else {
                downloadSingleStream(url, destination.channel, downloadedBytes, ::reportProgress)
            }
            onProgress(Progress(downloadedBytes.get(), probeResult.contentLength.coerceAtLeast(downloadedBytes.get()), 0))
        } finally {
            destination.finish(context)
        }

        DownloadResult(displayName, destination.contentUri, destination.file?.absolutePath)
    }

    // -------------------------------------------------------------------
    // Probing: does the server support byte ranges, and how big is the file?
    // -------------------------------------------------------------------

    private data class ProbeResult(val contentLength: Long, val supportsRanges: Boolean)

    private fun probe(url: String): ProbeResult {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { response ->
            val supportsRanges = response.code == 206
            val contentLength = if (supportsRanges) {
                response.header("Content-Range")?.substringAfter("/")?.toLongOrNull() ?: -1L
            } else {
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
            return ProbeResult(contentLength, supportsRanges && contentLength > 0)
        }
    }

    // -------------------------------------------------------------------
    // Segmented (multi-connection) transfer
    // -------------------------------------------------------------------

    private suspend fun downloadSegmented(
        url: String,
        channel: FileChannel,
        totalBytes: Long,
        downloadedBytes: AtomicLong,
        reportProgress: suspend () -> Unit,
    ) = coroutineScope {
        val segmentCount = SEGMENT_COUNT.coerceAtMost((totalBytes / MIN_SEGMENT_SIZE).toInt().coerceAtLeast(1))
        val segmentSize = totalBytes / segmentCount
        val segments = (0 until segmentCount).map { index ->
            val start = index * segmentSize
            val end = if (index == segmentCount - 1) totalBytes - 1 else start + segmentSize - 1
            async(Dispatchers.IO) { downloadRange(url, channel, start, end, downloadedBytes, reportProgress) }
        }
        segments.awaitAll()
    }

    private suspend fun downloadRange(
        url: String,
        channel: FileChannel,
        start: Long,
        end: Long,
        downloadedBytes: AtomicLong,
        reportProgress: suspend () -> Unit,
    ) {
        var position = start
        var attempt = 0
        while (position <= end) {
            try {
                val request = Request.Builder().url(url).header("Range", "bytes=$position-$end").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Segment fetch failed: ${response.code}")
                    val input = response.body?.byteStream() ?: throw IOException("Empty segment body")
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    input.use { stream ->
                        while (true) {
                            val read = stream.read(buffer)
                            if (read == -1) break
                            channel.write(ByteBuffer.wrap(buffer, 0, read), position)
                            position += read
                            downloadedBytes.addAndGet(read.toLong())
                            reportProgress()
                        }
                    }
                }
            } catch (e: IOException) {
                attempt++
                if (position > end) break
                if (attempt >= MAX_SEGMENT_RETRIES) throw e
                delay(RETRY_BACKOFF_MS * attempt)
            }
        }
        if (position <= end) throw IOException("Segment [$start-$end] incomplete after $MAX_SEGMENT_RETRIES retries")
    }

    // -------------------------------------------------------------------
    // Single-stream fallback (server doesn't support ranges, or file is tiny)
    // -------------------------------------------------------------------

    private suspend fun downloadSingleStream(
        url: String,
        channel: FileChannel,
        downloadedBytes: AtomicLong,
        reportProgress: suspend () -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: ${response.code}")
            val input = response.body?.byteStream() ?: throw IOException("Empty body")
            var position = 0L
            val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    channel.write(ByteBuffer.wrap(buffer, 0, read), position)
                    position += read
                    downloadedBytes.addAndGet(read.toLong())
                    reportProgress()
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // Destination file handling: MediaStore on API 29+, direct file below that
    // -------------------------------------------------------------------

    private class Destination(
        val channel: FileChannel,
        val contentUri: Uri?,
        val file: File?,
        private val pfd: ParcelFileDescriptor?,
        private val raf: RandomAccessFile?,
    ) {
        fun finish(context: Context) {
            channel.close()
            pfd?.close()
            raf?.close()
            if (contentUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(contentUri, values, null, null)
            } else if (file != null) {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            }
        }
    }

    private fun openDestination(context: Context, displayName: String, mimeType: String): Destination {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed for $displayName")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Could not open descriptor for $displayName")
            val channel = FileOutputStream(pfd.fileDescriptor).channel
            return Destination(channel, uri, null, pfd, null)
        }

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, displayName)
        val raf = RandomAccessFile(file, "rw")
        return Destination(raf.channel, null, file, null, raf)
    }

    companion object {
        /** How many concurrent range requests to split a download into. */
        private const val SEGMENT_COUNT = 4

        /** Don't bother splitting a segment smaller than this. */
        private const val MIN_SEGMENT_SIZE = 1L * 1024 * 1024 // 1 MB

        /** Don't bother segmenting the whole file below this size — single stream is fine. */
        private const val MIN_BYTES_TO_SPLIT = 2L * 1024 * 1024 // 2 MB

        private const val TRANSFER_BUFFER_SIZE = 64 * 1024 // 64 KB
        private const val MAX_SEGMENT_RETRIES = 3
        private const val RETRY_BACKOFF_MS = 400L
        private const val PROGRESS_THROTTLE_MS = 200L // ~5 UI updates/sec
    }
}
