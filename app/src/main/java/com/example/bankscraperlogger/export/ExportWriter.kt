package com.example.bankscraperlogger.export

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportWriter {
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    fun writeExportJson(context: Context, sessionDir: File, outputUri: Uri) {
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

    fun writeExportZip(context: Context, sessionDir: File, outputUri: Uri) {
        val contentResolver = context.contentResolver
        contentResolver.openOutputStream(outputUri)?.use { out ->
            writeExportZipToStream(sessionDir, out)
        } ?: throw IllegalStateException("Failed to open output stream for: $outputUri")
    }

    fun writeExportZipToStream(sessionDir: File, outputStream: OutputStream) {
        ZipOutputStream(outputStream).use { zip ->
            zip.setLevel(6)

            putTextEntry(zip, "README.txt", buildReadmeText())
            putFileEntryIfExists(zip, File(sessionDir, "meta.json"), "meta.json")
            putFileEntryIfExists(zip, File(sessionDir, "events.jsonl"), "events.jsonl")
            putFileEntryIfExists(zip, File(sessionDir, "pages.jsonl"), "pages.jsonl")

            // Also include a single, easy-to-consume JSON.
            zip.putNextEntry(ZipEntry("export.json"))
            val nonClosing = NonClosingOutputStream(zip)
            OutputStreamWriter(nonClosing, Charsets.UTF_8).use { osw ->
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
                    writer.flush()
                }
            }
            zip.closeEntry()
        }
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

    private fun putTextEntry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFileEntryIfExists(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists()) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun buildReadmeText(): String {
        return """
            BankScraperLogger export (ZIP)
            
            - export.json: single consolidated JSON (meta + arrays of events/pages)
            - meta.json: session metadata (if present)
            - events.jsonl: newline-delimited event objects
            - pages.jsonl: newline-delimited page snapshots (url/title/cookies/html)
            
            Notes:
            - WebView logging is best-effort: not all response details are accessible via standard APIs.
        """.trimIndent()
    }
}

private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() {
        // Intentionally no-op: ZipOutputStream is managed outside.
    }
}

