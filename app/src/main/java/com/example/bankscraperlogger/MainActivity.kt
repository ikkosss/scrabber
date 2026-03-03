package com.example.bankscraperlogger

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    private data class PendingBlobDownload(
        val originalUrl: String,
        val outFile: File,
        val inRecording: Boolean,
        val displayName: String,
    )

    private val pendingBlobDownloads = ConcurrentHashMap<String, PendingBlobDownload>()

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

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
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

        binding.webView.addJavascriptInterface(BlobDownloadBridge(), "BSLDownloadBridge")

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

        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            if (url.isNullOrBlank()) return@setDownloadListener
            startDownload(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
            )
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

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val cookieHeader = try { CookieManager.getInstance().getCookie(url) } catch (_: Throwable) { null }

        val inRecording = repo.isRecording()
        val sessionDir = if (inRecording) repo.getActiveSessionDir() else null
        val outFile: File? = if (sessionDir != null) {
            val downloadsDir = File(sessionDir, "downloads").apply { mkdirs() }
            File(
                downloadsDir,
                "${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}_${sanitizeFilename(guessedName)}",
            )
        } else {
            val dir = File(cacheDir, "bsl_downloads").apply { mkdirs() }
            File(dir, sanitizeFilename(guessedName))
        }

        val scheme = try { Uri.parse(url).scheme?.lowercase(Locale.US) } catch (_: Throwable) { null }
        if (scheme == "blob") {
            if (outFile != null) {
                if (inRecording) {
                    repo.logDownloadStarted(
                        url = url,
                        filename = outFile.name,
                        mimeType = mimeType,
                        contentLength = contentLength,
                        userAgent = userAgent,
                        referer = currentMainUrl,
                    )
                }
                startBlobDownload(
                    blobUrl = url,
                    outFile = outFile,
                    inRecording = inRecording,
                    displayName = outFile.name,
                )
            } else {
                toast(getString(R.string.toast_download_failed, "No output file"))
            }
            return
        }

        if (scheme != null && scheme != "http" && scheme != "https") {
            if (inRecording && outFile != null) {
                repo.logDownloadStarted(
                    url = url,
                    filename = outFile.name,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    userAgent = userAgent,
                    referer = currentMainUrl,
                )
                repo.logDownloadFinished(
                    url = url,
                    filename = outFile.name,
                    relativePath = "downloads/${outFile.name}",
                    bytes = 0,
                    error = "Unsupported URL scheme: $scheme",
                )
            }
            toast(getString(R.string.toast_download_failed, "Unsupported URL scheme: $scheme"))
            return
        }

        if (inRecording && outFile != null) {
            repo.logDownloadStarted(
                url = url,
                filename = outFile.name,
                mimeType = mimeType,
                contentLength = contentLength,
                userAgent = userAgent,
                referer = currentMainUrl,
            )
        }

        Thread {
            var bytes: Long = 0
            var error: String? = null
            try {
                val tmpFile = outFile?.let { File(it.parentFile, it.name + ".part") }
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .apply {
                        if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
                        if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader)
                        if (!currentMainUrl.isNullOrBlank()) header("Referer", currentMainUrl!!)
                    }
                    .build()

                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty body")
                    tmpFile?.parentFile?.mkdirs()
                    FileOutputStream(tmpFile ?: outFile).use { fos ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                                bytes += n.toLong()
                            }
                        }
                        fos.flush()
                    }
                }
                if (tmpFile != null && outFile != null) {
                    if (!tmpFile.renameTo(outFile)) {
                        throw IOException("Failed to finalize download")
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                try {
                    outFile?.delete()
                    outFile?.let { File(it.parentFile, it.name + ".part") }?.delete()
                } catch (_: Throwable) {
                }
            }

            if (inRecording) {
                repo.logDownloadFinished(
                    url = url,
                    filename = outFile?.name,
                    relativePath = outFile?.let { "downloads/${it.name}" },
                    bytes = bytes,
                    error = error,
                )
            }

            runOnUiThread {
                if (error == null) {
                    toast(getString(R.string.toast_download_ok, outFile?.name ?: guessedName))
                } else {
                    toast(getString(R.string.toast_download_failed, error))
                }
            }
        }.start()
    }

    private fun startBlobDownload(
        blobUrl: String,
        outFile: File,
        inRecording: Boolean,
        displayName: String,
    ) {
        val token = UUID.randomUUID().toString().take(8)
        pendingBlobDownloads[token] = PendingBlobDownload(
            originalUrl = blobUrl,
            outFile = outFile,
            inRecording = inRecording,
            displayName = displayName,
        )

        val js = """
            (function() {
              const token = ${gson.toJson(token)};
              const url = ${gson.toJson(blobUrl)};
              const filename = ${gson.toJson(displayName)};
              const MAX = 25 * 1024 * 1024;
              try {
                fetch(url).then(r => r.blob()).then(blob => {
                  const size = blob && blob.size ? blob.size : 0;
                  if (size && size > MAX) throw new Error("Blob too large: " + size);
                  return new Promise((resolve, reject) => {
                    const fr = new FileReader();
                    fr.onerror = () => reject(fr.error || new Error("FileReader error"));
                    fr.onloadend = () => resolve(fr.result);
                    fr.readAsDataURL(blob);
                  });
                }).then(dataUrl => {
                  window.BSLDownloadBridge.onBlobData(token, String(dataUrl), filename);
                }).catch(err => {
                  window.BSLDownloadBridge.onBlobError(token, String(err && err.message ? err.message : err));
                });
              } catch (e) {
                window.BSLDownloadBridge.onBlobError(token, String(e && e.message ? e.message : e));
              }
            })();
        """.trimIndent()

        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private inner class BlobDownloadBridge {
        @JavascriptInterface
        fun onBlobData(token: String, dataUrl: String, filename: String) {
            val ctx = pendingBlobDownloads.remove(token) ?: return
            Thread {
                var bytes: Long = 0
                var error: String? = null
                try {
                    val base64Marker = "base64,"
                    val idx = dataUrl.indexOf(base64Marker)
                    if (idx < 0) throw IOException("Invalid data URL")
                    val b64 = dataUrl.substring(idx + base64Marker.length)
                    val decoded = Base64.decode(b64, Base64.DEFAULT)

                    val tmpFile = File(ctx.outFile.parentFile, ctx.outFile.name + ".part")
                    tmpFile.parentFile?.mkdirs()
                    FileOutputStream(tmpFile).use { fos ->
                        fos.write(decoded)
                        fos.flush()
                    }
                    if (!tmpFile.renameTo(ctx.outFile)) {
                        throw IOException("Failed to finalize blob download")
                    }
                    bytes = decoded.size.toLong()
                } catch (t: Throwable) {
                    error = t.message ?: t.javaClass.simpleName
                    try {
                        ctx.outFile.delete()
                        File(ctx.outFile.parentFile, ctx.outFile.name + ".part").delete()
                    } catch (_: Throwable) {
                    }
                }

                if (ctx.inRecording) {
                    repo.logDownloadFinished(
                        url = ctx.originalUrl,
                        filename = ctx.outFile.name,
                        relativePath = "downloads/${ctx.outFile.name}",
                        bytes = bytes,
                        error = error,
                    )
                }

                runOnUiThread {
                    if (error == null) {
                        toast(getString(R.string.toast_download_ok, filename))
                    } else {
                        toast(getString(R.string.toast_download_failed, error))
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun onBlobError(token: String, error: String) {
            val ctx = pendingBlobDownloads.remove(token)
            if (ctx?.inRecording == true) {
                repo.logDownloadFinished(
                    url = ctx.originalUrl,
                    filename = ctx.outFile.name,
                    relativePath = "downloads/${ctx.outFile.name}",
                    bytes = 0,
                    error = error,
                )
            }
            runOnUiThread {
                toast(getString(R.string.toast_download_failed, error))
            }
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(120)
            .ifBlank { "download.bin" }
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
        binding.buttonsContainer.visibility = if (visible) View.GONE else View.VISIBLE

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

        val sessionDir = repo.getLastSessionDir() ?: return

        Thread {
            val dominant = SessionDomains.dominantHostByTime(sessionDir)
                ?: SessionDomains.collect(sessionDir).firstOrNull()?.let { SessionDomains.DomainTime(it.host, 0L) }
            runOnUiThread {
                pendingAllowedHostsForExport = dominant?.let { setOf(it.host) }
                showExportAfterStopDialog()
            }
        }.start()
    }

    private fun showExportAfterStopDialog() {
        val domainLabel = pendingAllowedHostsForExport?.firstOrNull() ?: "?"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.export_after_stop_title))
            .setMessage(getString(R.string.export_after_stop_msg, domainLabel))
            .setPositiveButton(getString(R.string.export_now)) { _, _ ->
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
                applyRecordingSystemUi()
            }
            LogRepository.RecordingState.RECORDING -> {
                binding.stopButton.isEnabled = true
                binding.stopButton.alpha = 1f
                binding.recordPauseButton.isEnabled = true
                binding.recordPauseButton.alpha = 1f
                binding.recordPauseButton.setImageResource(R.drawable.ic_pause)
                applyRecordingSystemUi()
            }
            LogRepository.RecordingState.PAUSED -> {
                binding.stopButton.isEnabled = true
                binding.stopButton.alpha = 1f
                binding.recordPauseButton.isEnabled = true
                binding.recordPauseButton.alpha = 1f
                binding.recordPauseButton.setImageResource(R.drawable.ic_record)
                applyRecordingSystemUi()
            }
        }
    }

    private fun applyRecordingSystemUi() {
        val lp = window.attributes
        when (repo.getRecordingState()) {
            LogRepository.RecordingState.RECORDING -> {
                window.statusBarColor = getColor(R.color.record_color)
                lp.screenBrightness = 1.0f
            }
            LogRepository.RecordingState.PAUSED -> {
                window.statusBarColor = getColor(R.color.media_button_stroke)
                lp.screenBrightness = 0.55f
            }
            LogRepository.RecordingState.STOPPED -> {
                val bg = MaterialColors.getColor(window.decorView, android.R.attr.colorBackground, getColor(R.color.md_theme_dark_background))
                window.statusBarColor = bg
                lp.screenBrightness = -1.0f
            }
        }
        window.attributes = lp
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
    }

    private fun showSecurityMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.security))
            .setItems(arrayOf(getString(R.string.set_pin), getString(R.string.disable_pin), getString(R.string.lock_now))) { _, which ->
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

