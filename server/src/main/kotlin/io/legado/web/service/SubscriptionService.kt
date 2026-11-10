package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.BookSources
import io.legado.web.db.SourceSubscriptionItems
import io.legado.web.db.SourceSubscriptions
import io.legado.web.db.Users
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

data class SubscriptionDto(
    val id: Long,
    val name: String,
    val url: String,
    val isPublic: Boolean,
    val autoUpdate: Boolean,
    val lastUpdateAt: Long,
    val lastResult: String?,
    val owned: Boolean,
)

/**
 * 书源订阅：定期从链接拉取书源并导入
 */
class SubscriptionService(private val sourceService: SourceService, private val ttsService: TtsService? = null,
    private val ruleKind: String? = null, private val importRules: (suspend (UserPrincipal, String) -> Int)? = null) {
    private val kind = ruleKind ?: if (ttsService == null) "source" else "tts"
    /** TTS、规则订阅是私有数据：只有创建者能管理，管理员也不能代管 */
    private val privateKind = kind != "source"
    private val refreshLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    private fun rowFilter(id: Long) = (SourceSubscriptions.id eq id) and (SourceSubscriptions.kind eq kind)

    /** 书源订阅保留管理员管理；私有订阅只能由创建者操作 */
    private fun requireManageable(user: UserPrincipal, row: ResultRow) {
        if (row[SourceSubscriptions.ownerId] == user.id) return
        if (!privateKind && user.isAdmin) return
        throw ForbiddenException()
    }

    private val logger = LoggerFactory.getLogger(SubscriptionService::class.java)

    suspend fun list(user: UserPrincipal): List<SubscriptionDto> = dbQuery {
        val visible = if (privateKind) (SourceSubscriptions.ownerId eq user.id)
            else ((SourceSubscriptions.ownerId eq user.id) or (SourceSubscriptions.isPublic eq true))
        SourceSubscriptions.selectAll()
            .where { (SourceSubscriptions.kind eq kind) and visible }
            .orderBy(SourceSubscriptions.id, SortOrder.ASC)
            .map {
                SubscriptionDto(
                    id = it[SourceSubscriptions.id].value,
                    name = it[SourceSubscriptions.name],
                    url = it[SourceSubscriptions.url],
                    isPublic = it[SourceSubscriptions.isPublic],
                    autoUpdate = it[SourceSubscriptions.autoUpdate],
                    lastUpdateAt = it[SourceSubscriptions.lastUpdateAt],
                    lastResult = it[SourceSubscriptions.lastResult],
                    owned = it[SourceSubscriptions.ownerId] == user.id,
                )
            }
    }

    suspend fun create(user: UserPrincipal, name: String, url: String, isPublic: Boolean, autoUpdate: Boolean): Long {
        if (!sourceService.canAddSource(user)) throw ForbiddenException("管理员未开放用户添加书源")
        if (url.length > 2048 || (!url.startsWith("https://") && !url.startsWith("http://"))) throw BadRequestException("订阅链接无效")
        val id = dbQuery {
            SourceSubscriptions.insertAndGetId {
                it[ownerId] = user.id
                it[SourceSubscriptions.kind] = this@SubscriptionService.kind
                it[SourceSubscriptions.name] = name.ifBlank { url }.take(255)
                it[SourceSubscriptions.url] = url.take(2048)
                it[SourceSubscriptions.isPublic] = this@SubscriptionService.kind == "source" && isPublic && user.isAdmin
                it[SourceSubscriptions.autoUpdate] = autoUpdate
                it[createdAt] = System.currentTimeMillis()
            }.value
        }
        runCatching { refresh(user, id) }
        return id
    }

    suspend fun delete(user: UserPrincipal, id: Long) {
        dbQuery {
            val row = SourceSubscriptions.selectAll().where { rowFilter(id) }.firstOrNull()
                ?: throw NotFoundException()
            requireManageable(user, row)
            SourceSubscriptionItems.deleteWhere { SourceSubscriptionItems.subscriptionId eq id }
            SourceSubscriptions.deleteWhere { SourceSubscriptions.id eq id }
        }
    }

    suspend fun update(user: UserPrincipal, id: Long, input: io.legado.web.routes.SubscriptionRequest) {
        dbQuery {
            val row = SourceSubscriptions.selectAll().where { rowFilter(id) }.firstOrNull()
                ?: throw NotFoundException()
            requireManageable(user, row)
            val url = input.url.trim()
            if (!url.startsWith("https://") && !url.startsWith("http://")) throw BadRequestException("订阅链接无效")
            if (!user.isAdmin && input.isPublic != row[SourceSubscriptions.isPublic]) throw ForbiddenException()
            SourceSubscriptions.update({ SourceSubscriptions.id eq id }) {
                it[name] = input.name.ifBlank { url }.take(255)
                it[SourceSubscriptions.url] = url.take(2048)
                it[isPublic] = this@SubscriptionService.kind == "source" && input.isPublic
                it[autoUpdate] = input.autoUpdate
            }
        }
    }

    suspend fun refresh(user: UserPrincipal, id: Long): ImportResult {
        if (!sourceService.canAddSource(user)) throw ForbiddenException("管理员未开放用户添加书源")
        val row = dbQuery { SourceSubscriptions.selectAll().where { rowFilter(id) }.firstOrNull() }
            ?: throw NotFoundException()
        requireManageable(user, row)
        val owner = dbQuery { Users.selectAll().where { Users.id eq row[SourceSubscriptions.ownerId] }.firstOrNull() } ?: throw NotFoundException()
        if (!owner[Users.enabled]) throw ForbiddenException()
        val principal = UserPrincipal(owner[Users.id].value, owner[Users.username], owner[Users.role])
        if (!sourceService.canAddSource(principal)) throw ForbiddenException("管理员未开放用户添加书源")
        // 同一订阅的两次刷新（手动 + 定时）串行执行：导入书源和重建关联不在一个事务里，交错会混出两次结果的关联
        val result = refreshLocks.computeIfAbsent(id) { Mutex() }.withLock { try {
            // 以订阅所有者身份导入
            if (importRules != null) {
                ImportResult(importRules.invoke(principal, sourceService.fetchText(row[SourceSubscriptions.url])), 0, 0, emptyList())
            } else if (ttsService != null) {
                val imported = ttsService.import(principal, sourceService.fetchText(row[SourceSubscriptions.url]))
                ImportResult(imported.added, imported.updated, 0, emptyList())
            } else {
                val text = sourceService.fetchText(row[SourceSubscriptions.url])
                val parsed = sourceService.parseAny(principal, text)
                val imported = sourceService.import(principal, parsed, row[SourceSubscriptions.isPublic])
                replaceItems(id, principal.id, parsed.map { it.bookSourceUrl })
                imported
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            dbQuery {
                SourceSubscriptions.update({ SourceSubscriptions.id eq id }) {
                    it[lastResult] = "失败: ${e.message}".take(500)
                    it[lastUpdateAt] = System.currentTimeMillis()
                }
            }
            throw e
        } }
        dbQuery {
            SourceSubscriptions.update({ SourceSubscriptions.id eq id }) {
                it[lastResult] = "新增 ${result.added}，更新 ${result.updated}，失败 ${result.failed}"
                it[lastUpdateAt] = System.currentTimeMillis()
            }
        }
        return result
    }

    /** 记下这次订阅导入的书源，列表里显示订阅名；先清掉旧关联，避免书源已不在订阅里仍显示 */
    private suspend fun replaceItems(subscriptionId: Long, ownerId: Long, urls: List<String>) {
        val wanted = urls.map { it.take(1024) }.distinct()
        val sourceIds = if (wanted.isEmpty()) emptyList() else dbQuery {
            wanted.chunked(500).flatMap { part ->
                BookSources.select(BookSources.id)
                    .where { (BookSources.ownerId eq ownerId) and (BookSources.isPublic eq false) and (BookSources.url inList part) }
                    .map { it[BookSources.id].value }
            }
        }
        dbQuery {
            // 刷新期间订阅可能已被删除：先清关联，确认订阅仍在才写入，避免留下悬空行。
            // 只重建私有书源的关联；公共副本的关联由公共同步写入，刷新不能把公共列表的订阅名清掉
            SourceSubscriptionItems.deleteWhere {
                (SourceSubscriptionItems.subscriptionId eq subscriptionId) and
                    (SourceSubscriptionItems.sourceId notInSubQuery BookSources.select(BookSources.id).where { BookSources.isPublic eq true })
            }
            val stillExists = SourceSubscriptions.selectAll().where { SourceSubscriptions.id eq subscriptionId }.any()
            val ids = if (stillExists) sourceIds.distinct() else emptyList()
            if (ids.isNotEmpty()) SourceSubscriptionItems.batchInsert(ids) { sourceId ->
                this[SourceSubscriptionItems.subscriptionId] = subscriptionId
                this[SourceSubscriptionItems.sourceId] = sourceId
            }
        }
    }

    /** 定时任务：刷新所有开启自动更新的订阅 */
    suspend fun refreshAll() {
        val subs = dbQuery {
            SourceSubscriptions.selectAll().where { (SourceSubscriptions.autoUpdate eq true) and (SourceSubscriptions.kind eq kind) }.map {
                Triple(it[SourceSubscriptions.id].value, it[SourceSubscriptions.ownerId], it[SourceSubscriptions.lastUpdateAt])
            }
        }
        val now = System.currentTimeMillis()
        for ((id, ownerId, last) in subs) {
            if (now - last < 20 * 3600_000L) continue
            val owner = dbQuery {
                Users.selectAll().where { Users.id eq ownerId }.firstOrNull()
            } ?: continue
            val principal = UserPrincipal(ownerId, owner[Users.username], owner[Users.role])
            runCatching { refresh(principal, id) }.onFailure {
                logger.warn("刷新书源订阅失败 {}: {}", id, it.message)
            }
        }
    }
}
