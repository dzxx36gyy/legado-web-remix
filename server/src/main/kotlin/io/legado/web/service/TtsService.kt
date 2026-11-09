package io.legado.web.service

import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.engine.EngineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.TtsEngines
import io.legado.web.db.Users
import io.legado.web.db.dbQuery
import io.legado.web.engine.runEngine
import com.google.gson.JsonParser
import io.legado.app.help.engine.Engine
import io.legado.web.service.tts.EdgeTts
import io.legado.web.service.tts.ProviderTts
import okhttp3.Response
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * 在线朗读引擎（App 的 httpTTS）：请求地址规则在服务器上执行，返回音频。
 *
 * 规则中可以执行 JavaScript、访问任意网址，与书源相同，所以只有能添加书源的用户可以导入；
 * 管理员导入的引擎所有用户都可以使用。
 */
class TtsService(private val sourceService: SourceService) {
    data class BackupEngine(val name: String, val http: String? = null, val provider: ProviderEngineInput? = null)

    suspend fun backup(user: UserPrincipal): List<BackupEngine> {
        val rows = dbQuery { TtsEngines.selectAll().where { TtsEngines.userId eq user.id }.toList() }
        return rows.map { row ->
            val provider = providerOf(row[TtsEngines.json])
            if (provider == null) BackupEngine(row[TtsEngines.name], row[TtsEngines.json])
            else BackupEngine(row[TtsEngines.name], provider = ProviderEngineInput(
                row[TtsEngines.name], provider.provider, provider.voice, decrypt(provider.apiKey), provider.secretId, provider.region,
            ))
        }
    }

    suspend fun restore(user: UserPrincipal, engines: List<BackupEngine>) {
        val own = list(user).filter { it.owned }.associateBy { it.name }
        engines.forEach { engine ->
            if (engine.provider != null) saveProvider(user, own[engine.name]?.id, engine.provider)
            else engine.http?.let { import(user, it) }
        }
    }

    data class TtsEngineDto(
        val id: Long,
        val name: String,
        val url: String,
        val contentType: String?,
        val hasLogin: Boolean,
        /** 自己导入的（可以删除）；否则是管理员提供的公共引擎 */
        val owned: Boolean,
        /** 云服务商引擎为 [ProviderTts.Provider] 的名称，阅读 App 格式的引擎为 null */
        val provider: String? = null,
        val voice: String? = null,
    )

    data class ImportResult(val added: Int, val updated: Int)

    data class SpeechAudio(val bytes: ByteArray, val contentType: String)

    companion object {
        /** 单次朗读的最大字数（前端按段落、句子切分后请求） */
        const val MAX_TEXT = 1000
        private const val MAX_AUDIO = 10L * 1024 * 1024
    }

    private val parsed = ConcurrentHashMap<Long, Pair<String, HttpTTS>>()

    private fun parse(id: Long, json: String): HttpTTS =
        parsed[id]?.takeIf { it.first == json }?.second
            ?: GSON.fromJsonObject<HttpTTS>(json).getOrThrow().copy(id = id).also { parsed[id] = json to it }

    private suspend fun adminIds(): List<Long> = dbQuery {
        Users.select(Users.id).where { Users.role eq "admin" }.map { it[Users.id].value }
    }

    suspend fun list(user: UserPrincipal): List<TtsEngineDto> {
        val admins = adminIds()
        return dbQuery {
            TtsEngines.selectAll()
                .where { (TtsEngines.userId eq user.id) or (TtsEngines.userId inList admins) }
                .orderBy(TtsEngines.order to SortOrder.ASC, TtsEngines.id to SortOrder.ASC)
                .mapNotNull { r ->
                    val p = providerOf(r[TtsEngines.json])
                    // 云服务商引擎用的是创建者自己的 API Key，只有创建者能看到和使用
                    if (p != null && r[TtsEngines.userId] != user.id) return@mapNotNull null
                    val tts = if (p == null) parse(r[TtsEngines.id].value, r[TtsEngines.json]) else null
                    TtsEngineDto(
                        id = r[TtsEngines.id].value,
                        name = r[TtsEngines.name],
                        url = tts?.url.orEmpty(),
                        contentType = tts?.contentType,
                        hasLogin = !tts?.loginUrl.isNullOrBlank(),
                        owned = r[TtsEngines.userId] == user.id,
                        provider = p?.provider,
                        voice = p?.voice,
                    )
                }
        }
    }

    /** 可以使用的引擎（自己的，或管理员的阅读 App 格式引擎）的 json；云服务商引擎只有创建者能用 */
    private suspend fun usableJson(user: UserPrincipal, id: Long): String {
        val admins = adminIds()
        val row = dbQuery {
            TtsEngines.selectAll().where { TtsEngines.id eq id }.firstOrNull()
        } ?: throw NotFoundException("朗读引擎不存在")
        val owner = row[TtsEngines.userId]
        if (owner != user.id && (owner !in admins || providerOf(row[TtsEngines.json]) != null)) {
            throw NotFoundException("朗读引擎不存在")
        }
        return row[TtsEngines.json]
    }

    // ---------------- 内置引擎：Edge 朗读、云服务商（见 tts/EdgeTts、tts/ProviderTts） ----------------

    data class ProviderInfo(
        val id: String,
        val label: String,
        val defaultVoice: String,
        val voices: List<VoiceOption>,
        /** 腾讯云需要 SecretId 和地域 */
        val needsSecretId: Boolean,
    )

    data class VoiceOption(val id: String, val name: String, val locale: String = "", val gender: String = "")

    /** 云服务商引擎的编辑内容；读取时不返回 API Key（[hasApiKey]），保存时留空表示不修改 */
    data class ProviderEngineInput(
        val name: String = "",
        val provider: String = "",
        val voice: String = "",
        val apiKey: String = "",
        val secretId: String = "",
        val region: String = "",
        val hasApiKey: Boolean = false,
    )

    fun providers(): List<ProviderInfo> = ProviderTts.Provider.entries.map { p ->
        ProviderInfo(
            id = p.name,
            label = p.label,
            defaultVoice = p.defaultVoice,
            voices = p.voices.map { (id, name) -> VoiceOption(id, name) },
            needsSecretId = p == ProviderTts.Provider.tencent,
        )
    }

    suspend fun edgeVoices(): List<VoiceOption> =
        EdgeTts.voices().map { VoiceOption(it.id, it.name, it.locale, it.gender) }

    suspend fun edgeSpeak(text: String, voice: String, speed: Int): SpeechAudio {
        val speakText = checkText(text)
        return SpeechAudio(EdgeTts.synthesize(speakText, voice.ifBlank { EdgeTts.DEFAULT_VOICE }, speed), "audio/mpeg")
    }

    /** 行里保存的云服务商配置（API Key 已加密）；普通 httpTTS 引擎返回 null */
    private fun providerOf(json: String): ProviderTts.Config? = runCatching {
        JsonParser.parseString(json).asJsonObject.get("provider")
            ?.takeIf { it.isJsonObject }
            ?.let { GSON.fromJson(it, ProviderTts.Config::class.java) }
    }.getOrNull()

    private fun providerJson(name: String, c: ProviderTts.Config): String =
        GSON.toJson(mapOf("name" to name, "url" to "", "provider" to c))

    suspend fun getProvider(user: UserPrincipal, id: Long): ProviderEngineInput {
        val row = dbQuery {
            TtsEngines.selectAll().where { (TtsEngines.id eq id) and (TtsEngines.userId eq user.id) }.firstOrNull()
        } ?: throw NotFoundException("朗读引擎不存在")
        val c = providerOf(row[TtsEngines.json]) ?: throw NotFoundException("朗读引擎不存在")
        return ProviderEngineInput(row[TtsEngines.name], c.provider, c.voice, "", c.secretId, c.region, c.apiKey.isNotEmpty())
    }

    /** 整理用户填写的配置；API Key 留空时沿用 [old] 的 */
    private fun providerConfig(input: ProviderEngineInput, old: ProviderTts.Config?): ProviderTts.Config {
        val c = ProviderTts.validate(
            ProviderTts.Config(input.provider, input.voice, input.apiKey, input.secretId, input.region),
        )
        val key = c.apiKey.ifEmpty { old?.takeIf { it.provider == c.provider }?.let { decrypt(it.apiKey) }.orEmpty() }
        if (key.isEmpty()) throw BadRequestException("请填写 API Key")
        if (c.provider == ProviderTts.Provider.tencent.name && c.secretId.isEmpty()) throw BadRequestException("请填写 SecretId")
        return c.copy(apiKey = key)
    }

    /** 新建（[id] 为空）或修改自己的云服务商引擎；不执行脚本，所有用户都可以添加 */
    suspend fun saveProvider(user: UserPrincipal, id: Long?, input: ProviderEngineInput): ProviderEngineInput {
        val name = input.name.trim().take(255)
        if (name.isEmpty()) throw BadRequestException("请填写名称")
        val old = id?.let { oldId ->
            dbQuery {
                TtsEngines.selectAll().where { (TtsEngines.id eq oldId) and (TtsEngines.userId eq user.id) }.firstOrNull()
            }?.let { providerOf(it[TtsEngines.json]) } ?: throw NotFoundException("朗读引擎不存在")
        }
        val c = providerConfig(input, old)
        val stored = c.copy(apiKey = encrypt(c.apiKey))
        val savedId = dbQuery {
            val sameName = TtsEngines.selectAll()
                .where { (TtsEngines.userId eq user.id) and (TtsEngines.name eq name) }
                .any { it[TtsEngines.id].value != id }
            if (sameName) throw BadRequestException("已有同名的朗读引擎")
            if (id != null) {
                TtsEngines.update({ (TtsEngines.id eq id) and (TtsEngines.userId eq user.id) }) {
                    it[TtsEngines.name] = name
                    it[json] = providerJson(name, stored)
                }
                id
            } else {
                val order = TtsEngines.selectAll().where { TtsEngines.userId eq user.id }.count().toInt()
                TtsEngines.insertAndGetId {
                    it[userId] = user.id
                    it[TtsEngines.name] = name
                    it[json] = providerJson(name, stored)
                    it[TtsEngines.order] = order
                    it[createdAt] = System.currentTimeMillis()
                }.value
            }
        }
        parsed.remove(savedId)
        return getProvider(user, savedId)
    }

    /** 用编辑中的云服务商配置试听；[id] 为正在编辑的引擎（API Key 留空时用已保存的） */
    suspend fun testProvider(user: UserPrincipal, id: Long?, input: ProviderEngineInput, text: String, speed: Int): SpeechAudio {
        val old = id?.let { oldId ->
            dbQuery {
                TtsEngines.selectAll().where { (TtsEngines.id eq oldId) and (TtsEngines.userId eq user.id) }.firstOrNull()
            }?.let { providerOf(it[TtsEngines.json]) }
        }
        val c = providerConfig(input, old)
        return SpeechAudio(ProviderTts.synthesize(c, checkText(text), speed), "audio/mpeg")
    }

    // ---------------- 云服务商 API Key 加密保存 ----------------

    private val aesKey by lazy {
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest((Engine.secret + ":tts").toByteArray()), "AES")
    }

    private fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    private fun decrypt(enc: String): String = try {
        val raw = Base64.getDecoder().decode(enc)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        throw BadRequestException("朗读引擎的 API Key 无法解密（服务器密钥可能已变更），请重新填写")
    }

    private fun checkText(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) throw BadRequestException("没有要朗读的文字")
        if (t.length > MAX_TEXT) throw BadRequestException("单次朗读的文字过长")
        return t
    }

    /** 导入 App 导出的 httpTTS（数组或单个对象）；同名的覆盖 */
    suspend fun import(user: UserPrincipal, text: String): ImportResult {
        if (!sourceService.canAddSource(user)) throw ForbiddenException("没有导入朗读引擎的权限（与添加书源的权限相同）")
        val t = text.trim()
        val list = when {
            t.startsWith("[") -> GSON.fromJsonArray<HttpTTS>(t).getOrNull()
            t.startsWith("{") -> GSON.fromJsonObject<HttpTTS>(t).getOrNull()?.let { listOf(it) }
            else -> null
        } ?: throw BadRequestException("格式不正确，请粘贴阅读 App 导出的朗读引擎 JSON")
        val valid = list.filter { it.name.isNotBlank() && it.url.isNotBlank() }
        if (valid.isEmpty()) throw BadRequestException("没有有效的朗读引擎（需要 name 和 url）")
        var added = 0
        var updated = 0
        dbQuery {
            val existing = TtsEngines.selectAll().where { TtsEngines.userId eq user.id }
                .associate { it[TtsEngines.name] to it[TtsEngines.id].value }
            var order = existing.size
            valid.forEach { tts ->
                val name = tts.name.take(255)
                val oldId = existing[name]
                if (oldId != null) {
                    TtsEngines.update({ TtsEngines.id eq oldId }) { it[json] = GSON.toJson(tts.copy(id = oldId)) }
                    updated++
                } else {
                    val id = TtsEngines.insertAndGetId {
                        it[userId] = user.id
                        it[TtsEngines.name] = name
                        it[json] = "{}"
                        it[TtsEngines.order] = order++
                        it[createdAt] = System.currentTimeMillis()
                    }.value
                    TtsEngines.update({ TtsEngines.id eq id }) { it[json] = GSON.toJson(tts.copy(id = id)) }
                    added++
                }
            }
        }
        return ImportResult(added, updated)
    }

    suspend fun delete(user: UserPrincipal, id: Long) {
        val n = dbQuery { TtsEngines.deleteWhere { (TtsEngines.id eq id) and (TtsEngines.userId eq user.id) } }
        if (n == 0) throw NotFoundException("朗读引擎不存在")
        parsed.remove(id)
    }

    /** 自己的引擎的完整内容（编辑用） */
    suspend fun get(user: UserPrincipal, id: Long): HttpTTS {
        val row = dbQuery {
            TtsEngines.selectAll().where { (TtsEngines.id eq id) and (TtsEngines.userId eq user.id) }.firstOrNull()
        }?.takeIf { providerOf(it[TtsEngines.json]) == null } ?: throw NotFoundException("朗读引擎不存在")
        return parse(id, row[TtsEngines.json])
    }

    /** 新建（[id] 为空）或修改自己的引擎；同一用户的引擎不能重名（导入时按名称覆盖） */
    suspend fun save(user: UserPrincipal, id: Long?, tts: HttpTTS): HttpTTS {
        if (!sourceService.canAddSource(user)) throw ForbiddenException("没有编辑朗读引擎的权限（与添加书源的权限相同）")
        val name = tts.name.trim().take(255)
        if (name.isEmpty()) throw BadRequestException("请填写名称")
        if (tts.url.isBlank()) throw BadRequestException("请填写 url")
        tts.contentType?.takeIf { it.isNotBlank() }?.let {
            runCatching { it.toRegex() }.onFailure { throw BadRequestException("Content-Type 不是有效的正则表达式") }
        }
        val savedId = dbQuery {
            val sameName = TtsEngines.selectAll()
                .where { (TtsEngines.userId eq user.id) and (TtsEngines.name eq name) }
                .any { it[TtsEngines.id].value != id }
            if (sameName) throw BadRequestException("已有同名的朗读引擎")
            if (id != null) {
                val n = TtsEngines.update({ (TtsEngines.id eq id) and (TtsEngines.userId eq user.id) }) {
                    it[TtsEngines.name] = name
                    it[json] = GSON.toJson(tts.copy(id = id, name = name, lastUpdateTime = System.currentTimeMillis()))
                }
                if (n == 0) throw NotFoundException("朗读引擎不存在")
                id
            } else {
                val order = TtsEngines.selectAll().where { TtsEngines.userId eq user.id }.count().toInt()
                val newId = TtsEngines.insertAndGetId {
                    it[userId] = user.id
                    it[TtsEngines.name] = name
                    it[json] = "{}"
                    it[TtsEngines.order] = order
                    it[createdAt] = System.currentTimeMillis()
                }.value
                TtsEngines.update({ TtsEngines.id eq newId }) {
                    it[json] = GSON.toJson(tts.copy(id = newId, name = name, lastUpdateTime = System.currentTimeMillis()))
                }
                newId
            }
        }
        parsed.remove(savedId)
        return get(user, savedId)
    }

    /** 导出自己导入的引擎（App 可以导入）；云服务商引擎只能在本服务器使用，不导出 */
    suspend fun export(user: UserPrincipal): List<HttpTTS> = dbQuery {
        TtsEngines.selectAll().where { TtsEngines.userId eq user.id }
            .orderBy(TtsEngines.order to SortOrder.ASC)
            .filter { providerOf(it[TtsEngines.json]) == null }
            .map { parse(it[TtsEngines.id].value, it[TtsEngines.json]) }
    }

    /**
     * 合成一段语音。[speed] 与 App 相同：10 为正常语速（界面上的 1.0 倍）。
     * 与 App 相同的检查：返回 JSON / 文本，或与引擎的 contentType 正则不匹配时视为出错，把返回内容作为错误信息。
     */
    suspend fun speak(user: UserPrincipal, id: Long, text: String, speed: Int): SpeechAudio {
        val json = usableJson(user, id)
        val p = providerOf(json) ?: return synthesize(user, parse(id, json), text, speed)
        return SpeechAudio(ProviderTts.synthesize(p.copy(apiKey = decrypt(p.apiKey)), checkText(text), speed), "audio/mpeg")
    }

    /** 用还没保存的引擎试听（编辑页）：规则在服务器上执行，需要添加书源的权限 */
    suspend fun test(user: UserPrincipal, tts: HttpTTS, text: String, speed: Int): SpeechAudio {
        if (!sourceService.canAddSource(user)) throw ForbiddenException("没有编辑朗读引擎的权限（与添加书源的权限相同）")
        if (tts.url.isBlank()) throw BadRequestException("请填写 url")
        // id 为负数：不与已保存的引擎共用 Cookie 等数据
        return synthesize(user, tts.copy(id = -1), text, speed)
    }

    private suspend fun synthesize(user: UserPrincipal, tts: HttpTTS, text: String, speed: Int): SpeechAudio {
        val speakText = checkText(text)
        // 朗读时不应弹出书源网页
        val ctx = EngineContext(user.id).apply { suppressUi = true }
        return runEngine(user.id, 90_000, ctx = ctx) {
            val analyzeUrl = AnalyzeUrl(
                tts.url,
                speakText = speakText,
                speakSpeed = speed.coerceIn(1, 50),
                source = tts,
                readTimeout = 60_000L,
                coroutineContext = coroutineContext,
            )
            var response = analyzeUrl.getResponseAwait()
            tts.loginCheckJs?.takeIf { it.isNotBlank() }?.let { js ->
                response = analyzeUrl.evalJS(js, response) as? Response ?: response
            }
            response.use { res ->
                val type = res.header("Content-Type")?.substringBefore(";")?.trim().orEmpty()
                fun fail(prefix: String): Nothing =
                    throw NoStackTraceException(prefix + res.body.string().take(300))
                if (!res.isSuccessful) fail("朗读引擎返回错误（${res.code}）：")
                if (type == "application/json" || type.startsWith("text/")) fail("朗读引擎返回错误：")
                val ct = tts.contentType
                if (!ct.isNullOrBlank() && type.isNotEmpty() && !type.matches(ct.toRegex())) fail("朗读引擎返回错误：")
                val len = res.body.contentLength()
                if (len > MAX_AUDIO) throw NoStackTraceException("朗读引擎返回的音频过大")
                val bytes = res.body.byteStream().use { it.readNBytes((MAX_AUDIO + 1).toInt()) }
                if (bytes.size > MAX_AUDIO) throw NoStackTraceException("朗读引擎返回的音频过大")
                if (bytes.isEmpty()) throw NoStackTraceException("朗读引擎没有返回音频")
                SpeechAudio(bytes, type.takeIf { it.startsWith("audio/") || it.startsWith("video/") } ?: "audio/mpeg")
            }
        }
    }
}
