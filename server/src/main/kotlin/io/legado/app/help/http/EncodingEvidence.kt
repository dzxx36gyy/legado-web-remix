package io.legado.app.help.http

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

data class EncodingEvidence(
    val httpCharset: String?, val specifiedCharset: String?, val bom: String?, val metaCharset: String?,
    val selectedCharset: String, val reason: String, val replacementCharacters: Int,
    val malformedBytes: Boolean, val declarationConflict: Boolean,
) {
    companion object {
        fun inspect(bytes: ByteArray, text: String, http: String?, specified: String?, selected: String): EncodingEvidence {
            val bom = when {
                bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) -> "UTF-8"
                bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte()) -> "UTF-16LE"
                bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte()) -> "UTF-16BE"
                else -> null
            }
            val head = String(bytes.take(8192).toByteArray(), Charsets.ISO_8859_1)
            val meta = Regex("(?is)<meta\\b[^>]*charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_-]+)").find(head)?.groupValues?.get(1)
            val charset = Charset.forName(selected)
            val malformed = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                false
            } catch (e: java.nio.charset.CharacterCodingException) { true }
            val conflict = listOfNotNull(http, specified, bom, meta).any {
                try { Charset.forName(it) != charset } catch (e: IllegalArgumentException) { true }
            }
            return EncodingEvidence(http, specified, bom, meta, charset.name(),
                if (specified != null) "specified" else if (http != null) "http" else "existing-content-detection",
                text.count { it == '\uFFFD' }, malformed, conflict)
        }
    }
}
