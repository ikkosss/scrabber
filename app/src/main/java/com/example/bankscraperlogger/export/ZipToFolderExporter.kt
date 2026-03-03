package com.example.bankscraperlogger.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

class ZipToFolderExporter(
    private val context: Context,
    private val exportWriter: ExportWriter,
) {
    data class Result(
        val displayName: String,
        val uri: android.net.Uri,
    )

    fun export(sessionDir: File, folder: DocumentFile, bankUrl: String?): Result {
        if (!folder.isDirectory) throw IllegalArgumentException("Selected folder is not a directory")
        if (!folder.canWrite()) throw IllegalStateException("No write access to selected folder")

        val host = extractHost(bankUrl) ?: "export"
        val base = sanitizeHostForFilename(host).ifBlank { "export" }

        var i = 1
        while (true) {
            val displayName = if (i == 1) "$base.zip" else "${base}_$i.zip"
            if (folder.findFile(displayName) != null) {
                i++
                continue
            }

            val outFile = folder.createFile("application/zip", displayName)
                ?: throw IllegalStateException("Failed to create $displayName in chosen folder")

            context.contentResolver.openOutputStream(outFile.uri, "w")?.use { out ->
                exportWriter.writeExportZipToStream(sessionDir, out)
            } ?: throw IllegalStateException("Failed to open output stream for ${outFile.uri}")

            return Result(displayName = displayName, uri = outFile.uri)
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
        return host
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(80)
    }
}

