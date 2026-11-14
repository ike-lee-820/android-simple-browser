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
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
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
import com.yuanbao.simplebrowser.databinding.ActivityMainBinding
import com.yuanbao.simplebrowser.download.DownloadEngine
import com.yuanbao.simplebrowser.download.DownloadsActivity
import com.yuanbao.simplebrowser.ui.RecordsActivity
import com.yuanbao.simplebrowser.ui.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var db: BrowserDb

    private var systemUa: String? = null
    private var appliedUa: String? = null
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        db = BrowserDb(this)
        DownloadEngine.init(this)
        DomainGuard.restore(prefs.pinnedBase)

        setupWebView()
        setupActions()
        setupBackHandling()
        syncPinButton()

        val incoming = intent?.data?.toString()
        if (savedInstanceState == null) {
            loadUrl(if (!incoming.isNullOrEmpty()) incoming else prefs.homeUrl())
        } else {
            binding.webView.restoreState(savedInstanceState)
            binding.etAddress.setText(binding.webView.url ?: "")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::prefs.isInitialized && applySettings()) {
            binding.webView.reload()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) binding.webView.saveState(outState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws = binding.webView.settings
        systemUa = ws.userAgentString
        applySettings()
        ws.domStorageEnabled = true
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.allowFileAccess = true
        ws.javaScriptCanOpenWindowsAutomatically = true
        ws.setSupportMultipleWindows(true)

        binding.webView.webViewClient = createClient()
        binding.webView.webChromeClient = createChromeClient()
        binding.webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            DownloadEngine.enqueue(this, url, name)
            snack(R.string.download_added)
        }
    }

    private fun applySettings(): Boolean {
        val ws = binding.webView.settings
        val ua = prefs.resolveUa(systemUa)
        var uaChanged = false
        if (ua != null && ua != appliedUa) {
            ws.userAgentString = ua
            appliedUa = ua
            uaChanged = true
        }
        ws.javaScriptEnabled = prefs.javascript
        ws.useWideViewPort = prefs.desktop
        ws.loadWithOverviewMode = !prefs.desktop
        return uaChanged
    }

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
            // 兜底：POST 提交、服务端重定向等不经 shouldOverrideUrlLoading 的主框架导航。
            if (DomainGuard.isPinned() && !DomainGuard.isAllowed(url)) {
                view.stopLoading()
                reportBlocked(url)
                if (view.canGoBack()) view.goBack()
                return
            }
            binding.etAddress.setText(url)
            binding.progress.visibility = View.VISIBLE
            updateNavButtons()
        }

        override fun onPageFinished(view: WebView, url: String) {
            binding.progress.visibility = View.GONE
            binding.etAddress.setText(url)
            updateNavButtons()
            if (url.startsWith("http")) {
                db.addHistory(url, view.title ?: url)
            }
            syncPinButton()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            updateNavButtons()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) snack(R.string.page_load_failed)
        }
    }

    private fun createChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.progress = newProgress
            if (newProgress >= 100) binding.progress.visibility = View.GONE
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
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val temp = WebView(this@MainActivity)
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    if (DomainGuard.isAllowed(url) && isWebScheme(url)) {
                        binding.webView.post { loadUrl(url) }
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

        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnRefresh.setOnClickListener { binding.webView.reload() }
        binding.btnHome.setOnClickListener { loadUrl(prefs.homeUrl()) }
        binding.btnMore.setOnClickListener { showMenu() }
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.btnMore)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_add_bookmark -> addBookmark()
                R.id.menu_bookmarks -> openRecords("bookmarks")
                R.id.menu_history -> openRecords("history")
                R.id.menu_downloads -> startActivity(Intent(this, DownloadsActivity::class.java))
                R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.menu_share -> shareCurrent()
                R.id.menu_desktop -> toggleDesktop()
                R.id.menu_exit -> finish()
            }
            true
        }
        popup.show()
    }

    private fun addBookmark() {
        val url = binding.webView.url ?: return
        if (!url.startsWith("http")) return
        val added = db.addBookmark(url, binding.webView.title ?: url)
        snack(if (added) R.string.bookmark_added else R.string.bookmark_exists)
    }

    private fun openRecords(mode: String) {
        val intent = Intent(this, RecordsActivity::class.java).putExtra("mode", mode)
        recordsLauncher.launch(intent)
    }

    private fun shareCurrent() {
        val url = binding.webView.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, binding.webView.title ?: "")
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun toggleDesktop() {
        prefs.desktop = !prefs.desktop
        applySettings()
        snack(if (prefs.desktop) R.string.desktop_on else R.string.desktop_off)
        binding.webView.reload()
    }

    private fun enablePin() {
        val base = DomainGuard.pinFrom(binding.webView.url ?: "")
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
        binding.etAddress.setText(url)
        binding.webView.loadUrl(url)
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
        binding.btnBack.isEnabled = binding.webView.canGoBack()
        binding.btnForward.isEnabled = binding.webView.canGoForward()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(binding.etAddress.windowToken, 0)
        binding.etAddress.clearFocus()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
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
