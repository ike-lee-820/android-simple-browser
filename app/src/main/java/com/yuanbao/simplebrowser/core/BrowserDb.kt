package com.yuanbao.simplebrowser.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BrowserDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "simple_browser.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT, time INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE bookmark (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT, time INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE download (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, file_name TEXT, file_path TEXT, status INTEGER NOT NULL, total INTEGER NOT NULL, loaded INTEGER NOT NULL, parts INTEGER NOT NULL, created INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 当前为第 1 版，预留迁移入口。
    }

    private fun w(): SQLiteDatabase = writableDatabase
    private fun r(): SQLiteDatabase = readableDatabase

    fun addHistory(url: String, title: String) {
        if (!url.startsWith("http")) return
        val d = w()
        d.delete("history", "url = ?", arrayOf(url))
        val cv = ContentValues().apply {
            put("url", url)
            put("title", title)
            put("time", System.currentTimeMillis())
        }
        d.insert("history", null, cv)
        d.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY time DESC LIMIT 500)")
    }

    fun history(): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        r().query("history", null, null, null, null, null, "time DESC", "300").use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iUrl = c.getColumnIndexOrThrow("url")
            val iTitle = c.getColumnIndexOrThrow("title")
            val iTime = c.getColumnIndexOrThrow("time")
            while (c.moveToNext()) {
                out.add(
                    HistoryEntry(
                        c.getLong(iId),
                        c.getString(iUrl) ?: "",
                        c.getString(iTitle) ?: "",
                        c.getLong(iTime)
                    )
                )
            }
        }
        return out
    }

    fun clearHistory() {
        w().delete("history", null, null)
    }

    fun deleteHistory(id: Long) {
        w().delete("history", "id = ?", arrayOf(id.toString()))
    }

    fun isBookmarked(url: String): Boolean {
        r().query("bookmark", arrayOf("id"), "url = ?", arrayOf(url), null, null, null, "1").use { c ->
            return c.moveToFirst()
        }
    }

    fun addBookmark(url: String, title: String): Boolean {
        if (!url.startsWith("http") || isBookmarked(url)) return false
        val cv = ContentValues().apply {
            put("url", url)
            put("title", title)
            put("time", System.currentTimeMillis())
        }
        return w().insert("bookmark", null, cv) > 0
    }

    fun bookmarks(): List<BookmarkEntry> {
        val out = ArrayList<BookmarkEntry>()
        r().query("bookmark", null, null, null, null, null, "time DESC", "300").use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iUrl = c.getColumnIndexOrThrow("url")
            val iTitle = c.getColumnIndexOrThrow("title")
            val iTime = c.getColumnIndexOrThrow("time")
            while (c.moveToNext()) {
                out.add(
                    BookmarkEntry(
                        c.getLong(iId),
                        c.getString(iUrl) ?: "",
                        c.getString(iTitle) ?: "",
                        c.getLong(iTime)
                    )
                )
            }
        }
        return out
    }

    fun clearBookmarks() {
        w().delete("bookmark", null, null)
    }

    fun deleteBookmark(id: Long) {
        w().delete("bookmark", "id = ?", arrayOf(id.toString()))
    }

    fun insertDownload(url: String, fileName: String, filePath: String): Long {
        val cv = ContentValues().apply {
            put("url", url)
            put("file_name", fileName)
            put("file_path", filePath)
            put("status", DownloadStatus.QUEUED)
            put("total", 0L)
            put("loaded", 0L)
            put("parts", 0)
            put("created", System.currentTimeMillis())
        }
        return w().insert("download", null, cv)
    }

    fun updateDownload(id: Long, status: Int, total: Long, loaded: Long, parts: Int) {
        val cv = ContentValues().apply {
            put("status", status)
            put("total", total)
            put("loaded", loaded)
            put("parts", parts)
        }
        w().update("download", cv, "id = ?", arrayOf(id.toString()))
    }

    fun download(id: Long): DownloadEntry? {
        r().query("download", null, "id = ?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            if (!c.moveToFirst()) return null
            return readDownload(c)
        }
    }

    fun downloads(): List<DownloadEntry> {
        val out = ArrayList<DownloadEntry>()
        r().query("download", null, null, null, null, null, "created DESC").use { c ->
            while (c.moveToNext()) out.add(readDownload(c))
        }
        return out
    }

    private fun readDownload(c: android.database.Cursor): DownloadEntry {
        val iId = c.getColumnIndexOrThrow("id")
        val iUrl = c.getColumnIndexOrThrow("url")
        val iName = c.getColumnIndexOrThrow("file_name")
        val iPath = c.getColumnIndexOrThrow("file_path")
        val iStatus = c.getColumnIndexOrThrow("status")
        val iTotal = c.getColumnIndexOrThrow("total")
        val iLoaded = c.getColumnIndexOrThrow("loaded")
        val iParts = c.getColumnIndexOrThrow("parts")
        val iCreated = c.getColumnIndexOrThrow("created")
        return DownloadEntry(
            c.getLong(iId),
            c.getString(iUrl) ?: "",
            c.getString(iName) ?: "file",
            c.getString(iPath) ?: "",
            c.getInt(iStatus),
            c.getLong(iTotal),
            c.getLong(iLoaded),
            c.getInt(iParts),
            c.getLong(iCreated)
        )
    }

    fun deleteDownload(id: Long) {
        w().delete("download", "id = ?", arrayOf(id.toString()))
    }

    fun clearFinished() {
        w().delete("download", "status = ?", arrayOf(DownloadStatus.DONE.toString()))
    }
}
