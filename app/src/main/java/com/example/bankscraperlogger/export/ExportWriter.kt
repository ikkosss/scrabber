package com.example.bankscraperlogger.export

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.OutputStreamWriter

class ExportWriter {
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    fun writeExport(context: Context, sessionDir: File, outputUri: Uri) {
        val contentResolver = context.contentResolver
        contentResolver.openOutputStream(outputUri)?.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { osw ->
                JsonWriter(osw).use { writer ->
                    writer.setIndent("  ")
                    writer.beginObject()

                    writer.name("exportedAtMs").value(System.currentTimeMillis())

                    writer.name("meta")
                    writeJsonFileOrNull(writer, File(sessionDir, "meta.json"))

                    writer.name("events")
                    writer.beginArray()
                    streamJsonlArray(writer, File(sessionDir, "events.jsonl"))
                    writer.endArray()

                    writer.name("pages")
                    writer.beginArray()
                    streamJsonlArray(writer, File(sessionDir, "pages.jsonl"))
                    writer.endArray()

                    writer.endObject()
                }
            }
        } ?: throw IllegalStateException("Failed to open output stream for: $outputUri")
    }

    private fun writeJsonFileOrNull(writer: JsonWriter, file: File) {
        if (!file.exists()) {
            writer.nullValue()
            return
        }
        val element = gson.fromJson(file.readText(Charsets.UTF_8), JsonElement::class.java)
        gson.toJson(element, writer)
    }

    private fun streamJsonlArray(writer: JsonWriter, file: File) {
        if (!file.exists()) return
        file.bufferedReader(Charsets.UTF_8).use { br ->
            br.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine
                val element = gson.fromJson(trimmed, JsonElement::class.java)
                gson.toJson(element, writer)
            }
        }
    }
}

