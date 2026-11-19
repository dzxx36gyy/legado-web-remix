package io.legado.web.service.tts

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.service.ShelfService
import io.legado.web.service.TtsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.EOFException
import java.net.ProtocolException
import java.net.SocketException
import java.util.UUID

data class ContinuousRequest(val bookId: Long, val chapter: Int, val parts: List<SpeechPart>,
    val engineId: Long? = null, val voice: String = "", val speed: Int = 10)
data class SpeechCue(val sequence: Long, val chapter: Int, val part: SpeechPart, val start: Double, val end: Double, val complete: Boolean = false)
data class SpeechStatus(val cues: List<SpeechCue>, val done: Boolean, val error: String?, val seconds: Double, val errorCode: String?)

class ContinuousSpeech(private val shelf: ShelfService, private val tts: TtsService, scope: CoroutineScope) {
    private class Session(val user: UserPrincipal, val request: ContinuousRequest, val chapters: List<Int>) {
        val cues = ArrayDeque<SpeechCue>()
        var sequence = 0L
        var seconds = 0.0
        var done = false
        var error: String? = null
        var errorCode: String? = null
        var job: Job? = null
        var opened = false
        var stopped = false
        var touched = System.nanoTime()
        var started = 0L
        var pausedAt = 0L
        var pausedNanos = 0L
    }
    private val sessions = mutableMapOf<String, Session>()

    init {
        scope.launch {
            try {
                while (true) {
                    delay(30_000)
                    synchronized(sessions) {
                        val expired = sessions.filterValues { session ->
                            synchronized(session) { (session.done || !session.opened || session.pausedAt != 0L) && System.nanoTime() - session.touched > 300_000_000_000L }
                        }
                        expired.forEach { (id, session) -> sessions.remove(id); session.job?.cancel() }
                    }
                }
            } finally { synchronized(sessions) { sessions.values.forEach { it.job?.cancel() }; sessions.clear() } }
        }
    }

    suspend fun create(user: UserPrincipal, request: ContinuousRequest): String {
        SpeechText.validate(request.parts)
        if (request.speed !in 1..50) throw BadRequestException("朗读速度不正确")
        val book = shelf.getBook(user, request.bookId)
        if (book.isAudio || book.isVideo || book.isImage) throw BadRequestException("该书籍不支持文字朗读")
        tts.requireUsable(user, request.engineId)
        val chapters = shelf.chapters(user, request.bookId).filter { !it.isVolume && it.index >= request.chapter }.map { it.index }
        if (chapters.firstOrNull() != request.chapter) throw BadRequestException("章节不存在")
        return synchronized(sessions) {
            if (sessions.size >= 64 || sessions.values.count { it.user.id == user.id } >= 4) throw BadRequestException("连续朗读会话过多，请先停止其他朗读")
            UUID.randomUUID().toString().also { sessions[it] = Session(user, request, chapters) }
        }
    }

    private fun session(user: UserPrincipal, id: String): Session = synchronized(sessions) {
        sessions[id]?.takeIf { it.user.id == user.id } ?: throw NotFoundException("朗读会话不存在")
    }

    fun status(user: UserPrincipal, id: String, after: Long): SpeechStatus {
        val session = session(user, id)
        return synchronized(session) {
            session.touched = System.nanoTime()
            SpeechStatus(session.cues.filter { it.sequence > after }, session.done, session.error, session.seconds, session.errorCode)
        }
    }

    fun pause(user: UserPrincipal, id: String, paused: Boolean) {
        val session = session(user, id)
        synchronized(session) {
            val now = System.nanoTime()
            session.touched = now
            if (paused && session.pausedAt == 0L) session.pausedAt = now
            if (!paused && session.pausedAt != 0L) { session.pausedNanos += now - session.pausedAt; session.pausedAt = 0 }
        }
    }

    fun stop(user: UserPrincipal, id: String) {
        val session = session(user, id)
        synchronized(sessions) { sessions.remove(id) }
        synchronized(session) { session.stopped = true; session.job?.cancel() }
    }

    suspend fun audio(user: UserPrincipal, id: String, write: suspend (ByteArray) -> Unit) {
        val session = session(user, id)
        val job = currentCoroutineContext().job
        synchronized(session) {
            if (session.stopped) throw NotFoundException("朗读会话不存在")
            if (session.opened) throw BadRequestException("朗读音频已打开，请重新开始朗读")
            session.opened = true
            session.job = job
        }
        val parser = Mp3Stream()
        try {
            for (chapter in session.chapters) {
                val parts = if (chapter == session.request.chapter) session.request.parts else {
                    val content = shelf.content(user, session.request.bookId, chapter, false)
                    if (content.isVolume) continue
                    if (content.video || content.incomplete) throw BadRequestException("章节内容不完整或不支持文字朗读")
                    SpeechText.chapter(content.title, content.content).also(SpeechText::validate)
                }
                for (part in parts) {
                    pace(session)
                    val start = session.seconds
                    val sequence = ++session.sequence
                    tts.stream(user, session.request.engineId, part.text, session.request.voice, session.request.speed).collect { bytes ->
                        parser.feed(bytes) { frame ->
                            pace(session)
                            synchronized(session) {
                                session.seconds += frame.seconds
                                if (session.cues.lastOrNull()?.sequence == sequence) session.cues.removeLast()
                                session.cues.addLast(SpeechCue(sequence, chapter, part, start, session.seconds))
                                while (session.cues.size > 256) session.cues.removeFirst()
                            }
                            write(frame.bytes)
                            synchronized(session) { if (session.started == 0L) session.started = System.nanoTime() }
                        }
                    }
                    parser.endPart()
                    synchronized(session) {
                        val cue = session.cues.removeLast()
                        session.cues.addLast(cue.copy(complete = true))
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            synchronized(session) {
                session.error = e.message ?: e.javaClass.simpleName
                if (e is SpeechConnectionInterrupted || e is EOFException ||
                    (e is SocketException && e !is java.net.ConnectException && e !is java.net.NoRouteToHostException) ||
                    (e is ProtocolException && e.message == "unexpected end of stream")) {
                    session.errorCode = "upstream_disconnected"
                }
            }
        }
        finally { synchronized(session) { session.done = true; session.job = null; session.touched = System.nanoTime() } }
    }

    private suspend fun pace(session: Session) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = synchronized(session) {
                val elapsed = if (session.started == 0L) 0.0 else (System.nanoTime() - session.started - session.pausedNanos) / 1e9
                session.pausedAt != 0L || session.seconds - elapsed > 20.0
            }
            if (!wait) return
            delay(100)
        }
    }
}
