package dev.openidea.goide.http

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.io.IOException
import java.io.StringReader
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel

/** Prepared off the EDT. Original response bytes remain owned by the execution, never by the editor. */
internal data class HttpResponsePresentation(
    val headersText: String,
    val bodyText: String,
    val extension: String,
    val formatted: Boolean,
    val binary: Boolean,
    val wireBytes: Int,
    val bodyOmitted: Boolean = false,
) {
    companion object {
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_DISPLAY_CHARS = 2 * 1024 * 1024
        private const val MAX_HEADER_CHARS = 64 * 1024
        private const val MAX_JSON_DEPTH = 64
        private const val MAX_JSON_TOKENS = 100_000
        private val charsetParameter = Regex("(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))", RegexOption.IGNORE_CASE)

        fun prepare(response: HttpRequestResponse): HttpResponsePresentation {
            val contentType = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }
                ?.value?.firstOrNull().orEmpty()
            val media = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
            val json = media == "application/json" || media == "text/json" || media.endsWith("+json")
            val xml = media == "application/xml" || media == "text/xml" || media.endsWith("+xml")
            val textual = media.isEmpty() || media.startsWith("text/") || media.contains("json") || media.contains("xml") ||
                media in setOf("application/x-www-form-urlencoded", "application/javascript", "application/ecmascript", "application/x-ndjson")
            val extension = when {
                media.contains("json") -> "json"
                media == "text/html" -> "html"
                xml || media.contains("xml") -> "xml"
                media in setOf("application/javascript", "application/ecmascript", "text/javascript") -> "js"
                else -> "txt"
            }
            if (!textual) return result(response, "[Binary response: ${response.body.size} bytes.]", "txt", binary = true)
            if (response.body.size > MAX_RESPONSE_BYTES) {
                return result(response, "[Response not displayed: ${response.body.size} bytes exceeds the 1 MiB display limit.]",
                    extension, omitted = true)
            }
            val match = charsetParameter.find(contentType)
            val declared = match?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val requestedCharset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
            val charset = requestedCharset ?: UTF_8
            var warning = if (declared != null && requestedCharset == null)
                "Unsupported response charset '${declared.take(80)}'; displayed as UTF-8." else null
            var invalidEncoding = false
            val text = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(response.body)).toString()
            } catch (_: CharacterCodingException) {
                invalidEncoding = true
                warning = listOfNotNull(warning, "Invalid byte sequence for ${charset.name()}; replacement characters are shown.").joinToString(" ")
                String(response.body, charset)
            }
            if (media.isEmpty() && (invalidEncoding || text.any { it == '\u0000' || (it < ' ' && it !in "\t\r\n\u000c") })) {
                return result(response, "[Binary response: ${response.body.size} bytes; no text Content-Type was declared.]", "txt", binary = true)
            }
            if (text.length > MAX_DISPLAY_CHARS) {
                return result(response, "[Response not displayed: decoded text exceeds the 2 MiB display limit.]", extension,
                    omitted = true, warning = warning)
            }
            val pretty = if (json && !invalidEncoding) prettyJson(text) else null
            return result(response, pretty ?: text, extension, formatted = pretty != null && pretty != text, warning = warning)
        }

        private fun result(response: HttpRequestResponse, text: String, extension: String,
                           formatted: Boolean = false, binary: Boolean = false, omitted: Boolean = false,
                           warning: String? = null) = HttpResponsePresentation(
            headers(response, warning), text, extension, formatted, binary, response.body.size, omitted)

        private fun headers(response: HttpRequestResponse, warning: String?): String {
            val output = StringBuilder("HTTP ${response.status} — ${response.elapsedMillis} ms, ${response.body.size} bytes\n")
            if (warning != null) output.append("[$warning]\n")
            val omitted = "[Remaining headers omitted after the 64 KiB display limit.]\n"
            for ((name, values) in response.headers) for (value in values) {
                if (name.length.toLong() + value.length + 3 > MAX_HEADER_CHARS - omitted.length - output.length) {
                    output.append(omitted)
                    return output.toString()
                }
                output.append(name).append(": ").append(value).append('\n')
            }
            return output.toString()
        }

        /** Streaming parsing and writing have no recursive JSON tree; every expansion is budgeted. */
        private fun prettyJson(text: String): String? {
            return try {
            val output = BoundedWriter(MAX_DISPLAY_CHARS)
            JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                JsonWriter(output).use { writer ->
                    writer.strictness = Strictness.STRICT
                    writer.setSerializeNulls(true)
                    writer.setIndent("  ")
                    var depth = 0
                    var tokens = 0
                    var values = 0
                    while (true) {
                        if (++tokens > MAX_JSON_TOKENS) return null
                        when (reader.peek()) {
                            JsonToken.BEGIN_OBJECT -> {
                                if (++depth > MAX_JSON_DEPTH) return null
                                reader.beginObject(); writer.beginObject(); values++
                            }
                            JsonToken.END_OBJECT -> { reader.endObject(); writer.endObject(); depth-- }
                            JsonToken.BEGIN_ARRAY -> {
                                if (++depth > MAX_JSON_DEPTH) return null
                                reader.beginArray(); writer.beginArray(); values++
                            }
                            JsonToken.END_ARRAY -> { reader.endArray(); writer.endArray(); depth-- }
                            JsonToken.NAME -> writer.name(reader.nextName())
                            JsonToken.STRING -> { writer.value(reader.nextString()); values++ }
                            JsonToken.NUMBER -> { writer.jsonValue(reader.nextString()); values++ }
                            JsonToken.BOOLEAN -> { writer.value(reader.nextBoolean()); values++ }
                            JsonToken.NULL -> { reader.nextNull(); writer.nullValue(); values++ }
                            JsonToken.END_DOCUMENT -> return if (depth == 0 && values > 0) output.toString() else null
                            else -> return null
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") null
            } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }
        }

        private class BoundedWriter(private val limit: Int) : Writer() {
            private val output = StringBuilder()
            override fun write(buffer: CharArray, offset: Int, length: Int) {
                if (length > limit - output.length) throw IOException("Formatted response is too large.")
                output.append(buffer, offset, length)
            }
            override fun flush() = Unit
            override fun close() = Unit
            override fun toString() = output.toString()
        }
    }
}

/** Native text editors only. HTML/XML responses are never browser or rich-text input. */
internal class HttpResponseView(private val project: Project, parentDisposable: Disposable) : JPanel(BorderLayout()), Disposable {
    private val disposed = AtomicBoolean()
    private val editors = mutableListOf<Editor>()

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        Disposer.register(parentDisposable, this)
        showMessage("No response yet.")
    }

    fun show(presentation: HttpResponsePresentation) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed.get()) return
        clearEditors()
        try {
            val tabs = JBTabbedPane()
            tabs.addTab("Response", editor(presentation.bodyText, presentation.extension).component)
            tabs.addTab("Headers", editor(presentation.headersText, "txt").component)
            add(tabs, BorderLayout.CENTER)
            revalidate(); repaint()
        } catch (failure: Throwable) {
            clearEditors()
            throw failure
        }
    }

    fun showMessage(text: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed.get()) return
        clearEditors()
        val message = JBTextArea(text.take(64 * 1024)).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        add(JBScrollPane(message), BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun editor(text: String, extension: String): Editor {
        val factory = EditorFactory.getInstance()
        val editor = factory.createViewer(factory.createDocument(StringUtil.convertLineSeparators(text)), project, EditorKind.PREVIEW)
        editors += editor
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(extension)
        (editor as EditorEx).highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType)
        editor.settings.isLineNumbersShown = true
        editor.settings.isLineMarkerAreaShown = false
        editor.settings.isRightMarginShown = false
        editor.settings.isUseSoftWraps = true
        return editor
    }

    private fun clearEditors() {
        removeAll()
        val previous = editors.toList()
        editors.clear()
        previous.forEach { if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it) }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) clearEditors()
        else application.invokeLater({ clearEditors() }, ModalityState.any())
    }
}
