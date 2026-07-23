package com.geto.jido

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, in-memory list of downloads, observed by MainActivity and
 * written to by ClipboardService / DownloadCompleteReceiver.
 *
 * NOTE: this is in-memory only — the list resets if the app process is
 * killed (e.g. by the system under memory pressure) even though the
 * foreground service itself is designed to restart (START_STICKY). If you
 * want history to survive process death, swap this for a Room database;
 * the public API below (addItem/updateItem/downloads) is intentionally
 * small so that swap wouldn't touch call sites much.
 */
object DownloadRepository {

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    fun addItem(item: DownloadItem) {
        _downloads.value = listOf(item) + _downloads.value
    }

    fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        _downloads.value = _downloads.value.map { if (it.id == id) transform(it) else it }
    }

    fun findByDownloadManagerId(downloadManagerId: Long): DownloadItem? =
        _downloads.value.firstOrNull { it.downloadManagerId == downloadManagerId }
}
