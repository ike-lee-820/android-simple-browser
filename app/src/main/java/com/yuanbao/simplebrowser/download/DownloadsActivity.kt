package com.yuanbao.simplebrowser.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuanbao.simplebrowser.R
import com.yuanbao.simplebrowser.core.BrowserDb
import com.yuanbao.simplebrowser.core.DownloadEntry
import com.yuanbao.simplebrowser.core.DownloadStatus
import com.yuanbao.simplebrowser.databinding.ActivityDownloadsBinding
import com.yuanbao.simplebrowser.databinding.ItemDownloadBinding
import java.io.File

class DownloadsActivity : AppCompatActivity(), DownloadEngine.Listener {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var db: BrowserDb
    private val items = ArrayList<DownloadEntry>()
    private lateinit var adapter: DownloadAdapter

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        DownloadEngine.init(this)
        db = BrowserDb(this)

        adapter = DownloadAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnClearFinished.setOnClickListener {
            DownloadEngine.clearFinished(this)
            reload()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        DownloadEngine.register(this)
        reload()
    }

    override fun onPause() {
        super.onPause()
        DownloadEngine.unregister(this)
    }

    override fun onChanged() {
        runOnUiThread { reload() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun reload() {
        items.clear()
        items.addAll(db.downloads())
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility =
            if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun statusText(entry: DownloadEntry): String {
        val label = when (entry.status) {
            DownloadStatus.QUEUED -> getString(R.string.status_queued)
            DownloadStatus.RUNNING -> getString(R.string.status_running)
            DownloadStatus.PAUSED -> getString(R.string.status_paused)
            DownloadStatus.DONE -> getString(R.string.status_done)
            else -> getString(R.string.status_failed)
        }
        return if (entry.total > 0) {
            val percent = (entry.loaded * 100 / entry.total).coerceIn(0, 100)
            "$label  $percent%  ${entry.loaded / 1024}KB / ${entry.total / 1024}KB"
        } else {
            "$label  ${entry.loaded / 1024}KB"
        }
    }

    private fun openFile(entry: DownloadEntry) {
        val file = File(entry.filePath)
        if (!file.exists()) return
        val ext = MimeTypeMap.getFileExtensionFromUrl(file.name)
        val mime = if (ext.isNullOrEmpty()) "*/*"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.open)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    inner class DownloadAdapter : RecyclerView.Adapter<DownloadAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            val b = holder.binding
            b.tvName.text = entry.fileName
            b.tvStatus.text = statusText(entry)
            val percent = if (entry.total > 0) {
                (entry.loaded * 100 / entry.total).coerceIn(0, 100)
            } else 0
            b.itemProgress.progress = percent.toInt()
            b.btnOpen.isEnabled = entry.status == DownloadStatus.DONE
            b.btnPause.text = getString(
                if (entry.status == DownloadStatus.PAUSED) R.string.resume else R.string.pause
            )
            b.btnPause.setOnClickListener {
                if (entry.status == DownloadStatus.PAUSED || entry.status == DownloadStatus.FAILED) {
                    DownloadEngine.resume(this@DownloadsActivity, entry.id)
                } else {
                    DownloadEngine.pause(entry.id)
                }
                reload()
            }
            b.btnOpen.setOnClickListener { openFile(entry) }
            b.btnDelete.setOnClickListener {
                DownloadEngine.delete(this@DownloadsActivity, entry.id)
                reload()
            }
        }

        override fun getItemCount(): Int = rowsSize()

        private fun rowsSize(): Int = items.size

        inner class Holder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
