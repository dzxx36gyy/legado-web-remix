package io.legado.web.service.tts

import io.legado.web.auth.BadRequestException

/** Only MPEG Layer III frames enter the continuous resource; container headers never get concatenated. */
class Mp3Stream {
    data class Format(val version: Int, val sampleRate: Int, val channels: Int)
    data class Frame(val bytes: ByteArray, val samples: Int, val format: Format) {
        val seconds get() = samples.toDouble() / format.sampleRate
    }

    var format: Format? = null
        private set
    private var pending = ByteArray(0)
    private var beginning = true
    private var frames = 0

    suspend fun feed(bytes: ByteArray, emit: suspend (Frame) -> Unit) {
        pending += bytes
        var offset = 0
        if (beginning) {
            if (pending.size < 10) return
            if (String(pending, 0, 3, Charsets.US_ASCII) == "ID3") {
                val sizeBytes = pending.sliceArray(6..9).map { it.toInt() and 255 }
                if (sizeBytes.any { it > 127 }) invalid()
                val size = sizeBytes.fold(0) { n, b -> (n shl 7) or b } + 10 +
                    if (pending[5].toInt() and 16 != 0) 10 else 0
                if (size > 256 * 1024) invalid()
                if (pending.size < size) return
                offset = size
            }
            beginning = false
        }
        while (pending.size - offset >= 4) {
            // ID3v1 is allowed only at the end of a provider response.
            if (pending[offset] == 84.toByte() && pending[offset + 1] == 65.toByte() && pending[offset + 2] == 71.toByte()) break
            val a = pending[offset].toInt() and 255
            val b = pending[offset + 1].toInt() and 255
            val c = pending[offset + 2].toInt() and 255
            val d = pending[offset + 3].toInt() and 255
            val version = (b shr 3) and 3
            val bitrateIndex = c shr 4
            val sampleIndex = (c shr 2) and 3
            if (a != 255 || b and 224 != 224 || version == 1 || b and 6 != 2 || bitrateIndex !in 1..14 || sampleIndex == 3 || d and 3 == 2) invalid()
            val sampleRate = intArrayOf(44100, 48000, 32000)[sampleIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
            val bitrate = if (version == 3) intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIndex]
                else intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[bitrateIndex]
            val size = (if (version == 3) 144000 else 72000) * bitrate / sampleRate + ((c shr 1) and 1)
            if (pending.size - offset < size) break
            val current = Format(version, sampleRate, if (d shr 6 == 3) 1 else 2)
            if (format != null && current != format) throw BadRequestException("连续朗读的 MP3 格式发生变化，请关闭连续播放后重试")
            format = current
            val frame = pending.copyOfRange(offset, offset + size)
            // Xing/Info/VBRI describe an individual file, not the duration of this ongoing resource.
            val sideInfo = if (version == 3) { if (current.channels == 1) 17 else 32 } else { if (current.channels == 1) 9 else 17 }
            val tagOffset = 4 + (if (b and 1 == 0) 2 else 0) + sideInfo
            val tag = if (tagOffset + 4 <= frame.size) String(frame, tagOffset, 4, Charsets.US_ASCII) else ""
            val vbri = if (frame.size >= 40) String(frame, 36, 4, Charsets.US_ASCII) else ""
            if (tag !in listOf("Xing", "Info") && vbri != "VBRI") {
                emit(Frame(frame, if (version == 3) 1152 else 576, current))
                frames++
            }
            offset += size
        }
        pending = pending.copyOfRange(offset, pending.size)
        if (pending.size > 256 * 1024) invalid()
    }

    fun endPart() {
        val tailTag = pending.size == 128 && String(pending, 0, 3, Charsets.US_ASCII) == "TAG"
        if (frames == 0 || (pending.isNotEmpty() && !tailTag)) invalid()
        pending = ByteArray(0)
        beginning = true
        frames = 0
    }

    private fun invalid(): Nothing = throw BadRequestException("连续播放仅支持完整、兼容的 MP3 音频，请关闭连续播放使用逐段朗读")
}
