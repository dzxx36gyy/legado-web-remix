package io.legado.web.service.tts

import com.google.gson.JsonParser
import io.legado.app.exception.NoStackTraceException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object ProviderVoiceDiscovery {
    data class Voice(val id: String, val name: String, val locale: String = "", val gender: String = "")
    data class Page(val voices: List<Voice>, val page: Int, val pageSize: Int, val total: Int? = null)

    internal fun azureRequest(config: ProviderTts.Config, text: String, rate: Int): Request {
        val c = ProviderTts.validate(config)
        val locale = c.voice.split('-').take(2).joinToString("-")
        val ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"${xml(locale)}\">" +
            "<voice name=\"${xml(c.voice)}\"><prosody rate=\"${rate}%\">${xml(text)}</prosody></voice></speak>"
        return Request.Builder().url("https://${c.region}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", c.apiKey)
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            .header("User-Agent", "Legado-Web")
            .post(ssml.toRequestBody("application/ssml+xml; charset=utf-8".toMediaType())).build()
    }

    private fun xml(value: String): String = buildString {
        value.forEach { ch ->
            append(when (ch) {
                '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"
                else -> {
                    if (ch.code < 32 && ch !in "\t\n\r") throw NoStackTraceException("文字包含无效的 XML 字符")
                    ch.toString()
                }
            })
        }
    }

    internal fun request(config: ProviderTts.Config, search: String, page: Int, pageSize: Int): Request {
        require(page >= 1 && pageSize in 1..100 && search.length <= 200) { "音色分页参数不正确" }
        val c = ProviderTts.validate(config)
        return when (ProviderTts.provider(c.provider)) {
            ProviderTts.Provider.fishaudio -> Request.Builder()
                .url("https://api.fish.audio/model".toHttpUrl().newBuilder()
                    .addQueryParameter("page_size", pageSize.toString())
                    .addQueryParameter("page_number", page.toString())
                    .addQueryParameter("title", search)
                    .addQueryParameter("self", "false").build())
                .header("Authorization", "Bearer ${c.apiKey}").get().build()
            ProviderTts.Provider.azure -> Request.Builder()
                .url("https://${c.region}.tts.speech.microsoft.com/cognitiveservices/voices/list")
                .header("Ocp-Apim-Subscription-Key", c.apiKey).get().build()
            else -> throw NoStackTraceException("此服务商使用内置音色列表")
        }
    }

    suspend fun voices(config: ProviderTts.Config, search: String, page: Int, pageSize: Int): Page {
        require(page >= 1 && pageSize in 1..100 && search.length <= 200) { "音色分页参数不正确" }
        val p = ProviderTts.provider(config.provider)
        if (p != ProviderTts.Provider.azure && p != ProviderTts.Provider.fishaudio) {
            val voices = p.voices.filter { it.first.contains(search, true) || it.second.contains(search, true) }
            return Page(voices.drop(((page - 1).toLong() * pageSize).coerceAtMost(voices.size.toLong()).toInt()).take(pageSize)
                .map { Voice(it.first, it.second) }, page, pageSize, voices.size)
        }
        val body = ProviderTts.discovery(request(config, search, page, pageSize))
        return parse(p, body, search, page, pageSize)
    }

    internal fun parse(provider: ProviderTts.Provider, body: String, search: String, page: Int, pageSize: Int): Page {
        val root = JsonParser.parseString(body)
        return when (provider) {
            ProviderTts.Provider.azure -> {
                val all = root.asJsonArray.map { el ->
                    val o = el.asJsonObject
                    Voice(o.get("ShortName").asString, o.get("DisplayName")?.asString ?: o.get("ShortName").asString,
                        o.get("Locale")?.asString.orEmpty(), o.get("Gender")?.asString.orEmpty())
                }.filter { it.id.contains(search, true) || it.name.contains(search, true) || it.locale.contains(search, true) }
                Page(all.drop(((page - 1).toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()).take(pageSize), page, pageSize, all.size)
            }
            ProviderTts.Provider.fishaudio -> Page(root.asJsonObject.getAsJsonArray("items").map { el ->
                val o = el.asJsonObject
                Voice(o.get("_id").asString, o.get("title").asString)
            }, page, pageSize, root.asJsonObject.get("total")?.asInt)
            else -> throw NoStackTraceException("不支持的音色列表")
        }
    }
}
