package com.example.bankscraperlogger.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class ExportFolderManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("bsl_prefs", Context.MODE_PRIVATE)

    fun getExportFolderUri(): Uri? {
        val s = prefs.getString(KEY_EXPORT_FOLDER_URI, null) ?: return null
        return try {
            Uri.parse(s)
        } catch (_: Throwable) {
            null
        }
    }

    fun setExportFolderUri(uri: Uri) {
        prefs.edit().putString(KEY_EXPORT_FOLDER_URI, uri.toString()).apply()
    }

    fun getExportFolder(): DocumentFile? {
        val uri = getExportFolderUri() ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    companion object {
        private const val KEY_EXPORT_FOLDER_URI = "export_folder_tree_uri"
    }
}

