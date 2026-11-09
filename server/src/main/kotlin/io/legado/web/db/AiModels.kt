package io.legado.web.db

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** 用户自己的 AI 模型，密钥以 AES-GCM 密文保存。 */
object AiModels : LongIdTable("ai_models") {
    val userId = long("user_id").index()
    val json = text("json")
}
