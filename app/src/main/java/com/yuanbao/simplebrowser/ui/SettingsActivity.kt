package com.yuanbao.simplebrowser.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yuanbao.simplebrowser.R
import com.yuanbao.simplebrowser.core.HomePages
import com.yuanbao.simplebrowser.core.Prefs
import com.yuanbao.simplebrowser.core.SearchEngines
import com.yuanbao.simplebrowser.core.UaPresets
import com.yuanbao.simplebrowser.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        setupSpinner(
            binding.spHome,
            HomePages.ALL.map { getString(it.label) },
            HomePages.ALL.indexOfFirst { it.key == prefs.homeKey }
        )
        setupSpinner(
            binding.spSearch,
            SearchEngines.ALL.map { getString(it.label) },
            SearchEngines.ALL.indexOfFirst { it.key == prefs.searchKey }
        )
        setupSpinner(
            binding.spUa,
            UaPresets.ALL.map { getString(it.label) },
            UaPresets.ALL.indexOfFirst { it.key == prefs.uaKey }
        )

        binding.etCustomHome.setText(prefs.homeCustom)
        binding.etCustomSearch.setText(prefs.searchCustom)
        binding.etCustomUa.setText(prefs.uaCustom)
        binding.etMaxTasks.setText(prefs.maxTasks.toString())
        binding.etParts.setText(prefs.partsPerTask.toString())
        binding.switchJs.isChecked = prefs.javascript

        binding.btnSave.setOnClickListener { save() }
    }

    private fun setupSpinner(
        spinner: android.widget.Spinner,
        labels: List<String>,
        selected: Int
    ) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(if (selected >= 0) selected else 0)
    }

    private fun save() {
        prefs.homeKey = HomePages.ALL[binding.spHome.selectedItemPosition.coerceAtLeast(0)].key
        prefs.searchKey = SearchEngines.ALL[binding.spSearch.selectedItemPosition.coerceAtLeast(0)].key
        prefs.uaKey = UaPresets.ALL[binding.spUa.selectedItemPosition.coerceAtLeast(0)].key
        prefs.homeCustom = binding.etCustomHome.text.toString()
        prefs.searchCustom = binding.etCustomSearch.text.toString()
        prefs.uaCustom = binding.etCustomUa.text.toString()

        val tasks = binding.etMaxTasks.text.toString().trim().toIntOrNull() ?: 8
        val parts = binding.etParts.text.toString().trim().toIntOrNull() ?: 32
        if (tasks !in 1..64 || parts !in 1..64) {
            Toast.makeText(this, R.string.invalid_number, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.maxTasks = tasks
        prefs.partsPerTask = parts
        prefs.javascript = binding.switchJs.isChecked

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
