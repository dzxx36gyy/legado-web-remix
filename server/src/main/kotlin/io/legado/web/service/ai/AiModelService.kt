package io.legado.web.service.ai

import io.legado.app.help.engine.Engine
import io.legado.app.utils.GSON
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.AiModels
import io.legado.web.db.dbQuery
import io.legado.web.security.NetworkGuard
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AiModelInput(
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

data class AiModelDto(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val model: String,
    val hasApiKey: Boolean,
    val temperature: Double?,
    val maxTokens: Int?,
)

class AiModelService {
    suspend fun fetchModels(user: UserPrincipal, input: FetchModelsRequest): List<String> {
        val baseUrl = validateBaseUrl(input.baseUrl)
        val key = input.apiKey?.takeIf { it.isNotBlank() }
            ?: input.id?.let { usable(user, it).apiKey }
            ?: throw BadRequestException("请填写 API Key")
        return AiChatClient().fetchModels(baseUrl, key)
    }

    internal fun validateBaseUrl(baseUrl: String): String {
        val url = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw BadRequestException("AI 模型地址无效")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw BadRequestException("AI 模型地址不能包含凭据、查询参数或片段")
        }
        try { NetworkGuard.checkHost(url.host) } catch (e: NetworkGuard.BlockedException) {
            throw BadRequestException(e.message.orEmpty())
        }
        return url.toString().trimEnd('/')
    }
    suspend fun backup(user: UserPrincipal): List<AiModelInput> = list(user).map { usable(user, it.id) }

    suspend fun restore(user: UserPrincipal, models: List<AiModelInput>) {
        applyRestore(user, prepareRestore(user, models))
    }
    data class PreparedRestore(val name: String, val json: String)

    suspend fun prepareRestore(user: UserPrincipal, models: List<AiModelInput>): List<PreparedRestore> {
        val existing = list(user).associateBy { it.name }
        return models.map { input ->
            val config = prepare(input, existing[input.name]?.id?.let { stored(user, it) })
            PreparedRestore(input.name, GSON.toJson(config))
        }
    }

    suspend fun applyRestore(user: UserPrincipal, models: List<PreparedRestore>) {
        val existing = list(user).associateBy { it.name }
        dbQuery {
            models.forEach { model ->
                val id = existing[model.name]?.id
                if (id == null) AiModels.insertAndGetId { it[userId] = user.id; it[json] = model.json }
                else {
                    val n = AiModels.update({ (AiModels.id eq id) and (AiModels.userId eq user.id) }) { it[json] = model.json }
                    if (n == 0) throw NotFoundException("AI 模型不存在")
                }
            }
        }
    }
    private fun dto(id: Long, c: AiModelInput) =
        AiModelDto(id, c.name, c.baseUrl, c.model, c.apiKey.isNotEmpty(), c.temperature, c.maxTokens)

    suspend fun list(user: UserPrincipal): List<AiModelDto> = dbQuery {
        AiModels.selectAll().where { AiModels.userId eq user.id }.map {
            dto(it[AiModels.id].value, GSON.fromJson(it[AiModels.json], AiModelInput::class.java))
        }
    }

    private suspend fun stored(user: UserPrincipal, id: Long): AiModelInput = dbQuery {
        AiModels.selectAll().where { (AiModels.id eq id) and (AiModels.userId eq user.id) }.firstOrNull()
            ?.let { GSON.fromJson(it[AiModels.json], AiModelInput::class.java) }
            ?: throw NotFoundException("AI 模型不存在")
    }

    suspend fun usable(user: UserPrincipal, id: Long): AiModelInput = stored(user, id).let {
        it.copy(apiKey = crypt(it.apiKey, false))
    }

    suspend fun save(user: UserPrincipal, id: Long?, input: AiModelInput): AiModelDto {
        val old = id?.let { stored(user, it) }
        val config = prepare(input, old)
        val saved = dbQuery {
            if (id == null) AiModels.insertAndGetId {
                it[userId] = user.id
                it[json] = GSON.toJson(config)
            }.value else {
                val n = AiModels.update({ (AiModels.id eq id) and (AiModels.userId eq user.id) }) {
                    it[json] = GSON.toJson(config)
                }
                if (n == 0) throw NotFoundException("AI 模型不存在")
                id
            }
        }
        return dto(saved, config)
    }

    private fun prepare(input: AiModelInput, old: AiModelInput?): AiModelInput {
        val baseUrl = validateBaseUrl(input.baseUrl)
        if (input.name.isBlank() || input.name.length > 255 || input.model.isBlank() || input.model.length > 255) {
            throw BadRequestException("请填写 AI 模型名称和模型标识（最多 255 字符）")
        }
        if (input.temperature?.let { !it.isFinite() || it !in 0.0..2.0 } == true ||
            input.maxTokens?.let { it !in 1..131072 } == true) throw BadRequestException("AI 模型参数无效")
        val key = input.apiKey.takeIf { it.isNotBlank() }?.let { crypt(it, true) } ?: old?.apiKey
            ?: throw BadRequestException("请填写 API Key")
        return input.copy(name = input.name.trim(), model = input.model.trim(), baseUrl = baseUrl, apiKey = key)
    }

    suspend fun delete(user: UserPrincipal, id: Long) {
        val n = dbQuery { AiModels.deleteWhere { (AiModels.id eq id) and (AiModels.userId eq user.id) } }
        if (n == 0) throw NotFoundException("AI 模型不存在")
    }

    // 与云 TTS 相同的 AES-GCM 方案，使用独立的用途派生密钥。
    private fun crypt(value: String, encrypt: Boolean): String {
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest((Engine.secret + ":ai").toByteArray()), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (encrypt) {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return Base64.getEncoder().encodeToString(iv + cipher.doFinal(value.toByteArray()))
        }
        try {
            val raw = Base64.getDecoder().decode(value)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
            return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            throw BadRequestException("AI 模型密钥无法解密，请重新填写")
        }
    }
}

data class FetchModelsRequest(val baseUrl: String = "", val apiKey: String? = null, val id: Long? = null)
