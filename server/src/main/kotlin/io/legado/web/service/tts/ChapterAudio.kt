package io.legado.web.service.tts

import io.legado.web.auth.BadRequestException
import io.legado.web.service.TtsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 将不同引擎返回的片段解码为同一种 PCM，再写出只有一个文件头的 WAV。 */
object ChapterAudio {
    suspend fun create(parts: List<String>, synthesize: suspend (String) -> TtsService.SpeechAudio): TtsService.SpeechAudio {
        if (parts.isEmpty() || parts.size > 500 || parts.sumOf { it.length } > 100000 || parts.any { it.isBlank() || it.length > TtsService.MAX_TEXT }) {
            throw BadRequestException("章节朗读文字过长或为空")
        }
        val dir = Files.createTempDirectory("legado-tts-").toFile()
        try {
            val pcm = dir.resolve("chapter.pcm")
            for ((index, text) in parts.withIndex()) {
                val audio = synthesize(text)
                val input = dir.resolve("input-$index")
                input.writeBytes(audio.bytes)
                val output = dir.resolve("part.pcm")
                convert(listOf("-protocol_whitelist", "pipe", "-i", "pipe:0", "-vn", "-ac", "1", "-ar", "24000", "-f", "s16le", output.path), dir, input)
                if (pcm.length() + output.length() > 64L * 1024 * 1024) throw BadRequestException("章节音频过大")
                pcm.appendBytes(output.readBytes())
                input.delete()
                output.delete()
            }
            val wav = dir.resolve("chapter.wav")
            convert(listOf("-protocol_whitelist", "pipe", "-f", "s16le", "-ar", "24000", "-ac", "1", "-i", "pipe:0", wav.path), dir, pcm)
            return TtsService.SpeechAudio(wav.readBytes(), "audio/wav")
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun convert(args: List<String>, dir: java.io.File, input: java.io.File) = runInterruptible(Dispatchers.IO) {
        val log = dir.resolve("ffmpeg.log")
        val process = try {
            ProcessBuilder(listOf("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y") + args)
                .redirectInput(input).redirectErrorStream(true).redirectOutput(log).start()
        } catch (e: java.io.IOException) {
            throw BadRequestException("整章朗读需要服务器安装 FFmpeg")
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) throw BadRequestException("朗读音频无法解码")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }
}
