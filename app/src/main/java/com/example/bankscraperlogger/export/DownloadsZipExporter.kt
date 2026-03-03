package com.example.bankscraperlogger.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

class DownloadsZipExporter(
    private val exportWriter: ExportWriter,
) {
    data class Result(
        val displayName: String,
        val uri: Uri?,
        val file: File?,
    )

    fun exportZipToDownloads(context: Context, sessionDir: File, bankUrl: String?): Result {
        val base = sanitizeHostForFilename(extractHost(bankUrl) ?: "export")
        val baseName = if (base.isBlank()) "export" else base

        return if (Build.VERSION.SDK_INT >= 29) {
            exportViaMediaStore(context, sessionDir, baseName)
        } else {
            exportViaLegacyDownloadsDir(sessionDir, baseName)
        }
    }

    private fun exportViaMediaStore(context: Context, sessionDir: File, baseName: String): Result {
        val resolver = context.contentResolver
        val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val displayName = nextAvailableNameMediaStore(context, baseName, "zip")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(downloads, values)
            ?: throw IllegalStateException("Failed to create download entry")

        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                exportWriter.writeExportZipToStream(sessionDir, out)
            } ?: throw IllegalStateException("Failed to open output stream for $uri")

            ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }.also { done ->
                resolver.update(uri, done, null, null)
            }

            return Result(displayName = displayName, uri = uri, file = null)
        } catch (t: Throwable) {
            // best-effort cleanup
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private fun exportViaLegacyDownloadsDir(sessionDir: File, baseName: String): Result {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()

        val file = nextAvailableNameFile(downloadsDir, baseName, "zip")
        FileOutputStream(file).use { out ->
            exportWriter.writeExportZipToStream(sessionDir, out)
        }
        return Result(displayName = file.name, uri = Uri.fromFile(file), file = file)
    }

    private fun nextAvailableNameMediaStore(context: Context, baseName: String, ext: String): String {
        val resolver = context.contentResolver
        val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        var i = 1
        while (true) {
            val candidate = if (i == 1) "$baseName.$ext" else "${baseName}_$i.$ext"
            val exists = resolver.query(
                downloads,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(candidate),
                null,
            )?.use { c -> c.moveToFirst() } ?: false

            if (!exists) return candidate
            i++
        }
    }

    private fun nextAvailableNameFile(dir: File, baseName: String, ext: String): File {
        var i = 1
        while (true) {
            val name = if (i == 1) "$baseName.$ext" else "${baseName}_$i.$ext"
            val file = File(dir, name)
            if (!file.exists()) return file
            i++
        }
    }

    private fun extractHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            android.net.Uri.parse(url).host
        } catch (_: Throwable) {
            null
        }
    }

    private fun sanitizeHostForFilename(host: String): String {
        // keep subdomains + TLD, replace everything else with '_'
        return host
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(80)
    }
}

