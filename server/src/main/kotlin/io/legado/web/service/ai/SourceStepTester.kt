package io.legado.web.service.ai

import io.legado.app.data.entities.BookSource
import io.legado.app.help.engine.EngineContext
import io.legado.app.model.webBook.WebBook
import io.legado.web.auth.UserPrincipal
import io.legado.web.engine.runEngine
import io.legado.web.service.SourceCheckHttpException
import io.legado.web.service.SourceCheckService
import io.legado.web.service.SourceCheckErrors
import io.legado.web.webview.ChallengeSolver
import kotlinx.coroutines.CancellationException

class SourceStepTester {
    suspend fun check(user: UserPrincipal, source: BookSource, keyword: String?): RepairEvidence {
        var stage = RepairStage.search
        var page = ""
        val requests = mutableListOf<RepairHttpEvidence>()
        val hits = linkedMapOf<String, Int>()
        val context = EngineContext(user.id).apply {
            suppressUi = true
            checkResponse = { response ->
                page = response.body.orEmpty()
                val safeUrl = response.raw.request.url.newBuilder().query(null).fragment(null).username("").password("").build().toString()
                if (requests.size < 12) requests.add(RepairHttpEvidence(stage, response.raw.request.method,
                    safeUrl, response.code(), AiRepairPayload.excerpt(page).take(2000), response.encoding?.httpCharset, response.encoding))
                val blocked = ChallengeSolver.isChallenge(response.raw, response.body)
                if (blocked || response.code() in 400..599) throw SourceCheckHttpException(response.code(), blocked)
            }
        }
        return try {
            runEngine(user.id, SourceCheckService.TIMEOUT, ctx = context) {
                if (source.searchUrl.isNullOrBlank()) throw IllegalArgumentException("书源没有搜索地址，无法修复")
                val key = keyword?.trim()?.takeIf { it.isNotEmpty() } ?: source.getCheckKeyword(SourceCheckService.DEFAULT_KEYWORD)
                val first = WebBook.searchBookAwait(source, key).firstOrNull()
                    ?: throw IllegalArgumentException("搜索“$key”没有结果")
                var book = first.toBook()
                hits["search.book"] = 1
                hits["search.name"] = if (book.name.isBlank()) 0 else 1
                stage = RepairStage.bookInfo
                page = book.infoHtml.orEmpty()
                book = WebBook.getBookInfoAwait(source, book)
                hits["bookInfo.name"] = if (book.name.isBlank()) 0 else 1
                hits["bookInfo.tocUrl"] = if (book.tocUrl.isBlank()) 0 else 1
                stage = RepairStage.toc
                page = book.tocHtml.orEmpty()
                val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
                hits["toc.chapters"] = chapters.size
                val chapter = chapters.firstOrNull { !it.isVolume } ?: throw IllegalArgumentException("目录为空")
                stage = RepairStage.content
                page = if (chapter.url == book.bookUrl) book.tocHtml.orEmpty() else ""
                if (WebBook.getContentAwait(source, book, chapter, needSave = false).isBlank()) throw IllegalArgumentException("正文为空")
                hits["content.nonEmpty"] = 1
                RepairEvidence(null, "", "", requests.toList(), hits = hits.toMap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SourceCheckErrors.classify(e) == "blocked") throw RepairChallengeException()
            RepairEvidence(stage, e.stackTraceToString(), page, requests.toList(), e.javaClass.simpleName, hits.toMap())
        }
    }
}

class RepairChallengeException : Exception("网站需要验证，已停止 AI 修复，请完成验证后重试")
