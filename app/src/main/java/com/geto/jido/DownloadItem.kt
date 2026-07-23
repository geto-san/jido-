package com.geto.jido

/** Lifecycle states shown in the downloads list UI. */
enum class DownloadStatus {
    FETCHING,     // resolving the direct media URL from the RapidAPI endpoint
    DOWNLOADING,  // DownloadManager is actively saving the file
    SUCCESS,
    FAILED
}

/**
 * One row in the downloads list. `id` is an internal UI-stable identifier
 * (assigned the moment a link is detected) — it's independent of
 * `downloadManagerId`, which is only known once DownloadManager.enqueue()
 * returns.
 */
data class DownloadItem(
    val id: String,
    val platform: String,
    val sourceLink: String,
    val fileName: String = "",
    val status: DownloadStatus = DownloadStatus.FETCHING,
    val progressPercent: Int = -1, // -1 = unknown/indeterminate
    val downloadManagerId: Long? = null,
)
