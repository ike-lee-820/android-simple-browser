package com.yuanbao.simplebrowser.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import com.yuanbao.simplebrowser.R
import com.yuanbao.simplebrowser.core.BrowserDb
import com.yuanbao.simplebrowser.core.DownloadEntry
import com.yuanbao.simplebrowser.core.DownloadStatus
import com.yuanbao.simplebrowser.core.Prefs
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

/**
 * 下载引擎：默认 8 个并发任务，每个任务最多 32 个并发分片。
 * Android 应用内不存在多进程下载，这里的「32 进程」按 32 个并发分片线程实现，
 * 使用 HTTP Range 请求分块下载，支持断点续传、暂停、继续与合并。
 */
object DownloadEngine {

    private const val MIN_PART_SIZE = 256L * 1024L
    private const val BUFFER_SIZE = 32 * 1024

    interface Listener {
        fun onChanged()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val taskExecutor = Executors.newFixedThreadPool(16)
    private val partExecutor = Executors.newFixedThreadPool(64)

    private val pending: MutableList<Long> = Collections.synchronizedList(ArrayList())
    private val runningIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    private val pausedIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    private val activeCalls = ConcurrentHashMap<Long, MutableList<Call>>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var database: BrowserDb? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            database = BrowserDb(appContext!!)
        }
    }

    private fun ensure(context: Context) {
        if (appContext == null) init(context)
    }

    private fun db(): BrowserDb = database ?: throw IllegalStateException("DownloadEngine not initialized")
    private fun context(): Context = appContext ?: throw IllegalStateException("DownloadEngine not initialized")

    fun register(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it.onChanged() } }
    }

    private fun downloadDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return cleaned.ifEmpty { "download.bin" }
    }

    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (File(dir, "$base-$i$ext").exists()) i++
        return "$base-$i$ext"
    }

    fun enqueue(context: Context, url: String, fileName: String): Long {
        ensure(context)
        val dir = downloadDir(context)
        val name = uniqueName(dir, sanitize(fileName))
        val target = File(dir, name)
        val id = db().insertDownload(url, name, target.absolutePath)
        synchronized(pending) { pending.add(id) }
        notifyChanged()
        pump(context)
        return id
    }

    fun pause(id: Long) {
        pausedIds.add(id)
        activeCalls[id]?.forEach { runCatching { it.cancel() } }
        val entry = runCatching { db().download(id) }.getOrNull()
        if (entry != null && entry.status != DownloadStatus.DONE) {
            setState(id, DownloadStatus.PAUSED, entry.total, entry.loaded, entry.parts)
        }
    }

    fun resume(context: Context, id: Long) {
        ensure(context)
        pausedIds.remove(id)
        val entry = db().download(id) ?: return
        if (entry.status == DownloadStatus.DONE) return
        setState(id, DownloadStatus.QUEUED, entry.total, entry.loaded, entry.parts)
        synchronized(pending) {
            if (!pending.contains(id) && !runningIds.contains(id)) pending.add(id)
        }
        pump(context)
    }

    fun delete(context: Context, id: Long) {
        ensure(context)
        pausedIds.add(id)
        activeCalls[id]?.forEach { runCatching { it.cancel() } }
        val entry = db().download(id)
        if (entry != null) deleteFiles(entry)
        db().deleteDownload(id)
        synchronized(pending) { pending.remove(id) }
        notifyChanged()
    }

    fun clearFinished(context: Context) {
        ensure(context)
        db().downloads().filter { it.status == DownloadStatus.DONE }.forEach { deleteFiles(it) }
        db().clearFinished()
        notifyChanged()
    }

    private fun deleteFiles(entry: DownloadEntry) {
        runCatching {
            File(entry.filePath).delete()
            for (i in 0 until 256) {
                val part = File(entry.filePath + ".part" + i)
                if (!part.exists()) break
                part.delete()
            }
        }
    }

    private fun pump(context: Context) {
        val max = Prefs(context).maxTasks.coerceIn(1, 16)
        synchronized(pending) {
            while (runningIds.size < max && pending.isNotEmpty()) {
                val id = pending.removeAt(0)
                if (pausedIds.contains(id)) {
                    val e = db().download(id)
                    if (e != null) setState(id, DownloadStatus.PAUSED, e.total, e.loaded, e.parts)
                    continue
                }
                runningIds.add(id)
                val app = context.applicationContext
                taskExecutor.execute { runTask(app, id) }
            }
        }
    }

    private fun setState(id: Long, status: Int, total: Long, loaded: Long, parts: Int) {
        runCatching { db().updateDownload(id, status, total, loaded, parts) }
        notifyChanged()
    }

    private fun isPaused(id: Long): Boolean = pausedIds.contains(id)

    private fun registerCall(id: Long, call: Call) {
        val list = activeCalls.getOrPut(id) { CopyOnWriteArrayList() }
        list.add(call)
    }

    private fun unregisterCall(id: Long, call: Call) {
        activeCalls[id]?.remove(call)
    }

    private fun partFile(entry: DownloadEntry, index: Int): File =
        File(entry.filePath + ".part" + index)

    private fun runTask(context: Context, id: Long) {
        val entry = db().download(id)
        if (entry == null) {
            finishTask(id)
            return
        }
        if (entry.status == DownloadStatus.DONE) {
            finishTask(id)
            return
        }

        var total = entry.total
        var ranged = false
        try {
            val head = Request.Builder().url(entry.url).head()
                .header("User-Agent", "SimpleBrowser/1.0").build()
            client.newCall(head).execute().use { resp ->
                if (resp.isSuccessful) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val accepts = resp.header("Accept-Ranges")?.lowercase() ?: ""
                    if (len > 0) total = len
                    ranged = len > 0 && accepts.contains("bytes")
                }
            }
        } catch (ignored: Exception) {
        }

        if (total <= 0L) {
            try {
                val probe = Request.Builder().url(entry.url)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", "SimpleBrowser/1.0").build()
                client.newCall(probe).execute().use { resp ->
                    val range = resp.header("Content-Range")
                    val len = range?.substringAfterLast('/')?.toLongOrNull()
                        ?: resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    if (resp.code == 206 && len > 0) {
                        total = len
                        ranged = true
                    } else if (len > 0) {
                        total = len
                    }
                }
            } catch (ignored: Exception) {
            }
        }

        val configuredParts = Prefs(context).partsPerTask.coerceIn(1, 64)
        val computed = if (ranged && total > 0) {
            min(
                configuredParts.toLong(),
                ceil(total.toDouble() / MIN_PART_SIZE.toDouble()).toLong().coerceAtLeast(1L)
            ).toInt()
        } else 1

        // 续传时沿用上次的分片数，避免分片边界变化导致数据错位。
        val parts = if (entry.parts > 0) entry.parts else computed
        var initialLoaded = 0L
        for (i in 0 until parts) initialLoaded += partFile(entry, i).length()
        val loadedBytes = AtomicLong(initialLoaded)

        setState(id, DownloadStatus.RUNNING, total, loadedBytes.get(), parts)

        val futures = ArrayList<java.util.concurrent.Future<Boolean>>()
        for (i in 0 until parts) {
            val start = if (parts == 1) 0L else total * i / parts
            val end = if (parts == 1) -1L else (total * (i + 1) / parts - 1).coerceAtMost(total - 1)
            val expected = if (parts == 1) -1L else end - start + 1

            futures.add(partExecutor.submit(Callable<Boolean> {
                if (isPaused(id)) return@Callable false
                val pf = partFile(entry, i)
                val existing = pf.length()
                if (parts > 1 && existing == expected) return@Callable true
                if (parts > 1 && existing > expected) pf.delete()
                val from = start + pf.length()
                if (parts > 1 && from > end) return@Callable true

                val builder = Request.Builder().url(entry.url)
                    .header("User-Agent", "SimpleBrowser/1.0")
                if (parts > 1) builder.header("Range", "bytes=$from-$end")
                val call = client.newCall(builder.build())
                registerCall(id, call)
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful && resp.code != 206) {
                            throw IllegalStateException("HTTP " + resp.code)
                        }
                        val body = resp.body ?: throw IllegalStateException("empty body")
                        body.byteStream().use { input ->
                            FileOutputStream(pf, true).use { out ->
                                val buf = ByteArray(BUFFER_SIZE)
                                var sinceUpdate = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    if (isPaused(id)) {
                                        call.cancel()
                                        return@Callable false
                                    }
                                    out.write(buf, 0, n)
                                    loadedBytes.addAndGet(n.toLong())
                                    sinceUpdate += n
                                    if (sinceUpdate >= 256L * 1024L) {
                                        sinceUpdate = 0L
                                        setState(id, DownloadStatus.RUNNING, total, loadedBytes.get(), parts)
                                    }
                                }
                            }
                        }
                    }
                    return@Callable true
                } catch (ignored: Exception) {
                    return@Callable false
                } finally {
                    unregisterCall(id, call)
                }
            }))
        }

        var ok = true
        for (f in futures) {
            try {
                if (!f.get()) ok = false
            } catch (ignored: Exception) {
                ok = false
            }
        }

        if (isPaused(id)) {
            setState(id, DownloadStatus.PAUSED, total, loadedBytes.get(), parts)
            finishTask(id)
            return
        }

        if (!ok) {
            setState(id, DownloadStatus.FAILED, total, loadedBytes.get(), parts)
            notifyDone(context, entry.fileName, false)
            finishTask(id)
            return
        }

        try {
            val target = File(entry.filePath)
            if (target.exists()) target.delete()
            FileOutputStream(target, false).use { out ->
                for (i in 0 until parts) {
                    val pf = partFile(entry, i)
                    FileInputStream(pf).use { input -> input.copyTo(out, BUFFER_SIZE) }
                }
            }
            for (i in 0 until parts) partFile(entry, i).delete()
            setState(id, DownloadStatus.DONE, total, target.length(), parts)
            notifyDone(context, entry.fileName, true)
        } catch (ignored: Exception) {
            setState(id, DownloadStatus.FAILED, total, loadedBytes.get(), parts)
            notifyDone(context, entry.fileName, false)
        }
        finishTask(id)
    }

    private fun finishTask(id: Long) {
        synchronized(pending) { runningIds.remove(id) }
        activeCalls.remove(id)
        appContext?.let { runCatching { pump(it) } }
    }

    private fun notifyDone(context: Context, name: String, success: Boolean) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "simple_browser_downloads",
                    context.getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(channel)
            }
            val title = context.getString(
                if (success) R.string.download_done else R.string.download_failed
            )
            val notification = NotificationCompat.Builder(context, "simple_browser_downloads")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(name)
                .setAutoCancel(true)
                .build()
            manager.notify((System.currentTimeMillis() % 100000L).toInt(), notification)
        }
    }
}
