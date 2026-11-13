package dev.openidea.goide.http

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class HttpResponseFilesTest {
    @Test fun `saving preserves arbitrary binary bytes and replaces the selected file`() {
        val directory = Files.createTempDirectory("http-response-files-")
        val target = directory.resolve("response.bin")
        try {
            Files.writeString(target, "previous response")
            val bytes = byteArrayOf(0, -1, -128, 13, 10, 65, 0)
            HttpResponseFiles.save(target, bytes)
            assertArrayEquals(bytes, Files.readAllBytes(target))
            Files.list(directory).use { assertEquals(1L, it.count()) }
        } finally { Files.deleteIfExists(target); Files.deleteIfExists(directory) }
    }

    @Test fun `failed replacement preserves the destination and removes the temporary file`() {
        val directory = Files.createTempDirectory("http-response-failure-")
        val target = Files.createDirectory(directory.resolve("existing-directory"))
        val original = target.resolve("retained.txt")
        try {
            Files.writeString(original, "retained")
            try { HttpResponseFiles.save(target, byteArrayOf(1, 2)); fail("A directory must not be replaced by response bytes") }
            catch (_: IOException) { }
            assertEquals("retained", Files.readString(original))
            Files.list(directory).use { assertEquals(listOf(target), it.toList()) }
        } finally { Files.deleteIfExists(original); Files.deleteIfExists(target); Files.deleteIfExists(directory) }
    }
}
