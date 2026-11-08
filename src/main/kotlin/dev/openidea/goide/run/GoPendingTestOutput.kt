package dev.openidea.goide.run

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Keep output private until a Go test is known to be a leaf or a parent suite. */
internal class GoPendingTestOutput(private val memoryLimit: Int = 256 * 1024) : AutoCloseable {
    private val prefix = StringBuilder()
    private var file: Path? = null
    private var writer: BufferedWriter? = null
    private var failure: String? = null

    fun append(value: String) {
        if (failure != null) return
        if (writer == null && prefix.length + value.length <= memoryLimit) { prefix.append(value); return }
        try {
            if (writer == null) {
                file = Files.createTempFile("openidea-go-test-output-", ".log")
                writer = Files.newBufferedWriter(file!!, StandardCharsets.UTF_8)
                writer!!.write(prefix.toString())
            }
            writer!!.write(value)
        } catch (error: Exception) { failure = "Test output buffering failed: ${error.message}" }
    }

    fun drain(emit: (String) -> Unit) {
        try {
            val spool = file
            if (spool == null) {
                if (prefix.isNotEmpty()) emit(prefix.toString())
            } else {
                val flushError = runCatching { writer?.close(); writer = null }.exceptionOrNull()
                if (flushError != null) {
                    if (prefix.isNotEmpty()) emit(prefix.toString())
                    emit("\nTest output could not be read completely: ${flushError.message}\n")
                } else try {
                    Files.newBufferedReader(spool, StandardCharsets.UTF_8).use { reader ->
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            val text = StringBuilder().append(buffer, 0, count)
                            if (count > 0 && Character.isHighSurrogate(buffer[count - 1])) reader.read().takeIf { it >= 0 }?.let { text.append(it.toChar()) }
                            emit(text.toString())
                        }
                    }
                } catch (error: Exception) { emit("\nTest output could not be read completely: ${error.message}\n") }
            }
            failure?.let { emit("\n$it\n") }
        } finally { close() }
    }

    override fun close() {
        runCatching { writer?.close() }; writer = null
        file?.let { runCatching { Files.deleteIfExists(it) } }; file = null
        prefix.setLength(0)
    }
}
