package com.example.bankscraperlogger

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.example.bankscraperlogger.export.ZipToFolderExporter
import com.example.bankscraperlogger.logging.LogRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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

    private var currentMainUrl: String? = null
    private var pendingZipExportAfterFolderPick: Boolean = false

    private val exportJsonLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val dir = repo.getLastSessionDir()
            if (dir == null) {
                toast("No session to export yet. Press Start first.")
                return@registerForActivityResult
            }
            try {
                exportWriter.writeExportJson(this, dir, uri)
                toast("Exported JSON: $uri")
            } catch (t: Throwable) {
                toast("Export failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }

    private val pickExportFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Throwable) {
                // Best-effort; some providers may not allow persistable permissions.
            }
            exportFolderManager.setExportFolderUri(uri)
            toast("Export folder selected")
            if (pendingZipExportAfterFolderPick) {
                pendingZipExportAfterFolderPick = false
                exportZipToChosenFolder()
            }
        }

    private val intensityHandler = Handler(Looper.getMainLooper())
    private var intensityEma = 0f

    private val intensityTicker = object : Runnable {
        override fun run() {
            val target = if (repo.isCollecting()) {
                val sample = repo.drainActivitySample()
                // Weight pages higher (HTML snapshots are the “heavy” writes).
                val score = sample.events * 1.0 + sample.pages * 6.0 + (sample.bytes / 2048.0)
                (score / 18.0).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }

            // Smooth to avoid jitter.
            intensityEma = (0.75f * intensityEma + 0.25f * target).coerceIn(0f, 1f)
            binding.intensityView.setIntensity(intensityEma)

            intensityHandler.postDelayed(this, 250)
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
                if (repo.isCollecting()) repo.logVisitedUrl(url, "shouldOverrideUrlLoading")
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                currentMainUrl = url
                binding.urlEditText.setText(url)
                if (repo.isCollecting()) repo.logVisitedUrl(url, "onPageStarted")
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.urlEditText.setText(url)
                currentMainUrl = url

                if (!repo.isCollecting()) return

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
                if (repo.isCollecting()) {
                    repo.logRequest(request, currentMainUrl)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        binding.goButton.setOnClickListener {
            loadFromBar()
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

        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.forwardButton.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.reloadButton.setOnClickListener {
            binding.webView.reload()
        }

        binding.collectToggleButton.setOnClickListener {
            if (repo.isCollecting()) {
                repo.stopSession()
                binding.collectToggleButton.setIconResource(android.R.drawable.ic_media_play)
                toast("Collection stopped")
            } else {
                val ua = binding.webView.settings.userAgentString ?: "unknown"
                val initial = currentMainUrl ?: binding.urlEditText.text?.toString()
                repo.startNewSession(userAgent = ua, initialUrl = initial?.takeIf { it.isNotBlank() })
                binding.collectToggleButton.setIconResource(android.R.drawable.ic_media_pause)
                toast("Collection started")
                fetchAndStoreExternalIp()
            }
        }

        binding.addressButton.setOnClickListener {
            setUrlBarVisible(binding.urlInputLayout.visibility != View.VISIBLE)
        }

        binding.exportButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Export")
                .setItems(arrayOf("Export ZIP (to chosen folder)", "Choose export folder", "Export JSON (choose file)")) { _, which ->
                    when (which) {
                        0 -> exportZipToChosenFolder()
                        1 -> pickExportFolderLauncher.launch(null)
                        2 -> {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            exportJsonLauncher.launch("bankscraperlogger_export_$timestamp.json")
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Initial page for quick testing.
        if (savedInstanceState == null) {
            binding.urlEditText.setText("https://example.com")
            loadFromBar()
        }

        intensityHandler.post(intensityTicker)
    }

    override fun onDestroy() {
        intensityHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun loadFromBar() {
        val raw = binding.urlEditText.text?.toString().orEmpty()
        val url = normalizeUrl(raw)
        binding.urlEditText.setText(url)
        binding.webView.loadUrl(url)
        if (repo.isCollecting()) repo.logVisitedUrl(url, "manual_loadUrl")
        setUrlBarVisible(false)
    }

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "https://example.com"
        val hasScheme = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
        return if (hasScheme) trimmed else "https://$trimmed"
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
            toast("No session to export yet. Press Start first.")
            return
        }

        val folder = exportFolderManager.getExportFolder()
        if (folder == null) {
            pendingZipExportAfterFolderPick = true
            toast("Choose export folder…")
            pickExportFolderLauncher.launch(null)
            return
        }

        val bankUrl = repo.getMeta()?.initialUrl ?: currentMainUrl
        try {
            val result = zipToFolderExporter.export(dir, folder, bankUrl)
            toast("Saved: ${result.displayName}")
        } catch (t: Throwable) {
            toast("Export failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun setUrlBarVisible(visible: Boolean) {
        binding.urlInputLayout.visibility = if (visible) View.VISIBLE else View.GONE
        binding.goButton.visibility = if (visible) View.VISIBLE else View.GONE

        val imm = getSystemService(InputMethodManager::class.java)
        if (visible) {
            binding.urlEditText.requestFocus()
            imm?.showSoftInput(binding.urlEditText, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm?.hideSoftInputFromWindow(binding.urlEditText.windowToken, 0)
            binding.webView.requestFocus()
        }
    }
}

