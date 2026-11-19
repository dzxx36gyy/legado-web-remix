package io.legado.web

import io.legado.web.auth.BadRequestException
import io.legado.web.service.tts.Mp3Stream
import io.legado.web.service.tts.SpeechText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Mp3StreamTest {
    private fun frame(rate: Int = 0): ByteArray = ByteArray(if (rate == 0) 144 else 156).apply {
        this[0] = 255.toByte(); this[1] = 243.toByte(); this[2] = (if (rate == 0) 0x64 else 0x60).toByte(); this[3] = 192.toByte()
    }

    @Test
    fun emitsCompleteFramesBeforeInputEndsAndPreservesExactSampleDuration() = runBlocking {
        val parser = Mp3Stream()
        val frames = mutableListOf<Mp3Stream.Frame>()
        val first = frame()
        parser.feed(first.copyOfRange(0, 5)) { frames.add(it) }
        assertTrue(frames.isEmpty())
        parser.feed(first.copyOfRange(5, first.size)) { frames.add(it) }
        assertEquals(1, frames.size)
        repeat(999) { parser.feed(first) { frames.add(it) } }
        parser.endPart()
        assertEquals(24.0, frames.sumOf { it.seconds }, 0.0000001)
        assertTrue(frames.all { it.bytes.contentEquals(first) })
    }

    @Test
    fun stripsFileMetadataAcrossPartsAndRejectsFormatChangesAndTruncation() = runBlocking {
        val parser = Mp3Stream()
        val frames = mutableListOf<Mp3Stream.Frame>()
        val id3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0)
        val info = frame().apply { "Info".toByteArray().copyInto(this, 13) }
        repeat(2) {
            parser.feed(id3 + info + frame()) { frames.add(it) }
            parser.endPart()
        }
        assertEquals(2, frames.size)
        assertFailsWith<BadRequestException> { parser.feed(frame(8)) {} }
        val truncated = Mp3Stream()
        truncated.feed(frame().copyOf(100)) {}
        assertFailsWith<BadRequestException> { truncated.endPart() }
        assertFailsWith<BadRequestException> { Mp3Stream().feed("RIFFxxxxxxxxxxxx".toByteArray()) {} }
        Unit
    }

    @Test
    fun textSplittingRetainsParagraphPositionsAndNaturalSentenceBoundaries() {
        val parts = SpeechText.chapter("标题", "第一段。\n<img src=\"https://example.org/image\">\n" + "长句。".repeat(100))
        assertEquals(listOf(-1, 0, 2), parts.map { it.para }.distinct())
        assertEquals("长句。".repeat(100), parts.filter { it.para == 2 }.joinToString("") { it.text })
        assertTrue(parts.all { it.text.length <= 160 })
        assertEquals(1.0, parts.last().endFraction)
    }
}
