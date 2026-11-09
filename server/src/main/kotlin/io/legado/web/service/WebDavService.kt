package io.legado.web.service

import io.legado.app.help.engine.Engine
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.normalizeFileName
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.Books
import io.legado.web.db.UserPrefs
import io.legado.web.db.dbQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WebDAV 备份，与阅读 App 相同的目录和文件名：
 * 备份放在 “WebDAV 地址/目录/” 下（目录默认 legado），文件名 backup2026-01-01[-设备名].zip。
 * 同一个 WebDAV 目录里 App 和网页版的备份可以互相恢复。
 */
class WebDavService(
    private val backupService: BackupService,
    private val userService: UserService,
) {
    private val logger = LoggerFactory.getLogger(WebDavService::class.java)

    /** 保存在 user_prefs（key 以 _ 开头，普通偏好接口不可读写） */
    private data class StoredConfig(
        val url: String = DEFAULT_URL,
        val account: String = "",
        /** AES-GCM 加密后的密码 */
        val password: String = "",
        val dir: String = "legado",
        val deviceName: String = "",
        val autoBackup: Boolean = false,
        val syncProgress: Boolean = false,
        val lastBackup: Long = 0,
        val lastError: String? = null,
    )

    /** 返回给前端的配置（不含密码） */
    data class ConfigDto(
        val url: String,
        val account: String,
        val hasPassword: Boolean,
        val dir: String,
        val deviceName: String,
        val autoBackup: Boolean,
        val syncProgress: Boolean,
        val lastBackup: Long,
        val lastError: String?,
    )

    data class ConfigInput(
        val url: String = DEFAULT_URL,
        val account: String = "",
        /** 为空表示不修改原密码 */
        val password: String? = null,
        val dir: String = "legado",
        val deviceName: String = "",
        val autoBackup: Boolean = false,
        val syncProgress: Boolean = false,
    )

    /** App 的 bookProgress 目录下的进度文件（durChapterPos 为章内字符偏移，音频、视频为毫秒） */
    data class AppBookProgress(
        val name: String = "",
        val author: String = "",
        val durChapterIndex: Int = 0,
        val durChapterPos: Int = 0,
        val durChapterTime: Long = 0,
        val durChapterTitle: String? = null,
    )

    /** 云端进度（pos 已换算为网页版的位置） */
    data class RemoteProgress(val index: Int, val pos: Int, val title: String?, val time: Long)

    data class BackupResult(val name: String, val size: Int)

    companion object {
        const val DEFAULT_URL = "https://dav.jianguoyun.com/dav/"
        private const val KEY = "_webdav"
        private const val MAX_BACKUP = 100L * 1024 * 1024
        private const val DAY = 24L * 3600 * 1000
        private const val MAX_PROGRESS = 64L * 1024
        private const val PROGRESS_DIR = "bookProgress/"
        /** 翻页时进度保存很频繁，停止变化一段时间后再上传 */
        private const val UPLOAD_DELAY = 10_000L

        /** 与 App 的 AppWebDav.getProgressFileName 相同：normalizeFileName 后再转义 URL 保留字符 */
        fun progressFileName(name: String, author: String): String =
            replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"

        /** 与 App 的 UrlUtil.replaceReservedChar 相同（% 必须最先替换） */
        private fun replaceReservedChar(text: String): String = text
            .replace("%", "%25").replace(" ", "%20").replace("\"", "%22").replace("#", "%23")
            .replace("&", "%26").replace("(", "%28").replace(")", "%29").replace("+", "%2B")
            .replace(",", "%2C").replace("/", "%2F").replace(":", "%3A").replace(";", "%3B")
            .replace("<", "%3C").replace("=", "%3D").replace(">", "%3E").replace("?", "%3F")
            .replace("@", "%40").replace("\\", "%5C").replace("|", "%7C")
    }

    // ---------------- 配置 ----------------

    private suspend fun load(userId: Long): StoredConfig? = dbQuery {
        UserPrefs.selectAll().where { (UserPrefs.userId eq userId) and (UserPrefs.key eq KEY) }
            .firstOrNull()?.get(UserPrefs.value)
    }?.let { GSON.fromJsonObject<StoredConfig>(it).getOrNull() }

    private suspend fun store(userId: Long, c: StoredConfig) {
        val json = GSON.toJson(c)
        dbQuery {
            UserPrefs.upsert {
                it[UserPrefs.userId] = userId
                it[key] = KEY
                it[value] = json
            }
        }
    }

    private fun StoredConfig.toDto() =
        ConfigDto(url, account, password.isNotEmpty(), dir, deviceName, autoBackup, syncProgress, lastBackup, lastError)

    private suspend fun storeResult(userId: Long, lastBackup: Long?, error: String?) = dbQuery {
        val row = UserPrefs.selectAll().where { (UserPrefs.userId eq userId) and (UserPrefs.key eq KEY) }.firstOrNull()
            ?: return@dbQuery
        val current = GSON.fromJsonObject<StoredConfig>(row[UserPrefs.value]).getOrThrow()
        UserPrefs.update({ (UserPrefs.userId eq userId) and (UserPrefs.key eq KEY) }) {
            it[value] = GSON.toJson(current.copy(lastBackup = lastBackup ?: current.lastBackup, lastError = error))
        }
    }

    suspend fun config(user: UserPrincipal): ConfigDto = (load(user.id) ?: StoredConfig()).toDto()

    /** 保存配置并检查连接（成功后创建备份目录） */
    suspend fun save(user: UserPrincipal, input: ConfigInput): ConfigDto {
        val old = load(user.id) ?: StoredConfig()
        val url = input.url.trim().ifEmpty { DEFAULT_URL }
        val c = old.copy(
            url = url,
            account = input.account.trim(),
            password = if (input.password.isNullOrEmpty()) old.password else encrypt(input.password),
            dir = input.dir.trim().trim('/'),
            deviceName = input.deviceName.trim().take(32),
            autoBackup = input.autoBackup,
            syncProgress = input.syncProgress,
            lastError = null,
        )
        if (c.account.isEmpty() || c.password.isEmpty()) throw BadRequestException("请填写 WebDAV 账号和密码")
        connect(user, c)
        store(user.id, c)
        return c.toDto()
    }

    suspend fun clear(user: UserPrincipal) {
        store(user.id, StoredConfig())
    }

    private fun rootUrl(c: StoredConfig): String {
        var url = c.url.trim().ifEmpty { DEFAULT_URL }
        if (!url.endsWith("/")) url += "/"
        if (c.dir.isNotBlank()) url += c.dir.trim('/') + "/"
        return url
    }

    /** 建立连接：检查地址、账号，并确保备份目录存在 */
    private suspend fun connect(user: UserPrincipal, c: StoredConfig): Pair<WebDavClient, String> {
        if (c.account.isEmpty() || c.password.isEmpty()) throw BadRequestException("还没有配置 WebDAV")
        WebDavClient.checkUrl(c.url, allowPrivate = user.isAdmin)
        val client = WebDavClient(c.account, decrypt(c.password), allowPrivate = user.isAdmin)
        val root = rootUrl(c)
        var base = c.url.trim().ifEmpty { DEFAULT_URL }
        if (!base.endsWith("/")) base += "/"
        client.check(base)
        if (root != base) client.makeDir(root)
        return client to root
    }

    private suspend fun requireConfig(user: UserPrincipal): StoredConfig =
        load(user.id)?.takeIf { it.account.isNotEmpty() && it.password.isNotEmpty() }
            ?: throw BadRequestException("还没有配置 WebDAV")

    // ---------------- 备份 / 恢复 ----------------

    private fun backupName(c: StoredConfig): String {
        val device = c.deviceName.trim().replace(Regex("""[\\/:*?"<>|\s]"""), "_")
        val suffix = java.util.UUID.randomUUID().toString()
        return "backup${LocalDate.now()}${if (device.isNotEmpty()) "-$device" else ""}-$suffix.zip"
    }

    suspend fun backup(user: UserPrincipal, parts: BackupService.Parts = BackupService.Parts()): BackupResult {
        val c = requireConfig(user)
        try {
            val (client, root) = connect(user, c)
            val bytes = backupService.export(user, parts)
            val name = backupName(c)
            client.upload(root + name, bytes)
            storeResult(user.id, System.currentTimeMillis(), null)
            return BackupResult(name, bytes.size)
        } catch (e: Exception) {
            storeResult(user.id, null, e.message?.take(200))
            throw e
        }
    }

    /** 云端的备份文件（App 和网页版的都会列出），最新的在前 */
    suspend fun list(user: UserPrincipal): List<WebDavFile> {
        val (client, root) = connect(user, requireConfig(user))
        return client.list(root)
            .filter { !it.isDir && it.name.startsWith("backup") && it.name.endsWith(".zip", true) }
            .sortedWith(compareByDescending<WebDavFile> { it.lastModified }.thenByDescending { it.name })
    }

    private suspend fun downloadBackup(user: UserPrincipal, name: String): ByteArray {
        if (!name.startsWith("backup") || name.contains('/') || name.contains('\\') || name.contains("..")) {
            throw BadRequestException("备份文件名无效")
        }
        val (client, root) = connect(user, requireConfig(user))
        val bytes = client.download(root + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"), MAX_BACKUP)
        return bytes
    }

    suspend fun preview(user: UserPrincipal, name: String) = backupService.preview(downloadBackup(user, name))

    suspend fun restore(user: UserPrincipal, name: String, parts: BackupService.Parts): BackupService.ImportSummary =
        backupService.import(user, downloadBackup(user, name), parts)

    // ---------------- 阅读进度同步（bookProgress/{书名}_{作者}.json，与 App 互通） ----------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingUploads = ConcurrentHashMap<Pair<Long, Long>, Job>()

    private suspend fun progressClient(user: UserPrincipal): Pair<WebDavClient, String>? {
        val c = load(user.id)?.takeIf { it.syncProgress && it.account.isNotEmpty() && it.password.isNotEmpty() } ?: return null
        WebDavClient.checkUrl(c.url, allowPrivate = user.isAdmin)
        return WebDavClient(c.account, decrypt(c.password), allowPrivate = user.isAdmin) to rootUrl(c) + PROGRESS_DIR
    }

    /** 读取云端进度；未开启同步或云端没有这本书时返回 null */
    suspend fun pullProgress(user: UserPrincipal, bookId: Long): RemoteProgress? {
        val (client, dir) = progressClient(user) ?: return null
        val row = dbQuery { Books.selectAll().where { (Books.id eq bookId) and (Books.userId eq user.id) }.firstOrNull() }
            ?: return null
        val bytes = client.downloadOrNull(dir + progressFileName(row[Books.name], row[Books.author]), MAX_PROGRESS) ?: return null
        val p = GSON.fromJsonObject<AppBookProgress>(String(bytes, Charsets.UTF_8)).getOrNull() ?: return null
        val index = p.durChapterIndex.coerceAtLeast(0)
        val pos = backupService.charPosToRatio(bookId, row[Books.type], index, p.durChapterPos)
        return RemoteProgress(index, pos, p.durChapterTitle, p.durChapterTime)
    }

    /** 进度保存后调用：同一本书在 UPLOAD_DELAY 内的多次保存只上传最后一次 */
    fun scheduleUpload(user: UserPrincipal, bookId: Long) {
        val key = user.id to bookId
        pendingUploads.remove(key)?.cancel()
        pendingUploads[key] = scope.launch {
            delay(UPLOAD_DELAY)
            pendingUploads.remove(key)
            runCatching { uploadProgress(user, bookId) }
                .onFailure { logger.info("用户 {} 上传阅读进度失败: {}", user.username, it.message) }
        }
    }

    suspend fun uploadProgress(user: UserPrincipal, bookId: Long) {
        val (client, dir) = progressClient(user) ?: return
        val row = dbQuery { Books.selectAll().where { (Books.id eq bookId) and (Books.userId eq user.id) }.firstOrNull() }
            ?: return
        val index = row[Books.durChapterIndex]
        val progress = AppBookProgress(
            name = row[Books.name],
            author = row[Books.author],
            durChapterIndex = index,
            durChapterPos = backupService.ratioToCharPos(bookId, row[Books.type], index, row[Books.durChapterPos]),
            durChapterTime = row[Books.durChapterTime],
            durChapterTitle = row[Books.durChapterTitle],
        )
        client.makeDir(dir.removeSuffix(PROGRESS_DIR))
        client.makeDir(dir)
        client.upload(dir + progressFileName(progress.name, progress.author), GSON.toJson(progress).toByteArray(), "application/json")
    }

    /** 定时任务：开启自动备份的用户每天备份一次 */
    suspend fun autoBackupAll() {
        val rows = dbQuery {
            UserPrefs.selectAll().where { UserPrefs.key eq KEY }.map { it[UserPrefs.userId] to it[UserPrefs.value] }
        }
        val now = System.currentTimeMillis()
        rows.forEach { (userId, json) ->
            val c = GSON.fromJsonObject<StoredConfig>(json).getOrNull() ?: return@forEach
            if (!c.autoBackup || c.account.isEmpty() || now - c.lastBackup < DAY) return@forEach
            val u = userService.findActive(userId) ?: return@forEach
            runCatching { backup(UserPrincipal(u.id, u.username, u.role)) }
                .onFailure { logger.info("用户 {} 自动 WebDAV 备份失败: {}", u.username, it.message) }
        }
    }

    // ---------------- 密码加密 ----------------

    private val aesKey by lazy {
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest((Engine.secret + ":webdav").toByteArray()), "AES")
    }

    private fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    private fun decrypt(enc: String): String = try {
        val raw = Base64.getDecoder().decode(enc)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        throw BadRequestException("WebDAV 密码无法解密（服务器密钥可能已变更），请重新填写密码")
    }
}
