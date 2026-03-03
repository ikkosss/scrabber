package com.example.bankscraperlogger.export

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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

    fun writeExportZipToStream(sessionDir: File, outputStream: OutputStream, allowedHosts: Set<String>? = null) {
        ZipOutputStream(outputStream).use { zip ->
            zip.setLevel(6)

            putTextEntry(zip, "README.txt", buildReadmeText())
            putFileEntryIfExists(zip, File(sessionDir, "meta.json"), "meta.json")
            if (allowedHosts.isNullOrEmpty()) {
                putFileEntryIfExists(zip, File(sessionDir, "events.jsonl"), "events.jsonl")
                putFileEntryIfExists(zip, File(sessionDir, "pages.jsonl"), "pages.jsonl")
            } else {
                putFilteredJsonlEntry(zip, File(sessionDir, "events.jsonl"), "events.jsonl", allowedHosts, kind = "events")
                putFilteredJsonlEntry(zip, File(sessionDir, "pages.jsonl"), "pages.jsonl", allowedHosts, kind = "pages")
            }

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
                    streamJsonlArray(writer, File(sessionDir, "events.jsonl"), allowedHosts = allowedHosts, kind = "events")
                    writer.endArray()

                    writer.name("pages")
                    writer.beginArray()
                    streamJsonlArray(writer, File(sessionDir, "pages.jsonl"), allowedHosts = allowedHosts, kind = "pages")
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

    private fun streamJsonlArray(writer: JsonWriter, file: File, allowedHosts: Set<String>?, kind: String) {
        if (allowedHosts.isNullOrEmpty()) {
            streamJsonlArray(writer, file)
            return
        }
        if (!file.exists()) return
        file.bufferedReader(Charsets.UTF_8).use { br ->
            br.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine
                val element = gson.fromJson(trimmed, JsonElement::class.java)
                if (shouldInclude(element, allowedHosts, kind)) {
                    gson.toJson(element, writer)
                }
            }
        }
    }

    private fun putFilteredJsonlEntry(
        zip: ZipOutputStream,
        file: File,
        entryName: String,
        allowedHosts: Set<String>,
        kind: String,
    ) {
        if (!file.exists()) return
        zip.putNextEntry(ZipEntry(entryName))
        val nonClosing = NonClosingOutputStream(zip)
        OutputStreamWriter(nonClosing, Charsets.UTF_8).use { osw ->
            file.bufferedReader(Charsets.UTF_8).use { br ->
                br.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine
                    val element = gson.fromJson(trimmed, JsonElement::class.java)
                    if (shouldInclude(element, allowedHosts, kind)) {
                        osw.write(gson.toJson(element))
                        osw.write("\n")
                    }
                }
                osw.flush()
            }
        }
        zip.closeEntry()
    }

    private fun shouldInclude(element: JsonElement, allowedHosts: Set<String>, kind: String): Boolean {
        return when (kind) {
            "pages" -> hostFromJson(element.asJsonObjectOrNull(), "url")?.let { it in allowedHosts } ?: false
            "events" -> {
                val obj = element.asJsonObjectOrNull() ?: return true
                val type = obj["type"]?.asString
                if (type == "session_start" || type == "session_stop" || type == "session_pause" || type == "session_resume" || type == "external_ip") {
                    return true
                }
                val data = obj["data"]?.asJsonObjectOrNull()
                val urlHost = hostFromJson(data, "url")
                val mainHost = hostFromJson(data, "mainPageUrl")
                (urlHost != null && urlHost in allowedHosts) || (mainHost != null && mainHost in allowedHosts)
            }
            else -> true
        }
    }

    private fun hostFromJson(obj: JsonObject?, key: String): String? {
        val raw = obj?.get(key)?.takeIf { !it.isJsonNull }?.asString ?: return null
        return try {
            android.net.Uri.parse(raw).host
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        try {
            if (isJsonObject) asJsonObject else null
        } catch (_: Throwable) {
            null
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

