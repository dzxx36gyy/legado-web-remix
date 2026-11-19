package io.legado.web.service.tts

import io.legado.web.auth.BadRequestException
import org.jsoup.Jsoup

data class SpeechPart(val para: Int, val chunk: Int, val text: String, val fraction: Double = 0.0, val endFraction: Double = 1.0)

object SpeechText {
    fun validate(parts: List<SpeechPart>) {
        if (parts.isEmpty() || parts.size > 2000 || parts.sumOf { it.text.length } > 100000 || parts.any {
            it.text.isBlank() || it.text.length > 1000 || it.para < -1 || it.chunk < 0 ||
                !it.fraction.isFinite() || !it.endFraction.isFinite() || it.fraction !in 0.0..1.0 || it.endFraction !in it.fraction..1.0
        }) throw BadRequestException("章节朗读文字过长或为空")
    }

    fun chapter(title: String, content: String): List<SpeechPart> {
        val paragraphs = mutableListOf<String>()
        content.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val image = Regex("<img[^>]*src=\"([^\"]*(?:\"[^>]+\\})?)\"[^>]*>", RegexOption.IGNORE_CASE)
            var start = 0
            var inline = ""
            image.findAll(line).forEach { match ->
                inline += Jsoup.parse(line.substring(start, match.range.first)).text()
                if (!Regex("[\"']?style[\"']?\\s*:\\s*[\"']text[\"']", RegexOption.IGNORE_CASE).containsMatchIn(match.value)) {
                    if (inline.isNotBlank()) paragraphs.add(inline.trim())
                    paragraphs.add("")
                    inline = ""
                }
                start = match.range.last + 1
            }
            inline += Jsoup.parse(line.substring(start)).text()
            if (inline.isNotBlank()) paragraphs.add(inline.trim())
        }
        return (listOf(-1 to title) + paragraphs.mapIndexed { i, p -> i to p }).flatMap { (para, text) ->
            val chunks = split(text)
            val total = chunks.sumOf(String::length).coerceAtLeast(1)
            var chars = 0
            chunks.mapIndexed { chunk, value ->
                SpeechPart(para, chunk, value, chars.toDouble() / total, (chars + value.length).toDouble() / total).also { chars += value.length }
            }
        }
    }

    private fun split(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= 160) return listOf(clean).filter(String::isNotBlank)
        val pieces = Regex("[^。！？!?；;…]+[。！？!?；;…]*[”’\"'）)」』】]*").findAll(clean).map { it.value }.toList()
        val result = mutableListOf<String>()
        var buffer = ""
        for (piece in pieces) {
            if (buffer.length + piece.length > 160 && buffer.isNotEmpty()) { result.add(buffer); buffer = "" }
            val subs = if (piece.length > 160) Regex(".{1,160}(?:[，,、：:]|$)|.{1,160}").findAll(piece).map { it.value }.toList() else listOf(piece)
            for (sub in subs) {
                if (buffer.length + sub.length > 160 && buffer.isNotEmpty()) { result.add(buffer); buffer = "" }
                buffer += sub
            }
        }
        if (buffer.isNotBlank()) result.add(buffer)
        return result.map(String::trim).filter { Regex("[\\p{L}\\p{N}]").containsMatchIn(it) }
    }
}
