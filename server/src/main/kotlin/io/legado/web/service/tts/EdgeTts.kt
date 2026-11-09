package io.legado.web.service.tts

import com.google.gson.JsonParser
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 微软 Edge 在线朗读（与 github.com/rany2/edge-tts 相同的协议），服务器直接连接微软，不需要额外部署服务。
 */
object EdgeTts {

    private const val BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_URL = "wss://$BASE/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
    private const val VOICE_LIST = "https://$BASE/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"
    private const val CHROMIUM_FULL = "143.0.3650.75"
    private const val CHROMIUM_MAJOR = "143"
    private const val GEC_VERSION = "2-$CHROMIUM_FULL"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
    /** 单个 SSML 请求的文字上限（字节，转义后），与 edge-tts 相同 */
    private const val MAX_SSML_TEXT = 4096
    private const val MAX_AUDIO = 10 * 1024 * 1024

    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

    /** 固定访问微软的服务器，不经过书源用的网络防护 */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var clockSkewSeconds = 0L

    data class Voice(val id: String, val name: String, val locale: String, val gender: String)

    @Volatile
    private var voiceCache: Pair<Long, List<Voice>>? = null

    suspend fun voices(): List<Voice> {
        voiceCache?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.let { return it.second }
        val body = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$VOICE_LIST&Sec-MS-GEC=${secMsGec(nowSeconds())}&Sec-MS-GEC-Version=$GEC_VERSION")
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=${muid()};")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    adjustSkew(res)
                    throw NoStackTraceException("读取 Edge 音色列表失败（${res.code}）")
                }
                res.body.string()
            }
        }
        val list = JsonParser.parseString(body).asJsonArray.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("ShortName")?.asString ?: return@mapNotNull null
            val friendly = o.get("FriendlyName")?.asString.orEmpty()
            Voice(
                id = id,
                name = friendly.removePrefix("Microsoft ").substringBefore(" Online").ifBlank { id },
                locale = o.get("Locale")?.asString.orEmpty(),
                gender = o.get("Gender")?.asString.orEmpty(),
            )
        }
        voiceCache = System.currentTimeMillis() to list
        return list
    }

    /**
     * 合成 MP3。[speed] 与阅读相同：10 为正常语速；[voice] 为 ShortName，例如 zh-CN-XiaoxiaoNeural。
     */
    suspend fun synthesize(text: String, voice: String, speed: Int, pitch: Int = 0, volume: Int = 0): ByteArray {
        val fullVoice = fullVoiceName(voice) ?: throw NoStackTraceException("Edge 音色名称不正确：$voice")
        val rate = ((speed.coerceIn(1, 50) - 10) * 10).coerceIn(-90, 200)
        val out = ByteArrayOutputStream()
        for (part in splitText(clean(text))) {
            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$fullVoice'><prosody pitch='${signed(pitch)}Hz' rate='${signed(rate)}%' volume='${signed(volume)}%'>" +
                part + "</prosody></voice></speak>"
            val audio = try {
                stream(ssml)
            } catch (e: SkewException) {
                // 服务器时间与本机相差较大时 403：按服务器时间校正后重试一次
                stream(ssml)
            }
            out.write(audio)
            if (out.size() > MAX_AUDIO) throw NoStackTraceException("Edge 朗读返回的音频过大")
        }
        if (out.size() == 0) throw NoStackTraceException("Edge 朗读没有返回音频")
        return out.toByteArray()
    }

    private class SkewException : Exception()

    private suspend fun stream(ssml: String): ByteArray = withTimeout(60_000) {
        suspendCancellableCoroutine { cont ->
            val url = "$WSS_URL&ConnectionId=${connectId()}&Sec-MS-GEC=${secMsGec(nowSeconds())}&Sec-MS-GEC-Version=$GEC_VERSION"
            val req = Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=${muid()};")
                .build()
            val audio = ByteArrayOutputStream()
            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ts = jsDate()
                    webSocket.send(
                        "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                    )
                    webSocket.send(
                        "X-RequestId:${connectId()}\r\nContent-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml"
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val head = text.substringBefore("\r\n\r\n")
                    if (head.lineSequence().any { it.trim() == "Path:turn.end" }) {
                        webSocket.close(1000, null)
                        if (cont.isActive) {
                            if (audio.size() == 0) cont.resumeWithException(NoStackTraceException("Edge 朗读没有返回音频"))
                            else cont.resume(audio.toByteArray())
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    if (headerLen + 2 > data.size) return
                    val head = String(data, 2, headerLen, Charsets.US_ASCII)
                    if (head.lines().none { it.trim() == "Path:audio" }) return
                    if (head.lines().none { it.trim().equals("Content-Type:audio/mpeg", true) }) return
                    synchronized(audio) {
                        audio.write(data, 2 + headerLen, data.size - 2 - headerLen)
                        if (audio.size() > MAX_AUDIO) webSocket.cancel()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!cont.isActive) return
                    if (response?.code == 403 && adjustSkew(response)) cont.resumeWithException(SkewException())
                    else cont.resumeWithException(
                        NoStackTraceException("连接 Edge 朗读失败：" + (response?.let { "HTTP ${it.code}" } ?: t.message.orEmpty()))
                    )
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) cont.resumeWithException(NoStackTraceException("Edge 朗读连接意外关闭（$code）"))
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
    }

    /** 根据服务器返回的 Date 校正本机时间；返回是否有变化 */
    private fun adjustSkew(response: Response): Boolean {
        val date = response.header("Date") ?: return false
        val server = runCatching {
            ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
        }.getOrNull() ?: return false
        val skew = server - System.currentTimeMillis() / 1000
        if (kotlin.math.abs(skew - clockSkewSeconds) < 30) return false
        clockSkewSeconds = skew
        return true
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000 + clockSkewSeconds

    private val random = SecureRandom()

    private fun muid(): String = ByteArray(16).also { random.nextBytes(it) }.toHex()

    private fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"

    private fun jsDate(): String = DateTimeFormatter
        .ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .format(ZonedDateTime.now(ZoneOffset.UTC))

    private val voiceRe = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$")
    private val safeVoiceRe = Regex("^[A-Za-z0-9-]+$")

    /** zh-CN-XiaoxiaoNeural → Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural) */
    fun fullVoiceName(voice: String): String? {
        val v = voice.trim()
        if (!safeVoiceRe.matches(v)) return null
        val m = voiceRe.matchEntire(v) ?: return null
        var (lang, region, name) = m.destructured
        if ('-' in name) {
            region += "-" + name.substringBefore('-')
            name = name.substringAfter('-')
        }
        return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
    }

    /** 服务不支持的控制字符换成空格 */
    private fun clean(text: String): String = buildString(text.length) {
        text.forEach { c ->
            val code = c.code
            append(if (code in 0..8 || code in 11..12 || code in 14..31) ' ' else c)
        }
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 按转义后的字节数切分，优先在换行、空格处断开 */
    internal fun splitText(text: String): List<String> {
        val parts = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            var bytes = 0
            var end = 0
            while (end < rest.length) {
                // 按码点计算：单独的代理项编码为 1 字节的“?”，会把 4 字节的 emoji 算少
                val len = Character.charCount(rest.codePointAt(end))
                val n = escapeXml(rest.substring(end, end + len)).toByteArray(Charsets.UTF_8).size
                if (bytes + n > MAX_SSML_TEXT) break
                bytes += n
                end += len
            }
            if (end < rest.length) {
                // 尽量在空白处断开
                val space = rest.lastIndexOfAny(charArrayOf('\n', ' '), end - 1)
                if (space > end / 2) end = space + 1
            }
            val piece = rest.substring(0, end).trim()
            if (piece.isNotEmpty()) parts.add(escapeXml(piece))
            rest = rest.substring(end)
        }
        return parts
    }

    // ───────────── Sec-MS-GEC v2（与 edge-tts drm.py 相同的算法） ─────────────

    private const val WIN_EPOCH = 11644473600L

    /** [unixSeconds] 为 Unix 秒数；按 5 分钟取整后转为 Windows 文件时间，再计算摘要 */
    internal fun secMsGec(unixSeconds: Long): String {
        var ticks = unixSeconds + WIN_EPOCH
        ticks -= ticks % 300
        val data = "${ticks * 10_000_000L}$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val (c0, c1) = cs64Digest(data, digest)
        val cs = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(c0).putInt(c1).array()
        return cs.toHex() + "-" + digest.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private val SHA_INITIAL = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
    )
    private val SHA_STATE = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2,
    )
    private val SHA_SCHEDULE = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA, 0xA622EE8C.toInt(),
        0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(), 0xF0C31573.toInt(), 0x7058CE4E,
        0x7188E606, 0x00E5AB33, 0xD258B060.toInt(), 0x72766564,
    )
    private val K = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    ).map { it.toInt() }.toIntArray()
    private const val SHA_ADJUSTMENT = 0xC9DC06A7.toInt()
    private val K_BLOCK = K.copyOf().also { it[14] = SHA_ADJUSTMENT }
    private const val SIGMA0_ADJUSTMENT = 0x059C0B80
    private const val SIGMA1_ADJUSTMENT = 0x000B92C0

    private fun sigma0(x: Int) = Integer.rotateRight(x, 7) xor Integer.rotateRight(x, 18) xor (x ushr 3)
    private fun sigma1(x: Int) = Integer.rotateRight(x, 17) xor Integer.rotateRight(x, 19) xor (x ushr 10)

    private fun expand(w: IntArray, from: Int) {
        for (i in from until 64) w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]
    }

    private fun rounds(state: IntArray, w: IntArray, first: Int): IntArray {
        val k = if (first == 14) K_BLOCK else K
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in first until 64) {
            val s1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
            val t1 = h + s1 + (g xor (e and (f xor g))) + k[i] + w[i]
            val s0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
            val t2 = s0 + ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun sha256Init(data: ByteArray): IntArray {
        val w = IntArray(64)
        w[14] = ((data[0].toInt() and 0xFF) shl 16) or ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        w[15] = ByteBuffer.wrap(data, 3, 4).int
        for (i in 16 until 30) {
            var v = SHA_SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) sigma1(w[i - 2]) xor SIGMA1_ADJUSTMENT else sigma1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) sigma0(w[i - 15]) xor SIGMA0_ADJUSTMENT else sigma0(w[i - 15])
            w[i] = v
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + SHA_ADJUSTMENT - K[14]
        expand(w, 31)
        val working = rounds(SHA_STATE, w, 14)
        return IntArray(8) { SHA_INITIAL[it] + working[it] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7)
        val length = 57 + data.size
        val zeros = Math.floorMod(55 - length, 64)
        val padded = ByteBuffer.allocate(data.size - 7 + 1 + zeros + 8)
            .put(data, 7, data.size - 7).put(0x80.toByte()).put(ByteArray(zeros)).putLong(length * 8L)
            .array()
        var state = sha256Init(data.copyOfRange(0, 7))
        val buf = ByteBuffer.wrap(padded)
        for (offset in padded.indices step 64) {
            val w = IntArray(64)
            for (i in 0 until 16) w[i] = buf.getInt(offset + i * 4)
            expand(w, 16)
            val working = rounds(state, w, 0)
            state = IntArray(8) { state[it] + working[it] }
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }

    private val CS64_COEFFICIENTS = arrayOf(
        0xE82E91B1 to 0xB32700D0, 0x44DE24C6 to 0x88DFF51D, 0x3BEB65BC to 0x29514038,
        0xC89C5933 to 0xB328CBDF, 0x8D1EC211 to 0xD405B4D5, 0x314EF5D5 to 0x26E55F3C,
        0xE92491B1 to 0x607801C6, 0xF0A5B9D5 to 0x82B2C1FB, 0x06064CF3 to 0xFA51A3DA,
        0xDA79FA60 to 0x207CC464, 0xBED20560 to 0x3C3C74F3, 0x89F9C9F8 to 0xD2D14B61,
        0x35FA38C6 to 0xBB04A2C1, 0xCB49E3C6 to 0xC5626167,
    ).map { (a, b) -> a.toInt() to b.toInt() }
    private const val CS64_FACTOR = 0x82893933.toInt()
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000

    private fun rol16(v: Int) = Integer.rotateLeft(v, 16)

    /** 所有运算只取低 32 位，Int 的溢出回绕与原实现的掩码等价 */
    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Int, Int> {
        val d = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = d.getInt(0) or 1
        val keyB = d.getInt(4) or 1
        val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
        val terms = ArrayList<Int>()
        val offsets = ArrayList<Int>()
        CS64_COEFFICIENTS.forEachIndexed { i, (coefficient, offset) ->
            terms.add(coefficient * factors[i % 2])
            offsets.add(offset)
        }
        val raw = byteArrayOf(0) + data
        val padded = raw.copyOf(raw.size + Math.floorMod(-raw.size, 4))
        val wordBuf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        val words = MutableList(padded.size / 4) { wordBuf.getInt(it * 4) }
        if (words.size % 2 == 1) words.add(0)
        words.forEachIndexed { i, word ->
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term)
            offsets.add(offset)
        }
        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        for (i in terms.indices step 2) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16(offsets[i] + x)
            var q = t * 0x76F50F34
            swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
            t = rol16(offsets[i + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rol16(q) * 0x35BD1EC9 + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111.toInt()
            state = rol16(state) * 0x87085B9F.toInt()
            state = rol16(state) * 0x12CEB96D
            state = rol16(state) * 0x257E1D83
            acc += state
            t = state * keyB + y
            t = rol16(t) * 0xA27416F5.toInt()
            t = rol16(t) * 0xD38396FF.toInt()
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }
}
