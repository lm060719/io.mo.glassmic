package io.mo.glassmic.audio.tts

import io.mo.glassmic.proto.TtsAiConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MimoPcmStreamTest {
    @Test
    fun streamIsOffForExistingConfigAndPersistsWhenEnabled() {
        val old = TtsAiConfig.newBuilder().setFormat("wav").build()
        assertFalse(TtsAiConfig.parseFrom(old.toByteArray()).mimoStream)

        val updated = TtsAiConfig.parseFrom(old.toBuilder().setMimoStream(true).build().toByteArray())
        assertTrue(updated.mimoStream)
        assertEquals("wav", updated.format)
    }

    @Test
    fun emitsAudioBeforeReadingTheNextEvent() {
        val received = mutableListOf<ByteArray>()
        val segments = listOf(
            ": heartbeat\r\ndata: {\"choices\":[]}\r\n\r\n" + audioEvent("AQI=", "\r\n"),
            "data: {\"choices\":[\r\ndata: {\"delta\":{\"audio\":{\"data\":\"AwQ=\"}}}]}\r\n\r\n",
            "data: [DONE]\n\n"
        ).map { it.toByteArray() }.iterator()
        val input = object : InputStream() {
            private var reads = 0

            override fun read(): Int = error("Expected buffered reads")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertEquals(reads, received.size)
                val next = segments.next()
                assertTrue(next.size <= length)
                next.copyInto(buffer, offset)
                reads++
                return next.size
            }
        }

        readMimoPcmStream(input, 4_096) { received.add(it) }

        assertEquals(2, received.size)
        assertArrayEquals(byteArrayOf(1, 2), received[0])
        assertArrayEquals(byteArrayOf(3, 4), received[1])
    }

    @Test
    fun acceptsFinishReasonAtEofAndRejectsAnInterruptedStream() {
        val finish = "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n\n"
        val complete = audioEvent("AQI=") + finish
        val received = mutableListOf<ByteArray>()
        readMimoPcmStream(complete.byteInputStream(), 4_096) { received.add(it) }
        assertArrayEquals(byteArrayOf(1, 2), received.single())

        assertThrows(IllegalStateException::class.java) {
            readMimoPcmStream(audioEvent("AQI=").byteInputStream(), 4_096) {}
        }
    }

    @Test
    fun rejectsEmptyOversizedAndMisalignedResponses() {
        assertThrows(IllegalStateException::class.java) {
            readMimoPcmStream("data: [DONE]\n\n".byteInputStream(), 4_096) {}
        }
        assertThrows(IllegalStateException::class.java) {
            readMimoPcmStream(ByteArrayInputStream(ByteArray(128)), 64) {}
        }
        assertThrows(IllegalStateException::class.java) {
            readMimoPcmStream(audioEvent("AQ==").byteInputStream(), 4_096) {}
        }
    }

    private fun audioEvent(base64: String, newline: String = "\n"): String =
        "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"$base64\"}}}]}$newline$newline"
}
