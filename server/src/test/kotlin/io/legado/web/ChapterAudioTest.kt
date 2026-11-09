package io.legado.web

import io.legado.web.service.TtsService
import io.legado.web.service.tts.ChapterAudio
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import io.legado.web.auth.BadRequestException

class ChapterAudioTest {
    private fun wav(sample: Short): ByteArray {
        val data = ByteBuffer.allocate(44 + 4800).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + 4800).put("WAVEfmt ".toByteArray()).putInt(16)
        data.putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(4800)
        repeat(2400) { data.putShort(sample) }
        return data.array()
    }

    @Test
    fun chapterRetainsAllDecodedPartsInOrder() = runBlocking {
        val result = ChapterAudio.create(listOf("first", "second")) {
            TtsService.SpeechAudio(wav(if (it == "first") 1000 else -1000), "audio/wav")
        }
        assertEquals("audio/wav", result.contentType)
        assertEquals("RIFF", String(result.bytes, 0, 4))
        val marker = "data".toByteArray()
        val offset = (12 until result.bytes.size - 8).first { result.bytes.copyOfRange(it, it + 4).contentEquals(marker) }
        val pcm = ByteBuffer.wrap(result.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(9600, pcm.getInt(offset + 4))
        assertEquals(1000, pcm.getShort(offset + 8).toInt())
        assertEquals(-1000, pcm.getShort(offset + 8 + 4800).toInt())
    }

    @Test
    fun rejectsOversizedChapterBeforeCallingProvider() = runBlocking {
        var called = false
        assertFailsWith<BadRequestException> {
            ChapterAudio.create(listOf("a".repeat(1001))) { called = true; TtsService.SpeechAudio(byteArrayOf(), "audio/wav") }
        }
        assertTrue(!called)
    }
}
