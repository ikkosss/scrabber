package com.example.bankscraperlogger

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.bankscraperlogger.databinding.ActivityMainBinding
import com.example.bankscraperlogger.export.ExportFolderManager
import com.example.bankscraperlogger.export.ExportWriter
import com.example.bankscraperlogger.export.SessionDomains
import com.example.bankscraperlogger.export.ZipToFolderExporter
import com.example.bankscraperlogger.history.HistoryStore
import com.example.bankscraperlogger.history.HistorySuggestionAdapter
import com.example.bankscraperlogger.logging.LogRepository
import com.example.bankscraperlogger.security.AppLockStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val gson = Gson()
    private val okHttp = OkHttpClient()

    private lateinit var repo: LogRepository
    private val exportWriter = ExportWriter()
    private lateinit var exportFolderManager: ExportFolderManager
    private lateinit var zipToFolderExporter: ZipToFolderExporter
    private lateinit var appLock: AppLockStore
    private lateinit var historyStore: HistoryStore
    private lateinit var historyAdapter: HistorySuggestionAdapter

    private var currentMainUrl: String? = null
    private var pendingZipExportAfterFolderPick: Boolean = false
    private var isLocked: Boolean = false
    private var stopExportOfferHandledSessionId: String? = null
    private var pendingAllowedHostsForExport: Set<String>? = null

    private val pickExportFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                // If we were waiting to export after stop, cancellation means "no retry" for this session.
                if (pendingZipExportAfterFolderPick) pendingZipExportAfterFolderPick = false
                return@registerForActivityResult
            }
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Throwable) {
                // Best-effort; some providers may not allow persistable permissions.
            }
            exportFolderManager.setExportFolderUri(uri)
            toast(getString(R.string.toast_export_folder_selected))
            if (pendingZipExportAfterFolderPick) {
                pendingZipExportAfterFolderPick = false
                exportZipToChosenFolder()
            }
        }

    private val intensityHandler = Handler(Looper.getMainLooper())
    private var intensityEma = 0f

    private val intensityTicker = object : Runnable {
        override fun run() {
            val target = if (repo.isRecording()) {
                val sample = repo.drainActivitySample()
                // Weight pages higher (HTML snapshots are the “heavy” writes).
                val score = sample.events * 1.0 + sample.pages * 6.0 + (sample.bytes / 2048.0)
                val dyn = (score / 14.0).toFloat().coerceIn(0f, 1f)
                // Base movement while collecting, even if traffic is low.
                (0.22f + 0.78f * dyn).coerceIn(0f, 1f)
            } else {
                0f
            }

            // Smooth to avoid jitter.
            intensityEma = (0.70f * intensityEma + 0.30f * target).coerceIn(0f, 1f)
            binding.intensityView.setIntensity(intensityEma)

            intensityHandler.postDelayed(this, 120)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = LogRepository(applicationContext)
        exportFolderManager = ExportFolderManager(this)
        zipToFolderExporter = ZipToFolderExporter(this, exportWriter)
        appLock = AppLockStore(this)
        historyStore = HistoryStore(this)
        historyAdapter = HistorySuggestionAdapter(this, historyStore)
        setupLockUi()

        WebView.setWebContentsDebuggingEnabled(true)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                // Keep minimal UI; title is stored on HTML snapshot capture.
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                binding.urlEditText.setText(url)
                if (repo.isRecording()) repo.logVisitedUrl(url, "shouldOverrideUrlLoading")
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                currentMainUrl = url
                binding.urlEditText.setText(url)
                if (repo.isRecording()) repo.logVisitedUrl(url, "onPageStarted")
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.urlEditText.setText(url)
                currentMainUrl = url

                // Global history (for omnibox suggestions), regardless of recording.
                historyStore.recordVisit(url = url, title = view.title)

                if (!repo.isRecording()) return

                val cookies = try {
                    CookieManager.getInstance().getCookie(url)
                } catch (_: Throwable) {
                    null
                }

                view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { value ->
                    try {
                        // value is a JSON string (quoted/escaped) or "null"
                        val html = if (value == null || value == "null") "" else gson.fromJson(value, String::class.java)
                        repo.logPageHtml(url = url, title = view.title, html = html, cookies = cookies)
                        CookieManager.getInstance().flush()
                    } catch (_: Throwable) {
                        // Ignore best-effort capture errors.
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                if (repo.isRecording()) {
                    repo.logRequest(request, currentMainUrl)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        binding.urlEditText.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                loadFromBar()
                true
            } else {
                false
            }
        }

        binding.urlEditText.setAdapter(historyAdapter)
        binding.urlEditText.setOnItemClickListener { _, _, position, _ ->
            val s = historyAdapter.getItem(position) ?: return@setOnItemClickListener
            binding.urlEditText.setText(s.url)
            binding.webView.loadUrl(s.url)
            setAddressModeVisible(false)
        }

        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.forwardButton.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.reloadButton.setOnClickListener {
            binding.webView.reload()
        }

        binding.recordPauseButton.setOnClickListener { recordOrPauseOrResume() }
        binding.stopButton.setOnClickListener { stopRecording(withPrompt = true) }
        binding.timeWarpButton.setOnClickListener {
            if (!appLock.isEnabled()) {
                toast(getString(R.string.pin_not_set))
            } else {
                appLock.simulateForward(hours = 1)
                showLockOverlay()
            }
        }

        binding.modeToggleButton.setOnClickListener { toggleMode() }
        binding.modeToggleButton.setOnLongClickListener {
            showSecurityMenu()
            true
        }
        binding.addressModeToggleButton.setOnClickListener { toggleMode() }

        if (savedInstanceState == null) {
            val startUrl = "https://google.ru"
            binding.urlEditText.setText(startUrl)
            binding.webView.loadUrl(startUrl)
            setAddressModeVisible(false)
            syncRecordingUi()
        }

        // Size timer to 2 buttons width + gap (after layout).
        binding.buttonsRow.post {
            val btnW = binding.buttonsRow.getChildAt(0).width
            val gapPx = (3 * resources.displayMetrics.density).toInt()
            binding.recordingTimer.layoutParams = (binding.recordingTimer.layoutParams as android.widget.FrameLayout.LayoutParams).also {
                it.width = btnW * 2 + gapPx
            }
        }

        intensityHandler.post(intensityTicker)
    }

    override fun onStop() {
        super.onStop()
        appLock.markBackgroundNow()
    }

    override fun onStart() {
        super.onStart()
        if (appLock.shouldLock()) {
            showLockOverlay()
        }
    }

    override fun onDestroy() {
        intensityHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun loadFromBar() {
        val raw = binding.urlEditText.text?.toString().orEmpty()
        val resolved = resolveInput(raw)
        binding.urlEditText.setText(resolved.displayText)
        binding.webView.loadUrl(resolved.targetUrl)
        if (repo.isRecording()) repo.logVisitedUrl(resolved.targetUrl, "manual_loadUrl")
        setAddressModeVisible(false)
    }

    private data class ResolvedInput(
        val targetUrl: String,
        val displayText: String,
    )

    private fun resolveInput(input: String): ResolvedInput {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ResolvedInput(targetUrl = "https://google.ru", displayText = "https://google.ru")
        }

        val looksLikeUrl = isProbablyUrl(trimmed)
        if (!looksLikeUrl) {
            val q = URLEncoder.encode(trimmed, "UTF-8")
            return ResolvedInput(
                targetUrl = "https://www.google.ru/search?q=$q",
                displayText = trimmed,
            )
        }

        val hasScheme = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
        val candidate = if (hasScheme) trimmed else "https://$trimmed"
        return ResolvedInput(targetUrl = candidate, displayText = candidate)
    }

    private fun isProbablyUrl(text: String): Boolean {
        if (text.contains(' ')) return false
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) return true
        // Simple domain heuristic: must contain a dot and some letters/digits.
        if (!text.contains('.')) return false
        val withScheme = "https://$text"
        return Patterns.WEB_URL.matcher(withScheme).matches()
    }

    private fun fetchAndStoreExternalIp() {
        val request = Request.Builder()
            .url("https://api.ipify.org?format=json")
            .get()
            .build()

        Thread {
            try {
                okHttp.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val ip = try {
                        gson.fromJson(body, Map::class.java)["ip"]?.toString()
                    } catch (_: Throwable) {
                        null
                    }
                    if (!ip.isNullOrBlank()) repo.setExternalIp(ip)
                }
            } catch (_: IOException) {
                // Ignore: optional feature.
            } catch (_: Throwable) {
                // Ignore.
            }
        }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun exportZipToChosenFolder() {
        val dir = repo.getLastSessionDir() ?: run {
            toast(getString(R.string.toast_no_session_to_export))
            return
        }

        val folder = exportFolderManager.getExportFolder()
        if (folder == null) {
            pendingZipExportAfterFolderPick = true
            toast(getString(R.string.toast_choose_export_folder))
            pickExportFolderLauncher.launch(null)
            return
        }

        val bankUrl = repo.getMeta()?.initialUrl ?: currentMainUrl
        try {
            val result = zipToFolderExporter.export(dir, folder, bankUrl, allowedHosts = pendingAllowedHostsForExport)
            pendingAllowedHostsForExport = null
            toast(getString(R.string.toast_saved, result.displayName))
        } catch (t: Throwable) {
            pendingAllowedHostsForExport = null
            toast(getString(R.string.toast_export_failed, t.message ?: t.javaClass.simpleName))
        }
    }

    private fun setAddressModeVisible(visible: Boolean) {
        binding.urlInputLayout.visibility = if (visible) View.VISIBLE else View.GONE
        binding.modeToggleButton.setImageResource(if (visible) R.drawable.ic_toggle_grid else R.drawable.ic_text_cursor)

        val imm = getSystemService(InputMethodManager::class.java)
        if (visible) {
            binding.urlEditText.requestFocus()
            imm?.showSoftInput(binding.urlEditText, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm?.hideSoftInputFromWindow(binding.urlEditText.windowToken, 0)
            binding.webView.requestFocus()
        }
    }

    private fun toggleMode() {
        val isAddressVisible = binding.urlInputLayout.visibility == View.VISIBLE
        setAddressModeVisible(!isAddressVisible)
    }

    private fun recordOrPauseOrResume() {
        when (repo.getRecordingState()) {
            LogRepository.RecordingState.STOPPED -> {
                val ua = binding.webView.settings.userAgentString ?: "unknown"
                val initial = currentMainUrl ?: binding.urlEditText.text?.toString()
                repo.startNewSession(userAgent = ua, initialUrl = initial?.takeIf { it.isNotBlank() })
                toast(getString(R.string.toast_recording_started))
                fetchAndStoreExternalIp()
                binding.recordingTimer.startRecording()
            }
            LogRepository.RecordingState.RECORDING -> {
                repo.pauseSession()
                toast(getString(R.string.toast_paused))
                binding.recordingTimer.pauseRecording()
            }
            LogRepository.RecordingState.PAUSED -> {
                repo.resumeSession()
                toast(getString(R.string.toast_resumed))
                binding.recordingTimer.resumeRecording()
            }
        }
        syncRecordingUi()
    }

    private fun stopRecording(withPrompt: Boolean) {
        val wasStopped = repo.getRecordingState() == LogRepository.RecordingState.STOPPED
        val sessionId = repo.getMeta()?.sessionId
        repo.stopSession()
        if (!wasStopped) toast(getString(R.string.toast_stopped))
        binding.recordingTimer.stopRecording()
        syncRecordingUi()

        if (!withPrompt || wasStopped) return
        if (sessionId != null && sessionId == stopExportOfferHandledSessionId) return
        stopExportOfferHandledSessionId = sessionId

        val sessionDir = repo.getLastSessionDir()
        if (sessionDir == null) return

        Thread {
            val domains = SessionDomains.collect(sessionDir)
            runOnUiThread {
                showDomainExportDialog(domains)
            }
        }.start()
    }

    private fun showDomainExportDialog(domains: List<SessionDomains.DomainCount>) {
        if (domains.isEmpty()) {
            // Fallback: export everything.
            pendingAllowedHostsForExport = null
            exportZipToChosenFolder()
            return
        }

        val labels = domains.map { "${it.host} (${it.count})" }.toTypedArray()
        val checked = BooleanArray(domains.size) { idx -> idx == 0 } // default: most frequent

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.export_after_stop_title))
            .setMessage(getString(R.string.export_after_stop_msg))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(getString(R.string.export_now)) { _, _ ->
                val selectedHosts = domains
                    .filterIndexed { i, _ -> checked[i] }
                    .map { it.host }
                    .toSet()
                if (selectedHosts.isEmpty()) {
                    toast(getString(R.string.export_domains_empty))
                    return@setPositiveButton
                }
                pendingAllowedHostsForExport = selectedHosts
                exportZipToChosenFolder()
            }
            .setNegativeButton(getString(R.string.export_later), null)
            .show()
    }

    private fun syncRecordingUi() {
        when (repo.getRecordingState()) {
            LogRepository.RecordingState.STOPPED -> {
                binding.stopButton.isEnabled = false
                binding.stopButton.alpha = 0.45f
                binding.recordPauseButton.isEnabled = true
                binding.recordPauseButton.alpha = 1f
                binding.recordPauseButton.setImageResource(R.drawable.ic_record)
            }
            LogRepository.RecordingState.RECORDING -> {
                binding.stopButton.isEnabled = true
                binding.stopButton.alpha = 1f
                binding.recordPauseButton.isEnabled = true
                binding.recordPauseButton.alpha = 1f
                binding.recordPauseButton.setImageResource(R.drawable.ic_pause)
            }
            LogRepository.RecordingState.PAUSED -> {
                binding.stopButton.isEnabled = true
                binding.stopButton.alpha = 1f
                binding.recordPauseButton.isEnabled = true
                binding.recordPauseButton.alpha = 1f
                binding.recordPauseButton.setImageResource(R.drawable.ic_record)
            }
        }
    }

    private fun showLockOverlay() {
        isLocked = true
        setAddressModeVisible(false)
        binding.lockOverlay.visibility = View.VISIBLE
        binding.pinEditText.setText("")
        binding.pinInputLayout.error = null
        binding.pinEditText.requestFocus()
    }

    private fun hideLockOverlay() {
        isLocked = false
        binding.lockOverlay.visibility = View.GONE
        binding.webView.requestFocus()
    }

    private fun setupLockUi() {
        binding.unlockButton.setOnClickListener {
            val pin = binding.pinEditText.text?.toString().orEmpty()
            if (appLock.verifyPin(pin)) {
                appLock.markUnlockedNow()
                binding.pinInputLayout.error = null
                hideLockOverlay()
            } else {
                binding.pinInputLayout.error = getString(R.string.wrong_pin)
            }
        }

        binding.simulateTimeButton.setOnClickListener {
            appLock.simulateForward(hours = 1)
            showLockOverlay()
        }
    }

    private fun showSecurityMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.security))
            .setItems(arrayOf(getString(R.string.set_pin), getString(R.string.disable_pin), getString(R.string.lock_now), getString(R.string.simulate_time))) { _, which ->
                when (which) {
                    0 -> showSetPinDialog()
                    1 -> {
                        appLock.disable()
                        toast(getString(R.string.toast_pin_disabled))
                    }
                    2 -> {
                        if (!appLock.isEnabled()) {
                            toast(getString(R.string.pin_not_set))
                        } else {
                            showLockOverlay()
                        }
                    }
                    3 -> {
                        if (!appLock.isEnabled()) {
                            toast(getString(R.string.pin_not_set))
                        } else {
                            appLock.simulateForward(hours = 1)
                            showLockOverlay()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSetPinDialog() {
        val input = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.set_pin))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text?.toString().orEmpty()
                if (pin.length !in 4..12) {
                    toast(getString(R.string.toast_pin_length))
                    return@setPositiveButton
                }
                appLock.setOrChangePin(pin)
                toast(getString(R.string.toast_pin_set))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

