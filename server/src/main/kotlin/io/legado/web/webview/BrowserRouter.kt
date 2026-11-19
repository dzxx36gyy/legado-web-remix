package io.legado.web.webview

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.engine.WebViewProvider
import io.legado.app.help.engine.EngineContext
import io.legado.web.db.UserPrefs
import io.legado.web.db.dbBlocking
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.legado.web.auth.BadRequestException
import io.legado.web.service.SettingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class BrowserRouter(private val settings: SettingService, private val contexts: BrowserContexts) : WebViewProvider, AutoCloseable {
    data class Destination(val address: String, val cloak: Boolean)
    private data class Backend(val web: CdpWebViewProvider, val remote: RemoteBrowserManager)
    private val backends = ConcurrentHashMap<Destination, Backend>()
    private val cloakQueue = BrowserTaskQueue()
    private val sitesLock = Mutex()
    var confirmCloak: suspend (String) -> Boolean = { false }

    companion object {
        fun domains(text: String): List<String> = text.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty).map { rule ->
            val wildcard = rule.startsWith("*.")
            val host = runCatching { IDN.toASCII(rule.removePrefix("*.").trimEnd('.')).lowercase() }
                .getOrElse { throw BadRequestException("Cloak 站点格式不正确：$rule") }
            if (host.length > 253 || host.split('.').any { !Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$").matches(it) }) {
                throw BadRequestException("Cloak 站点只填写域名，例如 example.com 或 *.example.com")
            }
            (if (wildcard) "*." else "") + host
        }.distinct()
    }

    fun available() = settings.get(SettingService.BROWSER_CDP).isNotBlank() || settings.get(SettingService.CLOAK_BROWSER_CDP).isNotBlank()

    fun personalSites(userId: Long): String = dbBlocking {
        UserPrefs.selectAll().where { (UserPrefs.userId eq userId) and (UserPrefs.key eq SettingService.CLOAK_BROWSER_SITES) }
            .firstOrNull()?.get(UserPrefs.value).orEmpty()
    }

    suspend fun savePersonalSites(userId: Long, text: String) {
        val normalized = domains(text).joinToString("\n")
        sitesLock.withLock {
            dbQuery { UserPrefs.upsert {
                it[UserPrefs.userId] = userId
                it[UserPrefs.key] = SettingService.CLOAK_BROWSER_SITES
                it[UserPrefs.value] = normalized
            } }
        }
    }

    suspend fun remember(url: String, userId: Long, global: Boolean) = sitesLock.withLock {
        val host = url.toHttpUrlOrNull()?.host ?: throw BadRequestException("网站地址无效")
        val existing = if (global) settings.get(SettingService.CLOAK_BROWSER_SITES) else personalSites(userId)
        val text = (domains(existing) + host).distinct().joinToString("\n")
        if (global) settings.update(mapOf(SettingService.CLOAK_BROWSER_SITES to text))
        else dbQuery { UserPrefs.upsert {
            it[UserPrefs.userId] = userId
            it[UserPrefs.key] = SettingService.CLOAK_BROWSER_SITES
            it[UserPrefs.value] = text
        } }
    }

    fun destination(url: String?, userId: Long = EngineContext.currentUserId): Destination {
        val host = url?.substringBefore(",{")?.toHttpUrlOrNull()?.host?.trimEnd('.')
        val rules = domains(settings.get(SettingService.CLOAK_BROWSER_SITES)) + if (userId > 0) domains(personalSites(userId)) else emptyList()
        val cloak = host != null && rules.any { rule ->
            if (rule.startsWith("*.")) host.endsWith("." + rule.substring(2)) else host == rule
        }
        val key = if (cloak) SettingService.CLOAK_BROWSER_CDP else SettingService.BROWSER_CDP
        val address = settings.get(key).trim().ifEmpty {
            throw NoStackTraceException(if (cloak) "该站点指定使用 Cloak，但没有设置 Cloak 浏览器地址" else "没有设置默认浏览器地址")
        }
        return Destination(address, cloak)
    }

    private fun backend(target: Destination) = backends.computeIfAbsent(target) {
        val connection = BrowserConnection({ target.address }, contexts)
        Backend(CdpWebViewProvider({ target.address }, contexts, connection, usingCloak = target.cloak), RemoteBrowserManager({ target.address }, contexts,
            if (target.cloak) cloakQueue else BrowserTaskQueue(), connection))
    }

    fun remote(url: String, userId: Long = EngineContext.currentUserId): RemoteBrowserManager = backend(destination(url, userId)).remote
    fun canSwitch(url: String, userId: Long = EngineContext.currentUserId) = !destination(url, userId).cloak && settings.get(SettingService.CLOAK_BROWSER_CDP).isNotBlank()

    override suspend fun load(request: WebViewProvider.Request): WebViewProvider.Result {
        var target = destination(request.url)
        if (!target.cloak) {
            try {
                return backend(target).web.load(request)
            } catch (e: BrowserChallengeException) {
                val url = e.url
                if (!canSwitch(url) || !confirmCloak(url)) throw e
                target = Destination(settings.get(SettingService.CLOAK_BROWSER_CDP).trim(), true)
            }
        }
        val ctx = coroutineContext
        var acquired = false
        try {
            withContext(Dispatchers.IO) { acquired = cloakQueue.acquire(UUID.randomUUID().toString()) { !ctx.isActive } }
            if (!acquired) throw kotlinx.coroutines.CancellationException("浏览器任务已取消")
            ctx.ensureActive()
            return backend(target).web.load(request)
        } finally {
            if (acquired) cloakQueue.release()
        }
    }

    override fun close() {
        backends.values.forEach { it.web.close(); it.remote.close() }
    }
}
