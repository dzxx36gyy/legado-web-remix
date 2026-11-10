package io.legado.web.service

import io.legado.web.security.ScriptUrls
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.BookGroups
import io.legado.web.db.Bookmarks
import io.legado.web.db.Books
import io.legado.web.db.BookSources
import io.legado.web.db.ChapterContents
import io.legado.web.db.SourceSubscriptions
import io.legado.web.db.SourceSubscriptionItems
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 与阅读 App（LegadoTeam/legado）备份互通。
 *
 * App 的备份是一个 zip，根目录下是若干 JSON 文件（见 App 的 help/storage/Backup.kt），恢复时缺少的文件会跳过。
 * 这里读写其中与网页版对应的部分：
 * - bookshelf.json：书架（含阅读进度）
 * - bookGroup.json：书架分组
 * - bookSource.json：书源
 * - replaceRule.json：替换规则
 * - sourceSub.json：书源订阅
 *
 * 阅读进度：章节序号完全对应；章内位置 App 记录的是字符偏移，网页版记录的是 0-10000 的比例（音频、视频两边都是毫秒），
 * 导出时按服务器缓存的正文长度换算，导入时无法换算，定位到章节开头。
 */
class BackupService(
    private val sourceService: SourceService,
    private val shelfService: ShelfService,
    private val replaceService: ReplaceRuleService,
    private val dictService: DictRuleService,
    private val tocService: TxtTocRuleService,
    private val ttsService: TtsService,
    private val aiModels: io.legado.web.service.ai.AiModelService,
) {

    /** App 的书签（time 为主键；chapterPos 为章内字符偏移） */
    data class AppBookmark(
        val time: Long = 0,
        val bookName: String = "",
        val bookAuthor: String = "",
        val chapterIndex: Int = 0,
        val chapterPos: Int = 0,
        val chapterName: String = "",
        val bookText: String = "",
        val content: String = "",
    )

    /** App 中的分组（groupId 为 2 的幂，书籍的 group 字段是这些 id 的按位或） */
    data class AppBookGroup(
        val groupId: Long = 0,
        val groupName: String = "",
        val cover: String? = null,
        val order: Int = 0,
        val enableRefresh: Boolean = true,
        val show: Boolean = true,
        val bookSort: Int = -1,
    )

    /** App 中的订阅（type 0 为书源订阅） */
    data class AppRuleSub(
        val id: Long = 0,
        val name: String = "",
        val url: String = "",
        val type: Int = 0,
        val customOrder: Int = 0,
        val autoUpdate: Boolean = false,
        val update: Long = 0,
    )

    data class Parts(
        val books: Boolean = true,
        val sources: Boolean = true,
        val rules: Boolean = true,
        val subs: Boolean = true,
        val bookUrls: List<String>? = null,
        val sourceUrls: List<String>? = null,
        val integrations: Boolean = false,
        val backupPassword: String = "",
    )

    data class SourceSubscriptionLink(val kind: String, val subscriptionUrl: String, val bookSourceUrl: String)

    data class ImportSummary(
        var booksAdded: Int = 0,
        var booksUpdated: Int = 0,
        var booksSkipped: Int = 0,
        var groupsAdded: Int = 0,
        var sourcesAdded: Int = 0,
        var sourcesUpdated: Int = 0,
        var sourcesSkipped: Int = 0,
        var rulesAdded: Int = 0,
        /** 导入（按名称覆盖）的字典规则数 */
        var dictRulesImported: Int = 0,
        /** 导入（按 id 覆盖）的 TXT 目录规则数 */
        var tocRulesImported: Int = 0,
        var bookmarksAdded: Int = 0,
        var subsAdded: Int = 0,
        val files: MutableList<String> = mutableListOf(),
        val warnings: MutableList<String> = mutableListOf(),
        val failures: List<RestoreFailure> = emptyList(),
        val committed: Boolean = true,
        val rolledBack: Boolean = false,
    )

    companion object {
        private const val MAX_ENTRY_SIZE = 64L * 1024 * 1024
        private const val MAX_POS = 10000

        /** 导出的 id 加上这个基数，避免覆盖 App 中已有的记录（App 的 id 一般是毫秒时间戳） */
        private const val ID_BASE = 1_600_000_000_000L

        private val WANTED = setOf("bookshelf.json", "bookGroup.json", "bookSource.json", "replaceRule.json", "sourceSub.json", "bookmark.json",
            "dictRule.json", "txtTocRule.json", "webIntegrations.enc", "webSourceSubscriptionLinks.json")
    }

    // ---------------- 导出 ----------------

    suspend fun export(user: UserPrincipal, parts: Parts): ByteArray {
        val files = linkedMapOf<String, String>()
        if (parts.integrations) {
            val subscriptions = dbQuery { SourceSubscriptions.selectAll().where { SourceSubscriptions.ownerId eq user.id }.map {
                IntegrationSub(it[SourceSubscriptions.kind], it[SourceSubscriptions.name], it[SourceSubscriptions.url], it[SourceSubscriptions.autoUpdate])
            } }
            files["webIntegrations.enc"] = BackupSecrets.encode(GSON.toJson(Integrations(ttsService.backup(user), aiModels.backup(user), subscriptions)), parts.backupPassword)
        }
        val groups = shelfService.groups(user).sortedBy { it.order }.take(62)
        // 网页版分组 id -> App 分组 id（2 的幂）
        val groupBits = groups.mapIndexed { i, g -> g.id to (1L shl i) }.toMap()
        if (parts.books) {
            files["bookGroup.json"] = GSON.toJson(groups.map { g ->
                AppBookGroup(groupId = groupBits.getValue(g.id), groupName = g.name, order = g.order)
            })
            files["bookshelf.json"] = GSON.toJson(exportBooks(user, groupBits))
            files["bookmark.json"] = GSON.toJson(exportBookmarks(user))
        }
        if (parts.sources) {
            files["bookSource.json"] = GSON.toJson(sourceService.export(user, null, true))
            files["webSourceSubscriptionLinks.json"] = GSON.toJson(dbQuery {
                SourceSubscriptionItems
                    .join(SourceSubscriptions, JoinType.INNER, additionalConstraint = { SourceSubscriptionItems.subscriptionId eq SourceSubscriptions.id })
                    .join(BookSources, JoinType.INNER, additionalConstraint = { SourceSubscriptionItems.sourceId eq BookSources.id })
                    .select(SourceSubscriptions.kind, SourceSubscriptions.url, BookSources.url)
                    .where { (SourceSubscriptions.ownerId eq user.id) and (SourceSubscriptions.kind eq "source") and
                        (BookSources.ownerId eq user.id) and (BookSources.isPublic eq false) }
                    .map { SourceSubscriptionLink(it[SourceSubscriptions.kind], it[SourceSubscriptions.url], it[BookSources.url]) }
                    .distinct()
            })
        }
        if (parts.rules) {
            files["replaceRule.json"] = GSON.toJson(replaceService.list(user.id).map { it.copy(id = ID_BASE + it.id) })
            files["dictRule.json"] = GSON.toJson(dictService.list(user.id))
            files["txtTocRule.json"] = GSON.toJson(tocService.list(user.id))
        }
        if (parts.subs) {
            val subs = dbQuery {
                SourceSubscriptions.selectAll().where { (SourceSubscriptions.ownerId eq user.id) and (SourceSubscriptions.kind eq "source") }
                    .orderBy(SourceSubscriptions.id, SortOrder.ASC)
                    .mapIndexed { i, r ->
                        AppRuleSub(
                            id = ID_BASE + r[SourceSubscriptions.id].value,
                            name = r[SourceSubscriptions.name],
                            url = r[SourceSubscriptions.url],
                            type = 0,
                            customOrder = i,
                            autoUpdate = r[SourceSubscriptions.autoUpdate],
                            update = r[SourceSubscriptions.lastUpdateAt],
                        )
                    }
            }
            files["sourceSub.json"] = GSON.toJson(subs)
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, json) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private suspend fun exportBooks(user: UserPrincipal, groupBits: Map<Long, Long>): List<Book> = dbQuery {
        // 本地书籍不写入 bookshelf.json：App 中没有这些文件，恢复后会是打不开的书；网页版上传的书也不在备份范围内
        Books.selectAll().where { (Books.userId eq user.id) and (Books.inShelf eq true) and (Books.origin neq BookType.localTag) }
            .orderBy(Books.order to SortOrder.ASC)
            .map { r ->
                val bookId = r[Books.id].value
                val durIndex = r[Books.durChapterIndex]
                // 章内位置：比例 -> 字符偏移（按缓存的正文长度估算）
                val ratio = r[Books.durChapterPos]
                // 音频、视频的位置是毫秒（与 App 相同），不需要换算
                val isAudio = r[Books.type] and (BookType.audio or BookType.video) != 0
                val charPos = if (isAudio) ratio else if (ratio > 0) {
                    ChapterContents.select(ChapterContents.content)
                        .where { (ChapterContents.bookId eq bookId) and (ChapterContents.index eq durIndex) }
                        .firstOrNull()?.get(ChapterContents.content)
                        ?.let { (it.length.toLong() * ratio / MAX_POS).toInt() } ?: 0
                } else 0
                Book(
                    bookUrl = r[Books.bookUrl],
                    tocUrl = r[Books.tocUrl],
                    origin = r[Books.origin],
                    originName = r[Books.originName],
                    name = r[Books.name],
                    author = r[Books.author],
                    kind = r[Books.kind],
                    coverUrl = r[Books.coverUrl],
                    customCoverUrl = r[Books.customCoverUrl],
                    intro = r[Books.intro],
                    customIntro = r[Books.customIntro],
                    type = r[Books.type],
                    group = groupBits[r[Books.groupId]] ?: 0,
                    latestChapterTitle = r[Books.latestChapterTitle],
                    latestChapterTime = r[Books.latestChapterTime],
                    lastCheckTime = r[Books.lastCheckTime],
                    lastCheckCount = r[Books.lastCheckCount],
                    totalChapterNum = r[Books.totalChapterNum],
                    durChapterTitle = r[Books.durChapterTitle],
                    durChapterIndex = durIndex,
                    durChapterPos = charPos,
                    durChapterTime = r[Books.durChapterTime],
                    wordCount = r[Books.wordCount],
                    canUpdate = r[Books.canUpdate],
                    order = r[Books.order],
                    variable = r[Books.variable],
                    readConfig = r[Books.readConfig]?.let { GSON.fromJsonObject<Book.ReadConfig>(it).getOrNull() },
                )
            }
    }

    /** 缓存的章节正文长度（用于章内位置在比例与字符偏移之间换算），没有缓存时为 null */
    private fun cachedLength(bookId: Long, index: Int): Int? =
        ChapterContents.select(ChapterContents.content)
            .where { (ChapterContents.bookId eq bookId) and (ChapterContents.index eq index) }
            .firstOrNull()?.get(ChapterContents.content)?.length

    /** 章内位置：网页版 0-10000 比例 -> App 字符偏移（音频、视频为毫秒，不换算；没有缓存正文时为 0） */
    suspend fun ratioToCharPos(bookId: Long, type: Int, index: Int, ratio: Int): Int {
        if (type and (BookType.audio or BookType.video) != 0) return ratio
        if (ratio <= 0) return 0
        val len = dbQuery { cachedLength(bookId, index) } ?: return 0
        return (len.toLong() * ratio / MAX_POS).toInt()
    }

    /** 章内位置：App 字符偏移 -> 网页版 0-10000 比例（没有缓存正文时定位到章节开头） */
    suspend fun charPosToRatio(bookId: Long, type: Int, index: Int, charPos: Int): Int {
        if (type and (BookType.audio or BookType.video) != 0) return charPos.coerceAtLeast(0)
        if (charPos <= 0) return 0
        val len = dbQuery { cachedLength(bookId, index) }?.takeIf { it > 0 } ?: return 0
        return (charPos.toLong() * MAX_POS / len).toInt().coerceIn(0, MAX_POS)
    }

    private suspend fun exportBookmarks(user: UserPrincipal): List<AppBookmark> = dbQuery {
        val books = Books.selectAll()
            .where { (Books.userId eq user.id) and (Books.inShelf eq true) and (Books.origin neq BookType.localTag) }
            .associate { it[Books.id].value to (it[Books.name] to it[Books.author]) }
        if (books.isEmpty()) return@dbQuery emptyList()
        val usedTimes = mutableSetOf<Long>()
        Bookmarks.selectAll().where { (Bookmarks.userId eq user.id) and (Bookmarks.bookId inList books.keys) }
            .orderBy(Bookmarks.createdAt to SortOrder.ASC)
            .map { r ->
                val bookId = r[Bookmarks.bookId]
                val (name, author) = books.getValue(bookId)
                val index = r[Bookmarks.chapterIndex]
                val ratio = r[Bookmarks.chapterPos]
                // time 是 App 中的主键，同一毫秒添加的书签错开 1ms
                var time = r[Bookmarks.createdAt]
                while (!usedTimes.add(time)) time++
                AppBookmark(
                    time = time,
                    bookName = name,
                    bookAuthor = author,
                    chapterIndex = index,
                    chapterPos = cachedLength(bookId, index)?.let { (it.toLong() * ratio / MAX_POS).toInt() } ?: 0,
                    chapterName = r[Bookmarks.chapterName],
                    bookText = r[Bookmarks.bookText],
                    content = r[Bookmarks.content],
                )
            }
    }

    // ---------------- 导入 ----------------

    private suspend fun importBookmarks(user: UserPrincipal, list: List<AppBookmark>, s: ImportSummary) {
        if (list.isEmpty()) return
        var missing = 0
        dbQuery {
            val books = Books.selectAll().where { Books.userId eq user.id }
                .associate { (it[Books.name] to it[Books.author]) to it[Books.id].value }
            // 已有相同的书签（同一本书、同一章、同样的文字）时跳过，重复导入不会产生重复书签
            val existing = Bookmarks.selectAll().where { Bookmarks.userId eq user.id }
                .map { Triple(it[Bookmarks.bookId], it[Bookmarks.chapterIndex], it[Bookmarks.bookText]) }
                .toMutableSet()
            for (b in list) {
                val bookId = books[b.bookName to b.bookAuthor]
                if (bookId == null) {
                    missing++
                    continue
                }
                val key = Triple(bookId, b.chapterIndex, b.bookText)
                if (key in existing) continue
                val ratio = cachedLength(bookId, b.chapterIndex)
                    ?.takeIf { it > 0 }
                    ?.let { (b.chapterPos.toLong() * MAX_POS / it).toInt().coerceIn(0, MAX_POS) } ?: 0
                Bookmarks.insert {
                    it[Bookmarks.userId] = user.id
                    it[Bookmarks.bookId] = bookId
                    it[chapterIndex] = b.chapterIndex.coerceAtLeast(0)
                    it[chapterName] = b.chapterName.take(500)
                    it[chapterPos] = ratio
                    it[bookText] = b.bookText.take(BookmarkService.MAX_TEXT)
                    it[content] = b.content.take(BookmarkService.MAX_TEXT)
                    it[createdAt] = if (b.time > 0) b.time else System.currentTimeMillis()
                }
                existing.add(key)
                s.bookmarksAdded++
            }
        }
        if (missing > 0) s.warnings.add("有 $missing 个书签对应的书不在书架上，已跳过")
    }

    data class RestoreFailure(val file: String, val reason: String)

    private data class RestorePlan(
        val files: List<String>,
        val canAddSource: Boolean,
        val sources: List<BookSource>?,
        val sourceLinks: List<SourceSubscriptionLink>?,
        val groups: List<AppBookGroup>?,
        val books: List<Book>?,
        val bookmarks: List<AppBookmark>?,
        val rules: List<ReplaceRuleDto>?,
        val dict: List<io.legado.app.data.entities.DictRule>?,
        val toc: List<io.legado.app.data.entities.TxtTocRule>?,
        val subs: List<AppRuleSub>?,
        val integrations: Integrations?,
        val models: List<io.legado.web.service.ai.AiModelService.PreparedRestore>,
        val tts: List<TtsService.PreparedRestore>,
    )
    private val logger = org.slf4j.LoggerFactory.getLogger(BackupService::class.java)

    suspend fun import(user: UserPrincipal, zipBytes: ByteArray, parts: Parts): ImportSummary {
        var stage = "backup.zip"
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val files = try { readZip(zipBytes) } catch (e: Exception) {
                    logger.warn("Restore validation failed: category=archive, stage=backup.zip")
                    return@withContext ImportSummary(failures = listOf(RestoreFailure("backup.zip", "备份压缩包无效")), committed = false)
                }
                val failures = validate(files, parts)
                if (failures.isNotEmpty()) {
                    failures.forEach { logger.warn("Restore validation failed: stage={}", it.file) }
                    return@withContext ImportSummary(failures = failures, committed = false)
                }
                val plan = prepareRestore(user, files, parts) { stage = it }
                val changedSources = mutableSetOf<Long>()
                val summary = org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction(db = io.legado.web.db.DatabaseFactory.database) {
                    maxAttempts = 1
                    importValidated(user, plan, changedSources) { stage = it }
                }
                // 内存缓存在提交之后再清：提交前清掉的话，并发请求会在提交前把旧数据重新读进缓存
                sourceService.invalidateSearch(changedSources)
                if (plan.rules != null) replaceService.invalidate(user.id)
                summary
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不返回底层异常正文，避免数据库语句或模型配置中的密钥进入失败报告。
            logger.warn("Restore failed: category=execution, stage={}, error={}", stage, e.javaClass.simpleName)
            ImportSummary(failures = listOf(RestoreFailure(stage, "还原执行失败，所有本次数据库修改已回滚")), committed = false, rolledBack = true)
        }
    }

    private fun validate(files: Map<String, String>, parts: Parts): List<RestoreFailure> {
        if (files.isEmpty()) return listOf(RestoreFailure("backup.zip", "没有找到阅读备份文件"))
        val selected = buildList {
            if (parts.books) addAll(listOf("bookshelf.json", "bookGroup.json", "bookmark.json"))
            if (parts.sources) addAll(listOf("bookSource.json", "webSourceSubscriptionLinks.json"))
            if (parts.rules) addAll(listOf("replaceRule.json", "dictRule.json", "txtTocRule.json"))
            if (parts.subs) add("sourceSub.json")
            if (parts.integrations) add("webIntegrations.enc")
        }
        return selected.mapNotNull { name ->
            val text = files[name] ?: return@mapNotNull null
            try {
                if (name == "webIntegrations.enc") {
                    val data = GSON.fromJson(BackupSecrets.decode(text, parts.backupPassword), Integrations::class.java)
                    require(data != null)
                    data.engines.forEach { require(it.name.isNotBlank() && (it.http != null || it.provider != null)) }
                    data.models.forEach { require(it.name.isNotBlank() && it.model.isNotBlank() && it.apiKey.isNotBlank()) }
                    data.subscriptions.forEach { require(it.kind in setOf("source", "tts", "replace", "dict", "txtToc") && (it.url.startsWith("https://") || it.url.startsWith("http://"))) }
                } else {
                    val array = com.google.gson.JsonParser.parseString(text)
                    require(array.isJsonArray && array.asJsonArray.all { it.isJsonObject })
                    when (name) {
                        "bookSource.json" -> GSON.fromJsonArray<BookSource>(text).getOrThrow().filter { parts.sourceUrls == null || it.bookSourceUrl in parts.sourceUrls }.forEach { require(it.bookSourceUrl.isNotBlank()) }
                        "webSourceSubscriptionLinks.json" -> GSON.fromJsonArray<SourceSubscriptionLink>(text).getOrThrow().forEach {
                            require(it.kind == "source" && it.subscriptionUrl.isNotBlank() && it.bookSourceUrl.isNotBlank())
                        }
                        "bookshelf.json" -> GSON.fromJsonArray<Book>(text).getOrThrow().filter { parts.bookUrls == null || it.bookUrl in parts.bookUrls }.forEach { require(it.bookUrl.isNotBlank()) }
                        "bookGroup.json" -> GSON.fromJsonArray<AppBookGroup>(text).getOrThrow()
                        "bookmark.json" -> GSON.fromJsonArray<AppBookmark>(text).getOrThrow()
                        "sourceSub.json" -> GSON.fromJsonArray<AppRuleSub>(text).getOrThrow()
                        "replaceRule.json" -> GSON.fromJsonArray<ReplaceRuleDto>(text).getOrThrow().forEach { require(it.pattern.isNotBlank()); if (it.isRegex) java.util.regex.Pattern.compile(it.pattern) }
                        "dictRule.json" -> GSON.fromJsonArray<io.legado.app.data.entities.DictRule>(text).getOrThrow().forEach { require(it.name.isNotBlank() && it.urlRule.isNotBlank()) }
                        "txtTocRule.json" -> GSON.fromJsonArray<io.legado.app.data.entities.TxtTocRule>(text).getOrThrow().filter { it.rule.isNotBlank() }.forEach { java.util.regex.Pattern.compile(it.rule) }
                    }
                }
                null
            } catch (e: Exception) {
                RestoreFailure(name, if (name == "webIntegrations.enc") "备份密码错误、加密文件损坏或配置无效" else "文件格式或规则校验失败")
            }
        }
    }

    private suspend fun prepareRestore(user: UserPrincipal, files: Map<String, String>, parts: Parts, stage: (String) -> Unit): RestorePlan {
        val canAdd = sourceService.canAddSource(user)
        val books = if (parts.books) files["bookshelf.json"]?.let { GSON.fromJsonArray<Book>(it).getOrThrow() }
            ?.filter { parts.bookUrls == null || it.bookUrl in parts.bookUrls } else null
        val marks = if (parts.books) files["bookmark.json"]?.let { GSON.fromJsonArray<AppBookmark>(it).getOrThrow() }
            ?.filter { mark -> parts.bookUrls == null || books == null || books.any { it.name == mark.bookName && it.author == mark.bookAuthor } } else null
        val integrations = if (parts.integrations) files["webIntegrations.enc"]?.let {
            GSON.fromJson(BackupSecrets.decode(it, parts.backupPassword), Integrations::class.java)
        } else null
        stage("webIntegrations.enc")
        val engines = integrations?.let { ttsService.prepareRestore(user, it.engines) }
        val models = integrations?.let { aiModels.prepareRestore(user, it.models) }.orEmpty()
        return RestorePlan(
            files.keys.sorted(), canAdd,
            if (parts.sources) files["bookSource.json"]?.let { GSON.fromJsonArray<BookSource>(it).getOrThrow().filter { source -> parts.sourceUrls == null || source.bookSourceUrl in parts.sourceUrls } } else null,
            if (parts.sources) files["webSourceSubscriptionLinks.json"]?.let { GSON.fromJsonArray<SourceSubscriptionLink>(it).getOrThrow() } else null,
            if (parts.books) files["bookGroup.json"]?.let { GSON.fromJsonArray<AppBookGroup>(it).getOrThrow() } else null,
            books, marks,
            if (parts.rules) files["replaceRule.json"]?.let { GSON.fromJsonArray<ReplaceRuleDto>(it).getOrThrow() } else null,
            if (parts.rules) files["dictRule.json"]?.let { GSON.fromJsonArray<io.legado.app.data.entities.DictRule>(it).getOrThrow() } else null,
            if (parts.rules) files["txtTocRule.json"]?.let { GSON.fromJsonArray<io.legado.app.data.entities.TxtTocRule>(it).getOrThrow() } else null,
            if (parts.subs) files["sourceSub.json"]?.let { GSON.fromJsonArray<AppRuleSub>(it).getOrThrow() } else null,
            integrations, models, engines.orEmpty(),
        )
    }

    private suspend fun importValidated(user: UserPrincipal, plan: RestorePlan, changedSources: MutableSet<Long>, stage: (String) -> Unit): ImportSummary {
        val summary = ImportSummary(files = plan.files.toMutableList())

        // 书源先导入，书架里的书才能找到对应书源
        plan.sources?.let { stage("bookSource.json"); importSources(user, it, plan.canAddSource, changedSources, summary) }
        run {
            val groupMap = plan.groups?.let { stage("bookGroup.json"); importGroups(user, it, summary) } ?: emptyMap()
            plan.books?.let { stage("bookshelf.json"); importBooks(user, it, groupMap, plan.canAddSource, summary) }
            // 书签按书名、作者对应到书架上的书，所以在书架之后导入
            plan.bookmarks?.let { stage("bookmark.json"); importBookmarks(user, it, summary) }
        }
        run {
            plan.rules?.let { stage("replaceRule.json"); importRules(user, it, summary) }
            plan.dict?.let { stage("dictRule.json"); importDictRules(user, it, plan.canAddSource, summary) }
            plan.toc?.let { stage("txtTocRule.json"); importTocRules(user, it, plan.canAddSource, summary) }
        }
        plan.subs?.let { stage("sourceSub.json"); importSubs(user, it, plan.canAddSource, summary) }
        plan.integrations?.let { data ->
            stage("webIntegrations.enc")
            ttsService.applyRestore(user, plan.tts)
            aiModels.applyRestore(user, plan.models)
            if (plan.canAddSource) dbQuery {
                data.subscriptions.forEach { sub ->
                    if (sub.kind !in setOf("source", "tts", "replace", "dict", "txtToc")) throw BadRequestException("订阅类型无效")
                    val existing = SourceSubscriptions.selectAll().where { (SourceSubscriptions.ownerId eq user.id) and (SourceSubscriptions.kind eq sub.kind) and (SourceSubscriptions.url eq sub.url) }.firstOrNull()
                    if (existing == null) SourceSubscriptions.insert {
                        it[ownerId] = user.id; it[kind] = sub.kind; it[name] = sub.name.take(255); it[url] = sub.url.take(2048)
                        it[autoUpdate] = sub.autoUpdate; it[isPublic] = false; it[createdAt] = System.currentTimeMillis()
                    }
                }
            }
        }
        if (plan.canAddSource && plan.sourceLinks != null && plan.sources != null) {
            stage("webSourceSubscriptionLinks.json")
            val restoredUrls = plan.sources.map { it.bookSourceUrl }.toSet()
            dbQuery {
                val sources = BookSources.selectAll().where {
                    (BookSources.ownerId eq user.id) and (BookSources.isPublic eq false)
                }.associate { it[BookSources.url] to it[BookSources.id].value }
                val subscriptions = SourceSubscriptions.selectAll().where {
                    (SourceSubscriptions.ownerId eq user.id) and (SourceSubscriptions.kind eq "source")
                }.groupBy { it[SourceSubscriptions.kind] to it[SourceSubscriptions.url] }
                for (link in plan.sourceLinks) {
                    if (link.bookSourceUrl !in restoredUrls) continue
                    val sourceId = sources[link.bookSourceUrl] ?: continue
                    for (sub in subscriptions[link.kind to link.subscriptionUrl].orEmpty()) {
                        SourceSubscriptionItems.insertIgnore {
                            it[SourceSubscriptionItems.subscriptionId] = sub[SourceSubscriptions.id].value
                            it[SourceSubscriptionItems.sourceId] = sourceId
                        }
                    }
                }
            }
        }
        return summary
    }

    data class PreviewItem(val url: String, val name: String)
    data class IntegrationSub(val kind: String, val name: String, val url: String, val autoUpdate: Boolean)
    data class Integrations(val engines: List<TtsService.BackupEngine>, val models: List<io.legado.web.service.ai.AiModelInput>, val subscriptions: List<IntegrationSub>)
    data class Preview(val books: List<PreviewItem>, val sources: List<PreviewItem>)
    fun preview(bytes: ByteArray): Preview {
        val files = readZip(bytes)
        return Preview(
            files["bookshelf.json"]?.let { GSON.fromJsonArray<Book>(it).getOrThrow().map { b -> PreviewItem(b.bookUrl, b.name) } }.orEmpty(),
            files["bookSource.json"]?.let { GSON.fromJsonArray<BookSource>(it).getOrThrow().map { s -> PreviewItem(s.bookSourceUrl, s.bookSourceName) } }.orEmpty(),
        )
    }

    private fun readZip(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // App 的备份文件都在根目录；兼容被再次打包进一层文件夹的情况
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name !in WANTED || name in result) continue
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ENTRY_SIZE) throw BadRequestException("$name 过大")
                        buf.write(chunk, 0, n)
                    }
                    result[name] = buf.toString(Charsets.UTF_8)
                }
            }
        } catch (e: BadRequestException) {
            throw e
        } catch (e: Exception) {
            throw BadRequestException("无法读取备份文件：${e.message}")
        }
        return result
    }

    private suspend fun importSources(user: UserPrincipal, list: List<BookSource>, canAdd: Boolean, changedSources: MutableSet<Long>, s: ImportSummary) {
        if (list.isEmpty()) return
        if (!canAdd) {
            s.sourcesSkipped += list.size
            s.warnings.add("没有添加书源的权限，跳过了 ${list.size} 个书源（书架中的书会使用已有的同地址书源）")
            return
        }
        // 已有同地址的公共书源时不再重复添加，避免出现两份
        val visible = sourceService.list(user)
        val own = visible.filter { it.owned }.map { it.bookSourceUrl }.toSet()
        val public = if (user.isAdmin) emptySet() else visible.filter { !it.owned }.map { it.bookSourceUrl }.toSet()
        val (skip, toImport) = list.partition { it.bookSourceUrl in public && it.bookSourceUrl !in own }
        s.sourcesSkipped += skip.size
        if (skip.isNotEmpty()) s.warnings.add("${skip.size} 个书源与公共书源地址相同，已使用公共书源")
        if (toImport.isEmpty()) return
        val r = dbQuery { sourceService.writeImport(user.id, toImport, changedSources) }
        if (r.failed > 0) throw BadRequestException("书源导入失败")
        s.sourcesAdded += r.added
        s.sourcesUpdated += r.updated
        s.sourcesSkipped += r.failed
        r.errors.take(5).forEach { s.warnings.add("书源导入失败 $it") }
    }

    /** 返回 App 分组 id -> 网页版分组 id */
    private suspend fun importGroups(user: UserPrincipal, list: List<AppBookGroup>, s: ImportSummary): Map<Long, Long> {
        // 只导入用户自建分组（id 为正数）；负数是“全部”“本地”等内置分组
        val existing = shelfService.groups(user).associateBy { it.name }
        val map = mutableMapOf<Long, Long>()
        list.filter { it.groupId > 0 && it.groupName.isNotBlank() }.sortedBy { it.order }.forEach { g ->
            val id = existing[g.groupName]?.id ?: shelfService.saveGroup(user, null, g.groupName.take(64), g.order).id.also {
                s.groupsAdded++
            }
            map[g.groupId] = id
        }
        return map
    }

    private suspend fun importBooks(user: UserPrincipal, list: List<Book>, groupMap: Map<Long, Long>, canScript: Boolean, s: ImportSummary) {
        var local = 0
        val now = System.currentTimeMillis()
        // 书籍地址里带脚本的（会在打开时执行）：没有添加书源权限的用户不导入，见 ScriptUrls
        var scripted = 0
        dbQuery {
            val existing = Books.selectAll().where { Books.userId eq user.id }
                .associateBy { it[Books.bookUrl] }
            val inserted = HashSet<String>()
            list.forEach { b ->
                if (b.bookUrl.isBlank()) return@forEach
                // 本地书（txt/epub 等）只存在于手机上，网页版无法打开
                if (b.origin == BookType.localTag || b.origin.startsWith(BookType.webDavTag) || b.type and BookType.local != 0) {
                    local++
                    return@forEach
                }
                if (!canScript && listOf(b.bookUrl, b.tocUrl, b.coverUrl, b.customCoverUrl).any { ScriptUrls.hasScript(it) }) {
                    scripted++
                    return@forEach
                }
                // App 的 group 是按位或，取第一个能对应上的分组
                val groupId = groupMap.entries.sortedBy { it.key }.firstOrNull { b.group and it.key != 0L }?.value ?: 0L
                val type = b.type and (BookType.notShelf or BookType.updateError).inv()
                val old = existing[b.bookUrl]
                if (old == null) {
                    // 备份里同一 bookUrl 出现多次时只导入第一条（唯一索引 user_id + book_url）
                    if (!inserted.add(b.bookUrl.take(2048))) return@forEach
                    Books.insert {
                        it[userId] = user.id
                        it[createdAt] = now
                        it[inShelf] = true
                        it[Books.groupId] = groupId
                        it[bookUrl] = b.bookUrl.take(2048)
                        it[tocUrl] = b.tocUrl.take(2048)
                        it[origin] = b.origin.take(1024)
                        it[originName] = b.originName.take(255)
                        it[name] = b.name.ifBlank { "未知书名" }.take(255)
                        it[author] = b.author.take(255)
                        it[kind] = b.kind
                        it[coverUrl] = b.coverUrl
                        it[customCoverUrl] = b.customCoverUrl?.takeIf { u -> u.startsWith("http") }
                        it[intro] = b.intro
                        it[customIntro] = b.customIntro
                        it[Books.type] = type
                        it[latestChapterTitle] = b.latestChapterTitle
                        it[latestChapterTime] = b.latestChapterTime
                        it[lastCheckTime] = b.lastCheckTime
                        it[lastCheckCount] = 0
                        // 目录在第一次打开时从书源获取
                        it[totalChapterNum] = b.totalChapterNum
                        it[durChapterTitle] = b.durChapterTitle
                        it[durChapterIndex] = b.durChapterIndex.coerceAtLeast(0)
                        it[durChapterPos] = if (b.type and (BookType.audio or BookType.video) != 0) b.durChapterPos.coerceAtLeast(0) else 0
                        it[durChapterTime] = b.durChapterTime.coerceAtMost(now)
                        it[wordCount] = b.wordCount?.take(64)
                        it[canUpdate] = b.canUpdate
                        it[order] = b.order
                        it[variable] = b.variable
                        b.readConfig?.let { c -> it[readConfig] = GSON.toJson(c) }
                    }
                    s.booksAdded++
                } else {
                    val oldId = old[Books.id].value
                    // 进度以最后阅读的为准
                    val newer = b.durChapterTime > old[Books.durChapterTime]
                    Books.update({ Books.id eq oldId }) {
                        it[inShelf] = true
                        if (groupId != 0L) it[Books.groupId] = groupId
                        if (newer) {
                            it[durChapterTitle] = b.durChapterTitle
                            it[durChapterIndex] = b.durChapterIndex.coerceAtLeast(0)
                            it[durChapterPos] = if (b.type and (BookType.audio or BookType.video) != 0) b.durChapterPos.coerceAtLeast(0) else 0
                            it[durChapterTime] = b.durChapterTime.coerceAtMost(now)
                        }
                        if (!b.customCoverUrl.isNullOrBlank() && b.customCoverUrl!!.startsWith("http")) {
                            it[customCoverUrl] = b.customCoverUrl
                        }
                        if (!b.customIntro.isNullOrBlank()) it[customIntro] = b.customIntro
                        if (newer) b.readConfig?.let { c -> it[readConfig] = GSON.toJson(c) }
                    }
                    if (newer) s.booksUpdated++ else s.booksSkipped++
                }
            }
        }
        if (local > 0) s.warnings.add("跳过了 $local 本本地书籍（本地文件只在手机上，网页版无法打开）")
        if (scripted > 0) s.warnings.add("跳过了 $scripted 本地址中含有脚本的书籍（需要“添加书源”的权限）")
    }

    private suspend fun importDictRules(user: UserPrincipal, list: List<io.legado.app.data.entities.DictRule>, canEdit: Boolean, s: ImportSummary) {
        if (!canEdit) {
            s.warnings.add("字典规则需要“添加书源”的权限才能导入，已跳过")
            return
        }
        val valid = list.filter { it.name.isNotBlank() && it.urlRule.isNotBlank() }
        if (valid.isEmpty()) throw BadRequestException("没有可导入的字典规则")
        s.dictRulesImported = dictService.applyRestore(user.id, valid)
    }

    /** 带“js 处理”的目录规则需要“添加书源”的权限，没有权限时只导入不带 js 的规则 */
    private suspend fun importTocRules(user: UserPrincipal, list: List<io.legado.app.data.entities.TxtTocRule>, canEdit: Boolean, s: ImportSummary) {
        val allowed = if (canEdit) list else list.filter { it.replacement.isBlank() }
        if (allowed.size < list.size) s.warnings.add("有 ${list.size - allowed.size} 条带 js 处理的目录规则需要“添加书源”的权限，已跳过")
        if (allowed.isEmpty()) return
        val valid = allowed.filter { it.rule.isNotBlank() }
        if (valid.isEmpty()) throw BadRequestException("没有可导入的目录规则")
        s.tocRulesImported = tocService.applyRestore(user.id, valid)
    }

    private suspend fun importRules(user: UserPrincipal, list: List<ReplaceRuleDto>, s: ImportSummary) {
        // 已有完全相同的规则时跳过，重复导入不会产生重复规则
        s.rulesAdded += dbQuery { replaceService.writeRestore(user.id, list) }
    }

    private suspend fun importSubs(user: UserPrincipal, list: List<AppRuleSub>, canAdd: Boolean, s: ImportSummary) {
        // 只导入书源订阅（type 0），RSS 订阅网页版不支持
        val book = list.filter { it.type == 0 && it.url.startsWith("http") }
        if (book.size < list.size) s.warnings.add("跳过了 ${list.size - book.size} 个非书源订阅（如 RSS 订阅）")
        if (book.isEmpty()) return
        if (!canAdd) {
            s.warnings.add("没有添加书源的权限，跳过了 ${book.size} 个书源订阅")
            return
        }
        dbQuery {
            val existing = SourceSubscriptions.selectAll().where { (SourceSubscriptions.ownerId eq user.id) and (SourceSubscriptions.kind eq "source") }
                .map { it[SourceSubscriptions.url] }.toMutableSet()
            book.forEach { sub ->
                if (sub.url in existing) return@forEach
                SourceSubscriptions.insertAndGetId {
                    it[ownerId] = user.id
                    it[name] = sub.name.ifBlank { sub.url }.take(255)
                    it[url] = sub.url.take(2048)
                    it[autoUpdate] = sub.autoUpdate
                    it[createdAt] = System.currentTimeMillis()
                }
                existing.add(sub.url)
                s.subsAdded++
            }
        }
    }
}
