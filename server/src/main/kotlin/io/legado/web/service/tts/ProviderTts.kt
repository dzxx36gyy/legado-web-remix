package io.legado.web.service.tts

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 云服务商的语音合成接口（移植自 github.com/Hamster-Prime/legado-tts-server），服务器直接调用，不需要额外部署服务。
 * 地址固定为各服务商的官方接口，所以不经过书源用的网络防护。
 */
object ProviderTts {

    /** 引擎类型；[voices] 为常用音色（也可以填写其他音色 ID） */
    enum class Provider(val label: String, val defaultVoice: String, val voices: List<Pair<String, String>>) {
        doubao(
            "火山引擎（豆包）", "zh_female_cancan_uranus_bigtts",
            listOf(
                "zh_female_cancan_uranus_bigtts" to "知性灿灿 2.0",
                "zh_female_shuangkuaisisi_uranus_bigtts" to "爽快思思 2.0",
                "zh_female_tiexinnvsheng_uranus_bigtts" to "贴心女声 2.0",
                "zh_female_jitangmei_uranus_bigtts" to "鸡汤妹妹 2.0",
                "zh_female_mengyatou_uranus_bigtts" to "萌丫头 2.0",
                "zh_male_shaonianzixin_uranus_bigtts" to "少年梓辛 2.0",
                "zh_male_wennuanahu_uranus_bigtts" to "温暖阿虎 2.0",
                "zh_male_cixingjieshuonan_uranus_bigtts" to "磁性解说男声 2.0",
                "zh_male_xuanyijieshuo_uranus_bigtts" to "悬疑解说 2.0",
                "zh_male_baqiqingshu_uranus_bigtts" to "霸气青叔 2.0",
                "zh_female_shaoergushi_uranus_bigtts" to "少儿故事 2.0",
            ),
        ),
        tencent(
            "腾讯云", "501002",
            listOf(
                "501002" to "智菊 - 阅读女声",
                "501000" to "智斌 - 阅读男声",
                "501001" to "智兰 - 资讯女声",
                "501003" to "智宇 - 阅读男声",
                "601009" to "爱小芊 - 多情感女声",
                "601008" to "爱小豪 - 多情感男声",
                "601010" to "爱小娇 - 多情感女声",
            ),
        ),
        xiaomi(
            "小米 MiMo", "mimo_default",
            listOf("mimo_default" to "MiMo 默认语音", "default_zh" to "中文女声", "default_en" to "英文女声"),
        ),
        fishaudio(
            "Fish Audio", "",
            listOf("" to "默认音色"),
        ),
    }

    /**
     * 保存在朗读引擎里的配置。[apiKey]：火山引擎、小米、Fish Audio 的 API Key；腾讯云为 SecretKey，[secretId] 为 SecretId。
     * Fish Audio 的 [voice] 为声音模型的 Reference ID，留空使用默认音色。
     */
    data class Config(
        val provider: String = "",
        val voice: String = "",
        val apiKey: String = "",
        val secretId: String = "",
        val region: String = "",
    )

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val MAX_AUDIO = 10 * 1024 * 1024
    private const val MAX_WIRE = MAX_AUDIO * 2
    private const val MAX_RECORD = MAX_WIRE.toLong()

    private suspend fun request(req: Request, read: (Response) -> ByteArray): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = EdgeTts.client.newCall(req)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val audio = response.use { res ->
                        val limit = if (res.isSuccessful) MAX_WIRE else 1200
                        val bytes = res.body.byteStream().readNBytes(limit + 1)
                        if (res.isSuccessful && bytes.size > limit) throw NoStackTraceException("朗读服务返回的响应过大")
                        res.newBuilder().body(bytes.copyOf(minOf(bytes.size, limit)).toResponseBody(res.body.contentType())).build().use(read)
                    }
                    if (audio.size > MAX_AUDIO) throw NoStackTraceException("朗读服务返回的音频过大")
                    continuation.resume(audio)
                } catch (e: Exception) { continuation.resumeWithException(e) }
            }
        })
    }

    private fun decode(data: String): ByteArray {
        if (data.length.toLong() > (MAX_AUDIO.toLong() + 2) / 3 * 4) throw NoStackTraceException("朗读服务返回的音频过大")
        return Base64.getDecoder().decode(data).also {
            if (it.size > MAX_AUDIO) throw NoStackTraceException("朗读服务返回的音频过大")
        }
    }
    private val voiceIdRe = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val regionRe = Regex("^[a-z]{2}-[a-z]+(-[a-z0-9]+)?$")

    fun provider(name: String): Provider =
        Provider.entries.firstOrNull { it.name == name } ?: throw NoStackTraceException("不支持的朗读服务：$name")

    /** 保存前检查，返回整理后的配置 */
    fun validate(c: Config): Config {
        val p = provider(c.provider)
        val voice = c.voice.trim().ifEmpty { p.defaultVoice }
        if (voice.isNotEmpty() && !voiceIdRe.matches(voice)) throw NoStackTraceException("音色 ID 格式不正确")
        if (p == Provider.tencent && !voice.all(Char::isDigit)) throw NoStackTraceException("腾讯云的音色 ID（VoiceType）应为数字")
        val region = c.region.trim().ifEmpty { "ap-guangzhou" }
        if (p == Provider.tencent && !regionRe.matches(region)) throw NoStackTraceException("地域格式不正确，例如 ap-guangzhou")
        return c.copy(
            voice = voice,
            apiKey = c.apiKey.trim(),
            secretId = if (p == Provider.tencent) c.secretId.trim() else "",
            region = if (p == Provider.tencent) region else "",
        )
    }

    /** 合成 MP3。[speed] 与阅读相同：10 为正常语速 */
    suspend fun synthesize(c: Config, text: String, speed: Int, p: Provider = provider(c.provider)): ByteArray {
        if (c.apiKey.isBlank() || (p == Provider.tencent && c.secretId.isBlank())) {
            throw NoStackTraceException("${p.label}：没有填写 API Key")
        }
        // 与 legado-tts-server 相同的换算：正常为 0，每 1 倍 = 100%
        val pct = (speed.coerceIn(1, 50) - 10) * 10
        return withContext(Dispatchers.IO) {
            when (p) {
                Provider.doubao -> doubao(c, text, pct.coerceIn(-50, 100))
                Provider.tencent -> {
                    // 腾讯云单次最多 150 个汉字
                    val out = ByteArrayOutputStream()
                    chunks(text, 150).forEach {
                        val bytes = tencent(c, it, (pct / 50.0).coerceIn(-2.0, 6.0))
                        if (out.size() + bytes.size > MAX_AUDIO) throw NoStackTraceException("腾讯云返回的音频过大")
                        out.write(bytes)
                    }
                    out.toByteArray()
                }
                Provider.xiaomi -> xiaomi(c, text, (1.0 + pct / 100.0).coerceIn(0.2, 3.0))
                Provider.fishaudio -> fishAudio(c, text, (1.0 + pct / 100.0).coerceIn(0.5, 2.0))
            }.also { if (it.isEmpty()) throw NoStackTraceException("${p.label}没有返回音频") }
        }
    }

    // ───────────── 火山引擎 v3 单向流式 HTTP 接口 ─────────────

    private suspend fun doubao(c: Config, text: String, speechRate: Int): ByteArray {
        val body = JsonObject().apply {
            add("req_params", JsonObject().apply {
                addProperty("text", text)
                addProperty("speaker", c.voice)
                add("audio_params", JsonObject().apply {
                    addProperty("format", "mp3")
                    addProperty("sample_rate", 24000)
                    addProperty("speech_rate", speechRate)
                })
            })
        }
        val req = Request.Builder().url("https://openspeech.bytedance.com/api/v3/tts/unidirectional")
            .header("X-Api-Key", c.apiKey)
            .header("X-Api-Resource-Id", "seed-tts-2.0")
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody(JSON))
            .build()
        return request(req) { res ->
            if (!res.isSuccessful) throw NoStackTraceException("火山引擎返回错误（${res.code}）：${res.body.byteStream().readNBytes(1200).toString(Charsets.UTF_8).take(300)}")
            val out = ByteArrayOutputStream()
            var completed = false
            val reader = res.body.source()
            while (true) {
                if (reader.exhausted()) break
                val newline = reader.indexOf('\n'.code.toByte(), 0, MAX_RECORD + 1)
                if (newline == -1L && reader.buffer.size > MAX_RECORD) throw NoStackTraceException("朗读服务返回的记录过大")
                val line = reader.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                    ?: throw NoStackTraceException("火山引擎返回的内容无法识别")
                val code = event.get("code")?.asInt ?: 0
                if (code == 20000000) {
                    completed = true
                    break
                }
                if (code != 0) throw NoStackTraceException("火山引擎错误 $code：${event.get("message")?.asString.orEmpty()}")
                event.get("data")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }?.let {
                    out.write(decode(it))
                    if (out.size() > MAX_AUDIO) throw NoStackTraceException("火山引擎返回的音频过大")
                }
            }
            if (!completed) throw NoStackTraceException("火山引擎的响应没有正常结束")
            out.toByteArray()
        }
    }

    // ───────────── 腾讯云 TextToVoice（TC3-HMAC-SHA256 签名） ─────────────

    private suspend fun tencent(c: Config, text: String, speed: Double): ByteArray {
        val host = "tts.tencentcloudapi.com"
        val action = "TextToVoice"
        val payload = JsonObject().apply {
            addProperty("Text", text)
            addProperty("SessionId", UUID.randomUUID().toString())
            addProperty("VoiceType", c.voice.toLong())
            addProperty("Codec", "mp3")
            addProperty("SampleRate", 16000)
            addProperty("Speed", speed)
        }.toString()
        val ts = System.currentTimeMillis() / 1000
        val auth = tencentAuthorization(c.secretId, c.apiKey, host, action, payload, ts)
        val req = Request.Builder().url("https://$host")
            .header("Authorization", auth)
            .header("X-TC-Action", action)
            .header("X-TC-Timestamp", ts.toString())
            .header("X-TC-Version", "2019-08-23")
            .header("X-TC-Region", c.region)
            .post(payload.toRequestBody(JSON))
            .build()
        return request(req) { res ->
            val body = res.body.string()
            val r = runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonObject("Response") }.getOrNull()
                ?: throw NoStackTraceException("腾讯云返回错误（${res.code}）：${body.take(300)}")
            r.get("Audio")?.asString?.let { return@request decode(it) }
            val err = r.getAsJsonObject("Error")
            throw NoStackTraceException("腾讯云错误：${err?.get("Message")?.asString ?: body.take(300)}")
        }
    }

    /** 腾讯云 API 3.0 签名；请求头中的 Content-Type 必须与签名时相同 */
    internal fun tencentAuthorization(secretId: String, secretKey: String, host: String, action: String, payload: String, ts: Long): String {
        val algo = "TC3-HMAC-SHA256"
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(ts))
        val signedHeaders = "content-type;host;x-tc-action"
        val canonical = "POST\n/\n\ncontent-type:${JSON}\nhost:$host\nx-tc-action:${action.lowercase()}\n\n$signedHeaders\n${sha256Hex(payload)}"
        val scope = "$date/tts/tc3_request"
        val toSign = "$algo\n$ts\n$scope\n${sha256Hex(canonical)}"
        val kDate = hmac("TC3$secretKey".toByteArray(), date)
        val kService = hmac(kDate, "tts")
        val kSigning = hmac(kService, "tc3_request")
        val signature = hmac(kSigning, toSign).joinToString("") { "%02x".format(it) }
        return "$algo Credential=$secretId/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    // ───────────── 小米 MiMo（OpenAI 兼容的 chat/completions，返回 base64 音频） ─────────────

    private suspend fun xiaomi(c: Config, text: String, ratio: Double): ByteArray {
        val style = when {
            kotlin.math.abs(ratio - 1.0) < 0.05 -> "<style>语速适中 吐字清晰 节奏稳定</style>"
            ratio >= 1.8 -> "<style>语速很快 节奏紧凑</style>"
            ratio >= 1.4 -> "<style>语速偏快 表达流畅</style>"
            ratio > 1.0 -> "<style>语速稍快 自然流畅</style>"
            ratio <= 0.5 -> "<style>语速很慢 咬字清晰</style>"
            ratio <= 0.8 -> "<style>语速偏慢 语气舒缓</style>"
            else -> "<style>语速稍慢 清晰稳重</style>"
        }
        val body = mapOf(
            "model" to "mimo-v2-tts",
            "messages" to listOf(
                mapOf("role" to "user", "content" to style + text),
                mapOf("role" to "assistant", "content" to style + text),
            ),
            "audio" to mapOf("format" to "mp3", "voice" to c.voice),
        )
        val req = Request.Builder().url("https://api.xiaomimimo.com/v1/chat/completions")
            .header("Authorization", "Bearer ${c.apiKey}")
            .post(GSON.toJson(body).toRequestBody(JSON))
            .build()
        return request(req) { res ->
            val text2 = res.body.string()
            if (!res.isSuccessful) throw NoStackTraceException("小米 MiMo 返回错误（${res.code}）：${text2.take(300)}")
            val data = runCatching {
                JsonParser.parseString(text2).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("message").getAsJsonObject("audio").get("data").asString
            }.getOrNull() ?: throw NoStackTraceException("小米 MiMo 返回错误：${text2.take(300)}")
            decode(data)
        }
    }

    // ───────────── Fish Audio ─────────────

    private suspend fun fishAudio(c: Config, text: String, speed: Double): ByteArray {
        val body = JsonObject().apply {
            addProperty("text", text)
            addProperty("format", "mp3")
            // 不带 reference_id 时使用默认音色
            if (c.voice.isNotEmpty()) addProperty("reference_id", c.voice)
            add("prosody", JsonObject().apply { addProperty("speed", speed) })
        }
        val req = Request.Builder().url("https://api.fish.audio/v1/tts")
            .header("Authorization", "Bearer ${c.apiKey}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return request(req) { res ->
            if (!res.isSuccessful) throw NoStackTraceException("Fish Audio 返回错误（${res.code}）：${res.body.byteStream().readNBytes(1200).toString(Charsets.UTF_8).take(300)}")
            val type = res.header("Content-Type").orEmpty()
            if (type.startsWith("application/json") || type.startsWith("text/")) {
                throw NoStackTraceException("Fish Audio 返回错误：${res.body.byteStream().readNBytes(1200).toString(Charsets.UTF_8).take(300)}")
            }
            val bytes = res.body.byteStream().use { it.readNBytes(MAX_AUDIO + 1) }
            if (bytes.size > MAX_AUDIO) throw NoStackTraceException("Fish Audio 返回的音频过大")
            bytes
        }
    }

    /** 按句末标点、逗号切成不超过 [max] 字的段 */
    internal fun chunks(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = window.indexOfLast { it in "。！？!?；;…\n" } + 1
            if (cut < max / 3) cut = window.indexOfLast { it in "，,、：: " } + 1
            if (cut < max / 3) cut = max
            if (Character.isHighSurrogate(rest[cut - 1])) cut--
            rest.substring(0, cut).takeIf { it.isNotBlank() }?.let(out::add)
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) out.add(rest)
        return out
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, msg: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg.toByteArray())
}
