package io.legado.web.service.ai

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.engine.EngineContext
import io.legado.app.model.webBook.WebBook
import io.legado.web.auth.UserPrincipal
import io.legado.web.engine.runEngine
import io.legado.web.service.SourceCheckHttpException
import io.legado.web.service.SourceCheckService
import io.legado.web.service.SourceCheckErrors
import io.legado.web.webview.ChallengeSolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl

class SourceStepTester {
    internal class ChapterInput(private val book: Book, private val chapter: BookChapter) {
        suspend fun read(source: BookSource): String = WebBook.getContentAwait(
            source, book.copy().also { it.tocHtml = book.tocHtml }, chapter.copy(), needSave = false,
        )
    }

    internal data class Check(val evidence: RepairEvidence, val chapter: ChapterInput?)

    suspend fun check(user: UserPrincipal, source: BookSource, keyword: String?, onStage: suspend (RepairStage) -> Unit = {}): RepairEvidence =
        inspect(user, source, keyword, null, { _, _, _ -> }, onStage).evidence

    internal suspend fun inspect(
        user: UserPrincipal, source: BookSource, keyword: String?, input: ChapterInput?,
        activity: suspend (RepairStage, EngineContext.RequestActivity, Int?) -> Unit,
        onStage: suspend (RepairStage) -> Unit,
        exploreUrl: String? = null,
    ): Check {
        var stage = when {
            exploreUrl != null -> RepairStage.explore
            input != null -> RepairStage.content
            else -> RepairStage.search
        }
        var chapterInput = input
        var page = ""
        var hasRequestState = false
        var searchLandedOnHome = false
        val requests = mutableListOf<RepairHttpEvidence>()
        val hits = linkedMapOf<String, Int>()
        val context = EngineContext(user.id).apply {
            suppressUi = true
            onRequestActivity = { phase, status ->
                if (phase == EngineContext.RequestActivity.browser || phase == EngineContext.RequestActivity.verification) hasRequestState = true
                activity(stage, phase, status)
            }
            checkResponse = { response ->
                val request = response.raw.networkResponse?.request ?: response.raw.request
                if (request.method != "GET" || !request.header("Cookie").isNullOrBlank() || response.raw.header("Set-Cookie") != null || response.raw.priorResponse != null) hasRequestState = true
                page = response.body.orEmpty()
                val redirects = generateSequence(response.raw.priorResponse) { it.priorResponse }
                    .filter { it.code in setOf(301, 302, 303, 307, 308) }.toList().asReversed()
                val requested = redirects.firstOrNull()?.request?.url
                val final = response.raw.request.url
                if (requests.size == 12) requests.removeAt(requests.lastIndex)
                requests.add(RepairHttpEvidence(stage, response.raw.request.method,
                    final.forEvidence(), response.code(), AiRepairPayload.excerpt(page).take(2000), response.encoding?.httpCharset,
                    response.encoding, requested?.forEvidence(), redirects.map { it.code }))
                val blocked = ChallengeSolver.isChallenge(response.raw, response.body)
                if (blocked || response.code() in 400..599) throw SourceCheckHttpException(response.code(), blocked)
                if (stage in setOf(RepairStage.search, RepairStage.explore) && source.loginCheckJs.isNullOrBlank() && response.isSuccessful() &&
                    requested != null && requested.encodedPath != "/" && final.encodedPath == "/" && final.query == null) {
                    searchLandedOnHome = true
                    throw ContentEmptyException(landingReason(stage))
                }
            }
        }
        val evidence = try {
            runEngine(user.id, SourceCheckService.TIMEOUT, ctx = context) {
                onStage(stage)
                if (exploreUrl != null) {
                    val books = WebBook.exploreBookAwait(source, exploreUrl)
                    hits["explore.book"] = books.size
                    if (books.isEmpty()) throw ContentEmptyException("发现分类没有结果")
                    return@runEngine RepairEvidence(null, "", "", requests.toList(), hits = hits.toMap())
                }
                if (input != null) {
                    if (input.read(source).isBlank()) throw ContentEmptyException("正文为空")
                    hits["content.nonEmpty"] = 1
                    return@runEngine RepairEvidence(null, "", "", requests.toList(), hits = hits.toMap())
                }
                if (source.searchUrl.isNullOrBlank()) throw IllegalArgumentException("书源没有搜索地址，无法修复")
                val key = keyword?.trim()?.takeIf { it.isNotEmpty() } ?: source.getCheckKeyword(SourceCheckService.DEFAULT_KEYWORD)
                val books = WebBook.searchBookAwait(source, key)
                hits["search.book"] = books.size
                val first = books.firstOrNull()
                    ?: throw ContentEmptyException("搜索“$key”没有结果")
                var book = first.toBook()
                hits["search.name"] = if (book.name.isBlank()) 0 else 1
                stage = RepairStage.bookInfo
                onStage(stage)
                page = book.infoHtml.orEmpty()
                book = WebBook.getBookInfoAwait(source, book)
                hits["bookInfo.name"] = if (book.name.isBlank()) 0 else 1
                hits["bookInfo.tocUrl"] = if (book.tocUrl.isBlank()) 0 else 1
                stage = RepairStage.toc
                onStage(stage)
                page = book.tocHtml.orEmpty()
                val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
                hits["toc.chapters"] = chapters.size
                val chapter = chapters.firstOrNull { !it.isVolume } ?: throw TocEmptyException("目录为空")
                stage = RepairStage.content
                onStage(stage)
                page = if (chapter.url == book.bookUrl) book.tocHtml.orEmpty() else ""
                if (chapter.url != book.bookUrl && book.variable.isNullOrBlank() && chapter.variable.isNullOrBlank() && !urlOptions.containsMatchIn(chapter.url)) {
                    chapterInput = ChapterInput(book.copy(), chapter.copy())
                }
                if (WebBook.getContentAwait(source, book, chapter, needSave = false).isBlank()) throw ContentEmptyException("正文为空")
                hits["content.nonEmpty"] = 1
                RepairEvidence(null, "", "", requests.toList(), hits = hits.toMap())
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            RepairEvidence(stage, "校验操作超时（单源执行限时 ${SourceCheckService.TIMEOUT / 1000} 秒），请检查网站响应和代理后重试", page, requests.toList(), "timeout", hits.toMap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val type = if (searchLandedOnHome) "search-landing" else SourceCheckErrors.classify(e)
            val detail = if (searchLandedOnHome) landingReason(stage)
                else e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            RepairEvidence(stage, AiRepairPayload.redact(SourceCheckErrors.reason(type, detail)), page, requests.toList(), type, hits.toMap())
        }
        return Check(evidence, chapterInput.takeUnless { hasRequestState })
    }

    internal fun canReuseChapter(source: BookSource): Boolean {
        val stateful = Regex("(?i)@(?:web)?js:|</?js>|^\\s*<|\\{\\{|@get:|@put:|javascript:|webview")
        if (listOf(source.jsLib, source.loginUrl, source.loginUi, source.loginCheckJs, source.ruleToc?.formatJs,
                source.ruleToc?.preUpdateJs, source.ruleContent?.webJs, source.ruleContent?.callBackJs,
                source.ruleContent?.payAction, source.ruleContent?.contentBatch, source.ruleContent?.imageDecode).any { !it.isNullOrBlank() }) return false
        val json = io.legado.app.utils.GSON.toJsonTree(source).asJsonObject
        val search = source.searchUrl.orEmpty().replace("{{key}}", "")
        return !urlOptions.containsMatchIn(search) && !stateful.containsMatchIn(search) &&
            !stateful.containsMatchIn(source.header.orEmpty()) && RepairStage.entries.all { stage ->
            json.getAsJsonObject(stage.rule)?.entrySet()?.none { (_, value) ->
                value.isJsonPrimitive && value.asJsonPrimitive.isString && stateful.containsMatchIn(value.asString)
            } != false
        }
    }

    private val urlOptions = Regex(",\\s*\\{")

    private fun landingReason(stage: RepairStage): String = if (stage == RepairStage.explore)
        "发现请求跳转到站点首页，请检查发现分类。" else "搜索请求跳转到站点首页，请检查搜索入口。"
}

private fun HttpUrl.forEvidence(): String = AiRepairPayload.redact(
    newBuilder().query(null).fragment(null).username("").password("").build().toString(),
)

class RepairChallengeException : Exception("网站需要验证，已停止 AI 修复，请完成验证后重试")
