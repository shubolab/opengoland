package dev.openidea.goide.engine

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EngineFramingTest {
    private fun input(value: ByteArray) = BufferedInputStream(ByteArrayInputStream(value))
    @Test fun readsUtf8ByteLengthsAndConsecutiveFrames() {
        val first = "{\"text\":\"中文😀\"}".toByteArray()
        val second = "{}".toByteArray()
        val stream = input("Content-Length: ${first.size}\r\nContent-Type: application/json\r\n\r\n".toByteArray() + first + "Content-Length: 2\r\n\r\n".toByteArray() + second)
        assertContentEquals(first, EngineFraming.read(stream))
        assertContentEquals(second, EngineFraming.read(stream))
        assertNull(EngineFraming.read(stream))
    }
    @Test fun rejectsAmbiguousOrTruncatedFrames() {
        listOf("Content-Length: 1\r\nContent-Length: 1\r\n\r\nx", "Content-Length: -1\r\n\r\n", "Content-Length: 5\r\n\r\n{}", "Invalid header\r\n\r\n").forEach {
            assertFailsWith<Exception> { EngineFraming.read(input(it.toByteArray())) }
        }
    }
}
