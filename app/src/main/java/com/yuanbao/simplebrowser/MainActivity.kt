package com.yuanbao.simplebrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.yuanbao.simplebrowser.core.BrowserDb
import com.yuanbao.simplebrowser.core.DomainGuard
import com.yuanbao.simplebrowser.core.Prefs
import com.yuanbao.simplebrowser.core.SearchEngines
import com.yuanbao.simplebrowser.core.SettingsState
import com.yuanbao.simplebrowser.core.snapshot
import com.yuanbao.simplebrowser.databinding.ActivityMainBinding
import com.yuanbao.simplebrowser.databinding.DialogTabsBinding
import com.yuanbao.simplebrowser.databinding.ItemTabBinding
import com.yuanbao.simplebrowser.download.DownloadEngine
import com.yuanbao.simplebrowser.download.DownloadsActivity
import com.yuanbao.simplebrowser.ui.RecordsActivity
import com.yuanbao.simplebrowser.ui.SettingsActivity

class MainActivity : AppCompatActivity() {

    private data class BrowserTab(
        val id: Long,
        val isPrivate: Boolean,
        val webView: WebView,
        var url: String = "",
        var title: String = ""
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var db: BrowserDb

    private val tabs = ArrayList<BrowserTab>()
    private var nextTabId = 1L
    private var currentTabId = -1L
    private var systemUa: String? = null
    private var lastSettings: SettingsState? = null
    private var lastBackTime = 0L
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = result.data?.let { data ->
                val clip = data.clipData
                if (clip != null) {
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    private val recordsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra("url")?.let { loadUrl(it) }
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val previous = lastSettings
                applySettingsAll()
                // WebSettings 的变更对已加载页面不会立即生效，强制刷新每个标签页。
                tabs.forEach { it.webView.reload() }
                val state = lastSettings
                if (previous != null && state != null && previous.homeUrl != state.homeUrl) {
                    snack(R.string.home_updated)
                } else {
                    snack(R.string.settings_applied)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        db = BrowserDb(this)
        DownloadEngine.init(this)
        DomainGuard.restore(prefs.pinnedBase)

        setupActions()
        setupBackHandling()

        val incoming = intent?.data?.toString()
        val startUrl = savedInstanceState?.getString("current_url")
            ?: incoming
            ?: prefs.homeUrl()
        addTab(isPrivate = false, url = startUrl)
        applySettingsAll()
        syncPinButton()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && tabs.isNotEmpty()) {
            applySettingsAll()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { addTab(isPrivate = false, url = it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current_url", currentTab()?.url ?: prefs.homeUrl())
    }

    // ---------- 标签页 ----------

    private fun currentTab(): BrowserTab? = tabs.firstOrNull { it.id == currentTabId }

    private fun tabOf(view: WebView): BrowserTab? = tabs.firstOrNull { it.webView === view }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(isPrivate: Boolean): WebView {
        val wv = WebView(this)
        if (systemUa == null) systemUa = wv.settings.userAgentString
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        applyTo(wv, prefs.snapshot(systemUa), isPrivate)
        wv.settings.apply {
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }
        wv.webViewClient = createClient()
        wv.webChromeClient = createChromeClient()
        wv.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            DownloadEngine.enqueue(this, url, name)
            snack(R.string.download_added)
        }
        return wv
    }

    private fun addTab(isPrivate: Boolean, url: String) {
        val wv = createWebView(isPrivate)
        wv.visibility = View.GONE
        binding.webContainer.addView(wv)
        val tab = BrowserTab(nextTabId++, isPrivate, wv, url = url)
        tabs.add(tab)
        switchTo(tab.id)
        wv.loadUrl(url)
        if (isPrivate) snack(R.string.private_notice)
    }

    private fun switchTo(id: Long) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        tabs.forEach {
            it.webView.visibility = if (it.id == id) View.VISIBLE else View.GONE
        }
        currentTabId = id
        binding.etAddress.setText(tab.url)
        binding.etAddress.hint = getString(
            if (tab.isPrivate) R.string.private_hint else R.string.address_hint
        )
        binding.privateIndicator.visibility =
            if (tab.isPrivate) View.VISIBLE else View.GONE
        binding.btnTabs.text = tabs.size.toString()
        updateNavButtons()
        syncPinButton()
    }

    private fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = tabs[index]
        binding.webContainer.removeView(tab.webView)
        if (tab.isPrivate) clearPrivateData(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            addTab(isPrivate = false, url = prefs.homeUrl())
        } else if (currentTabId == id) {
            switchTo(tabs[minOf(index, tabs.size - 1)].id)
        } else {
            binding.btnTabs.text = tabs.size.toString()
        }
    }

    private fun clearPrivateData(wv: WebView) {
        runCatching {
            wv.clearCache(true)
            wv.clearFormData()
            wv.clearHistory()
            wv.clearSslPreferences()
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeSessionCookies(null)
        }
    }

    private fun showTabsDialog() {
        val dialogBinding = DialogTabsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tabs)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun render() {
            dialogBinding.tabList.removeAllViews()
            tabs.forEach { tab ->
                val row = ItemTabBinding.inflate(layoutInflater, dialogBinding.tabList, false)
                val label = if (tab.isPrivate) {
                    getString(R.string.private_tab) + " · " +
                        tab.title.ifEmpty { tab.url.ifEmpty { getString(R.string.home_blank) } }
                } else {
                    tab.title.ifEmpty { tab.url.ifEmpty { getString(R.string.home_blank) } }
                }
                row.tvTitle.text = label
                row.tvUrl.text = if (tab.isPrivate) {
                    getString(R.string.private_hint)
                } else {
                    tab.url
                }
                row.card.alpha = if (tab.id == currentTabId) 1f else 0.72f
                row.root.setOnClickListener {
                    switchTo(tab.id)
                    dialog.dismiss()
                }
                row.btnClose.setOnClickListener {
                    closeTab(tab.id)
                    render()
                }
                dialogBinding.tabList.addView(row.root)
            }
        }

        render()
        dialogBinding.btnNewTab.setOnClickListener {
            addTab(isPrivate = false, url = prefs.homeUrl())
            dialog.dismiss()
        }
        dialogBinding.btnNewPrivateTab.setOnClickListener {
            addTab(isPrivate = true, url = "about:blank")
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---------- 设置全量生效 ----------

    private fun applyTo(wv: WebView, state: SettingsState, isPrivate: Boolean) {
        val ws = wv.settings
        state.ua?.let { if (ws.userAgentString != it) ws.userAgentString = it }
        ws.javaScriptEnabled = state.javascript
        ws.useWideViewPort = state.desktop
        ws.loadWithOverviewMode = !state.desktop
        @Suppress("DEPRECATION")
        ws.setSaveFormData(!isPrivate)
        ws.cacheMode = if (isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    }

    private fun applySettingsAll(): Boolean {
        val state = prefs.snapshot(systemUa)
        val previous = lastSettings
        lastSettings = state
        tabs.forEach { applyTo(it.webView, state, it.isPrivate) }
        runCatching { DownloadEngine.onConfigChanged(this) }
        return previous != null && (
            previous.ua != state.ua ||
                previous.javascript != state.javascript ||
                previous.desktop != state.desktop
        )
    }

    // ---------- WebView 客户端 ----------

    private fun createClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!isWebScheme(url)) {
                if (DomainGuard.isPinned()) {
                    reportBlocked(url)
                    return true
                }
                return try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } catch (e: Exception) {
                    true
                }
            }
            if (!DomainGuard.isAllowed(url)) {
                reportBlocked(url)
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val tab = tabOf(view) ?: return
            tab.url = url
            if (DomainGuard.isPinned() && !DomainGuard.isAllowed(url)) {
                view.stopLoading()
                reportBlocked(url)
                if (view.canGoBack()) view.goBack()
                return
            }
            if (tab.id == currentTabId) {
                binding.etAddress.setText(url)
                binding.progress.visibility = View.VISIBLE
                updateNavButtons()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            val tab = tabOf(view) ?: return
            tab.url = url
            tab.title = view.title ?: url
            if (!tab.isPrivate && url.startsWith("http")) {
                db.addHistory(url, tab.title)
            }
            if (tab.id == currentTabId) {
                binding.progress.visibility = View.GONE
                binding.etAddress.setText(url)
                updateNavButtons()
                syncPinButton()
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (tabOf(view)?.id == currentTabId) updateNavButtons()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame && tabOf(view)?.id == currentTabId) {
                snack(R.string.page_load_failed)
            }
        }
    }

    private fun createChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (tabOf(view)?.id != currentTabId) return
            binding.progress.progress = newProgress
            if (newProgress >= 100) binding.progress.visibility = View.GONE
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            tabOf(view)?.title = title
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                fileChooserLauncher.launch(params.createIntent())
                true
            } catch (e: Exception) {
                filePathCallback = null
                false
            }
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val owner = currentTab()
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val temp = WebView(this@MainActivity)
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    if (DomainGuard.isAllowed(url) && isWebScheme(url)) {
                        addTab(isPrivate = owner?.isPrivate == true, url = url)
                    } else {
                        reportBlocked(url)
                    }
                    return true
                }
            }
            transport.webView = temp
            resultMsg.sendToTarget()
            return true
        }

        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ): Boolean {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ): Boolean {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String?,
            result: JsPromptResult
        ): Boolean {
            val input = EditText(this@MainActivity).apply { setText(defaultValue ?: "") }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.confirm(input.text.toString())
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    // ---------- 交互 ----------

    private fun setupActions() {
        binding.btnGo.setOnClickListener { loadFromInput(binding.etAddress.text.toString()) }
        binding.etAddress.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadFromInput(binding.etAddress.text.toString())
                true
            } else {
                false
            }
        }

        binding.btnPin.setOnClickListener {
            if (DomainGuard.isPinned()) disablePin() else enablePin()
            syncPinButton()
        }

        binding.btnTabs.setOnClickListener { showTabsDialog() }

        binding.btnBack.setOnClickListener {
            if (currentTab()?.webView?.canGoBack() == true) currentTab()?.webView?.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (currentTab()?.webView?.canGoForward() == true) currentTab()?.webView?.goForward()
        }
        binding.btnRefresh.setOnClickListener { currentTab()?.webView?.reload() }
        binding.btnHome.setOnClickListener { loadUrl(prefs.homeUrl()) }
        binding.btnMore.setOnClickListener { showMenu() }
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.btnMore)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> addTab(false, prefs.homeUrl())
                R.id.menu_new_private_tab -> addTab(true, "about:blank")
                R.id.menu_tabs -> showTabsDialog()
                R.id.menu_add_bookmark -> addBookmark()
                R.id.menu_bookmarks -> openRecords("bookmarks")
                R.id.menu_history -> openRecords("history")
                R.id.menu_downloads -> startActivity(Intent(this, DownloadsActivity::class.java))
                R.id.menu_settings ->
                    settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                R.id.menu_share -> shareCurrent()
                R.id.menu_desktop -> toggleDesktop()
                R.id.menu_exit -> finish()
            }
            true
        }
        popup.show()
    }

    private fun addBookmark() {
        val tab = currentTab() ?: return
        if (tab.isPrivate) {
            snack(R.string.bookmark_blocked_private)
            return
        }
        val url = tab.url
        if (!url.startsWith("http")) return
        val added = db.addBookmark(url, tab.title.ifEmpty { url })
        snack(if (added) R.string.bookmark_added else R.string.bookmark_exists)
    }

    private fun openRecords(mode: String) {
        val intent = Intent(this, RecordsActivity::class.java).putExtra("mode", mode)
        recordsLauncher.launch(intent)
    }

    private fun shareCurrent() {
        val tab = currentTab() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, tab.title)
            putExtra(Intent.EXTRA_TEXT, tab.url)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun toggleDesktop() {
        prefs.desktop = !prefs.desktop
        applySettingsAll()
        tabs.forEach { it.webView.reload() }
        snack(if (prefs.desktop) R.string.desktop_on else R.string.desktop_off)
    }

    private fun enablePin() {
        val base = DomainGuard.pinFrom(currentTab()?.url ?: "")
        if (base == null) {
            snack(R.string.pin_need_page)
            syncPinButton()
            return
        }
        prefs.pinnedBase = base
        snack(getString(R.string.pinned_to, base))
    }

    private fun disablePin() {
        DomainGuard.clear()
        prefs.pinnedBase = null
        snack(R.string.pin_off)
    }

    private fun syncPinButton() {
        val pinned = DomainGuard.isPinned()
        binding.btnPin.setImageResource(
            if (pinned) R.drawable.ic_lock_closed_24 else R.drawable.ic_lock_open_24
        )
        val tint = MaterialColors.getColor(
            binding.btnPin,
            if (pinned) com.google.android.material.R.attr.colorPrimary
            else android.R.attr.colorControlNormal
        )
        binding.btnPin.imageTintList = ColorStateList.valueOf(tint)
        val description = if (pinned) {
            getString(R.string.pinned_to, DomainGuard.base() ?: "")
        } else {
            getString(R.string.pin_site)
        }
        binding.btnPin.contentDescription = description
        binding.btnPin.tooltipText = description
    }

    private fun loadFromInput(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val url = when {
            isWebScheme(input) -> input
            looksLikeHost(input) -> "https://$input"
            else -> SearchEngines.build(prefs.searchTemplate(), input)
        }
        loadUrl(url)
    }

    private fun looksLikeHost(value: String): Boolean {
        if (value.contains(" ") || !value.contains(".")) return false
        if (!value.matches(Regex("^[A-Za-z0-9.-]+$"))) return false
        val last = value.substringAfterLast('.')
        return last.isNotEmpty() && last.all { it.isLetterOrDigit() }
    }

    private fun loadUrl(url: String) {
        if (url.isEmpty()) return
        if (!DomainGuard.isAllowed(url)) {
            reportBlocked(url)
            return
        }
        val tab = currentTab() ?: return
        tab.url = url
        binding.etAddress.setText(url)
        tab.webView.loadUrl(url)
        hideKeyboard()
    }

    private fun isWebScheme(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("about:") || url.startsWith("data:") ||
            url.startsWith("file:")

    private fun reportBlocked(url: String) {
        val base = DomainGuard.base() ?: return
        val short = runCatching { Uri.parse(url).host ?: url.take(48) }
            .getOrDefault(url.take(48))
        runOnUiThread {
            Snackbar.make(
                binding.root,
                getString(R.string.blocked_notice, base, short),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun updateNavButtons() {
        val wv = currentTab()?.webView
        binding.btnBack.isEnabled = wv?.canGoBack() == true
        binding.btnForward.isEnabled = wv?.canGoForward() == true
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(binding.etAddress.windowToken, 0)
        binding.etAddress.clearFocus()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = currentTab()?.webView
                if (wv?.canGoBack() == true) {
                    wv.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackTime < 2000) {
                        finish()
                    } else {
                        lastBackTime = now
                        Toast.makeText(
                            this@MainActivity,
                            R.string.press_back_again,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    private fun snack(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }

    private fun snack(resId: Int) {
        snack(getString(resId))
    }
}
