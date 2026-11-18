package io.legado.web.service.tts

import com.google.gson.JsonParser
import io.legado.app.data.entities.HttpTTS
import io.legado.app.utils.GSON
import io.legado.web.auth.BadRequestException

sealed interface StoredTtsEngine {
    data class Http(val engine: HttpTTS) : StoredTtsEngine
    data class Provider(val kind: ProviderTts.Provider, val config: ProviderTts.Config) : StoredTtsEngine

    companion object {
        fun parse(json: String): StoredTtsEngine {
            val root = JsonParser.parseString(json).asJsonObject
            if (!root.has("provider")) return Http(GSON.fromJson(root, HttpTTS::class.java))
            return try {
                val value = root.get("provider")
                require(value.isJsonObject)
                require(listOf("provider", "voice", "apiKey", "secretId", "region").all { field ->
                    value.asJsonObject.get(field)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true
                })
                val fields = value.asJsonObject
                if (!fields.has("model")) fields.addProperty("model", if (fields.get("provider").asString == "fishaudio") ProviderTts.fishModels.first() else "")
                require(fields.get("model").let { it.isJsonPrimitive && it.asJsonPrimitive.isString })
                // 读取只检查存储结构；旧音色可能已下线，仍须允许列出和编辑，参数校验留在保存与合成入口。
                val config = GSON.fromJson(fields, ProviderTts.Config::class.java)
                val kind = ProviderTts.provider(config.provider)
                Provider(kind, config)
            } catch (e: Exception) {
                throw BadRequestException("朗读服务商配置损坏，请重新保存")
            }
        }
    }
}
