package com.yuanbao.simplebrowser.core

data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String,
    val time: Long
)

data class BookmarkEntry(
    val id: Long,
    val url: String,
    val title: String,
    val time: Long
)

data class DownloadEntry(
    val id: Long,
    val url: String,
    val fileName: String,
    val filePath: String,
    val status: Int,
    val total: Long,
    val loaded: Long,
    val parts: Int,
    val created: Long
)

object DownloadStatus {
    const val QUEUED = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val FAILED = 4
}
