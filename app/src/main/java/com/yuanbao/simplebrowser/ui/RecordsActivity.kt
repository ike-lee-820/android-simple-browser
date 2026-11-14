package com.yuanbao.simplebrowser.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuanbao.simplebrowser.R
import com.yuanbao.simplebrowser.core.BrowserDb
import com.yuanbao.simplebrowser.databinding.ActivityRecordsBinding
import com.yuanbao.simplebrowser.databinding.ItemRecordBinding

class RecordsActivity : AppCompatActivity() {

    private data class Row(val id: Long, val title: String, val url: String)

    private lateinit var binding: ActivityRecordsBinding
    private lateinit var db: BrowserDb
    private val rows = ArrayList<Row>()
    private lateinit var adapter: RecordAdapter
    private var showBookmarks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        db = BrowserDb(this)

        adapter = RecordAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        showBookmarks = intent.getStringExtra("mode") == "bookmarks"
        binding.toggleGroup.check(if (showBookmarks) R.id.btnBookmark else R.id.btnHistory)
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                showBookmarks = checkedId == R.id.btnBookmark
                reload()
            }
        }
        binding.btnClear.setOnClickListener { confirmClear() }
        reload()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear)
            .setMessage(if (showBookmarks) R.string.menu_bookmarks else R.string.menu_history)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (showBookmarks) db.clearBookmarks() else db.clearHistory()
                reload()
            }
            .show()
    }

    private fun reload() {
        rows.clear()
        if (showBookmarks) {
            db.bookmarks().forEach { rows.add(Row(it.id, it.title.ifEmpty { it.url }, it.url)) }
        } else {
            db.history().forEach { rows.add(Row(it.id, it.title.ifEmpty { it.url }, it.url)) }
        }
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    inner class RecordAdapter : RecyclerView.Adapter<RecordAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val b = holder.binding
            b.tvTitle.text = row.title
            b.tvUrl.text = row.url
            b.root.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra("url", row.url))
                finish()
            }
            b.btnDelete.setOnClickListener {
                if (showBookmarks) db.deleteBookmark(row.id) else db.deleteHistory(row.id)
                reload()
            }
        }

        override fun getItemCount(): Int = rows.size

        inner class Holder(val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
