package io.legado.web.service

import io.legado.app.model.webBook.WebBook
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.engine.EngineContext
import io.legado.web.auth.UserPrincipal
import io.legado.web.engine.runEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 书源校验：依次检查搜索 → 详情 → 目录 → 正文，结果只返回给前端，不修改书源的分组
 * （有问题的书源由用户在校验弹窗里直接删除）。只记录响应时间用于排序。
 */
class SourceCheckService(
    private val sourceService: SourceService,
    private val settings: SettingService,
) {
    data class CheckResult(
        val id: Long,
        val name: String,
        val ok: Boolean,
        /** 失败在哪一步：搜索失效、详情失效、目录失效、正文失效、校验超时 */
        val stage: String?,
        val error: String?,
        /** 耗时（毫秒） */
        val time: Long,
        /** 当前用户能否删除这个书源（自己的书源，或管理员） */
        val editable: Boolean,
        val errorType: String?,
    )

    private class CheckFailure(val group: String, message: String, cause: Throwable? = null) : Exception(message, cause)

    data class CheckProgress(val id: Long, val name: String, val type: String, val stage: String, val elapsed: Long)

    companion object {
        /** 单个书源的校验超时 */
        const val TIMEOUT = 90_000L
        const val DEFAULT_KEYWORD = "牧神记"
    }

    /**
     * 校验 [ids] 中的书源（只校验当前用户可用的），每校验完一个通过 [emit] 推送结果。
     * [keyword] 为空时用书源自己的校验关键字（ruleSearch.checkKeyWord），再没有就用“牧神记”
     */
    suspend fun check(
        user: UserPrincipal,
        ids: List<Long>,
        keyword: String?,
        onProgress: suspend (CheckProgress) -> Unit = {},
        emit: suspend (done: Int, total: Int, result: CheckResult) -> Unit,
    ) = coroutineScope {
        val visible = sourceService.list(user).map { it.id }.toSet()
        val targets = ids.filter { it in visible }.distinct()
        val total = targets.size
        if (total == 0) return@coroutineScope
        val semaphore = Semaphore(settings.getInt(SettingService.SEARCH_CONCURRENCY).coerceIn(1, 8))
        val results = Channel<CheckResult>(Channel.UNLIMITED)
        val output = Mutex()
        val job = launch {
            targets.forEach { id ->
                launch { semaphore.withPermit { results.send(checkOne(user, id, keyword) { progress ->
                    output.withLock { onProgress(progress) }
                }) } }
            }
        }
        job.invokeOnCompletion { results.close() }
        var done = 0
        for (r in results) output.withLock { emit(++done, total, r) }
    }

    private suspend fun checkOne(user: UserPrincipal, id: Long, keyword: String?, onProgress: suspend (CheckProgress) -> Unit): CheckResult {
        val stored = sourceService.get(user, id)
        val source = stored.source
        val start = System.currentTimeMillis()
        var stage = "search"
        onProgress(CheckProgress(id, source.bookSourceName, "started", stage, 0))
        var group: String? = null
        var error: String? = null
        var errorType: String? = null
        val context = EngineContext(user.id).apply {
            checkResponse = { response ->
                val blocked = io.legado.web.webview.ChallengeSolver.isChallenge(response.raw, response.body)
                if (blocked || response.code() in 400..599) throw SourceCheckHttpException(response.code(), blocked)
            }
        }
        try {
            runEngine(user.id, TIMEOUT, ctx = context) {
                if (source.searchUrl.isNullOrBlank()) {
                    // 只有发现的书源没有可校验的搜索，跳过
                    if (source.exploreUrl.isNullOrBlank()) throw CheckFailure("搜索失效", "书源没有搜索和发现地址")
                    error = "书源没有搜索，跳过校验"
                    return@runEngine
                }
                val key = keyword?.trim()?.takeIf { it.isNotEmpty() } ?: source.getCheckKeyword(DEFAULT_KEYWORD)
                onProgress(CheckProgress(id, source.bookSourceName, "stage", stage, System.currentTimeMillis() - start))
                val books = try {
                    WebBook.searchBookAwait(source, key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CheckFailure("搜索失效", e.message ?: e.javaClass.simpleName, e)
                }
                val first = books.firstOrNull() ?: throw CheckFailure("搜索失效", "搜索“$key”没有结果", ContentEmptyException("搜索无结果"))
                var book = first.toBook()
                stage = "bookInfo"
                onProgress(CheckProgress(id, source.bookSourceName, "stage", stage, System.currentTimeMillis() - start))
                try {
                    if (book.tocUrl.isBlank()) book = WebBook.getBookInfoAwait(source, book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CheckFailure("详情失效", e.message ?: e.javaClass.simpleName, e)
                }
                stage = "toc"
                onProgress(CheckProgress(id, source.bookSourceName, "stage", stage, System.currentTimeMillis() - start))
                val chapters = WebBook.getChapterListAwait(source, book).getOrElse {
                    if (it is CancellationException) throw it
                    throw CheckFailure("目录失效", it.message ?: it.javaClass.simpleName, it)
                }
                val chapter = chapters.firstOrNull { !it.isVolume } ?: throw CheckFailure("目录失效", "目录为空", TocEmptyException("目录为空"))
                stage = "content"
                onProgress(CheckProgress(id, source.bookSourceName, "stage", stage, System.currentTimeMillis() - start))
                val content = try {
                    WebBook.getContentAwait(source, book, chapter, needSave = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CheckFailure("正文失效", e.message ?: e.javaClass.simpleName, e)
                }
                if (content.isBlank()) throw CheckFailure("正文失效", "正文为空", ContentEmptyException("正文为空"))
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            group = "校验超时"
            error = "${stage} 阶段超过 ${TIMEOUT / 1000} 秒没有完成，请检查网站响应和代理后重试"
            errorType = "timeout"
        } catch (e: CheckFailure) {
            group = e.group
            errorType = SourceCheckErrors.classify(e)
            error = SourceCheckErrors.reason(errorType, e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            group = "搜索失效"
            error = e.message ?: e.javaClass.simpleName
            errorType = SourceCheckErrors.classify(e)
        }
        val time = System.currentTimeMillis() - start
        val editable = runCatching { sourceService.recordCheckTime(user, id, time) }.getOrDefault(false)
        return CheckResult(id, source.bookSourceName, group == null, group, error?.lineSequence()?.firstOrNull()?.take(300), time, editable, errorType)
    }
}
