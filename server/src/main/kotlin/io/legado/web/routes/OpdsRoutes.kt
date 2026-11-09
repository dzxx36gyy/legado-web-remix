package io.legado.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.http.withCharset
import io.legado.app.constant.BookType
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.service.ApiKeyService
import io.legado.web.service.BookDto
import io.legado.web.service.LocalBookService
import io.legado.web.service.ShelfService
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

private const val NAV_TYPE = "application/atom+xml;profile=opds-catalog;kind=navigation"
private const val ACQ_TYPE = "application/atom+xml;profile=opds-catalog;kind=acquisition"

/** 本地书籍文件格式 -> MIME（OPDS 客户端按它决定能否打开） */
private val BOOK_MIME = mapOf(
    "epub" to "application/epub+zip",
    "pdf" to "application/pdf",
    "txt" to "text/plain; charset=utf-8",
    "mobi" to "application/x-mobipocket-ebook",
    "azw3" to "application/vnd.amazon.ebook",
    "azw" to "application/vnd.amazon.ebook",
    "umd" to "application/octet-stream",
)

/** XML 1.0 不允许的字符（控制字符、U+FFFE/U+FFFF、不成对的代理项）一个也不能输出，否则整个目录无法解析 */
private fun xmlChars(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val ok = cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
        if (ok) sb.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return sb.toString()
}

private fun esc(s: String?): String = xmlChars(s.orEmpty())
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    // XML 1.0 不允许的控制字符
    .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")

private fun iso(ms: Long): String = Instant.ofEpochMilli(if (ms > 0) ms else System.currentTimeMillis()).toString()

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun attachment(book: BookDto, ext: String): String {
    val fileName = (book.name.ifBlank { "book" } + if (book.author.isNotBlank()) " - ${book.author}" else "")
        .replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").take(150) + ".$ext"
    // filename 给只认 ASCII 的客户端兜底，filename* (RFC 5987) 保留中文书名
    val encoded = URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
    return "attachment; filename=\"book.$ext\"; filename*=UTF-8''$encoded"
}

/**
 * HTTP Basic：用户名 + API Key（密码填 API Key，不使用登录密码）。
 * 用户名必须与密钥所属账号一致，避免填错账号时看到别人的书架。
 */
private suspend fun ApplicationCall.opdsUser(apiKeyService: ApiKeyService): UserPrincipal? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith("Basic ", true)) return null
    val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8) }.getOrNull() ?: return null
    val sep = decoded.indexOf(':')
    if (sep < 0) return null
    val name = decoded.substring(0, sep)
    val key = decoded.substring(sep + 1)
    val user = apiKeyService.authenticate(key) ?: return null
    return user.takeIf { it.username.equals(name.trim(), ignoreCase = true) }
}

private suspend fun ApplicationCall.challenge() {
    response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"legado-web OPDS\", charset=\"UTF-8\"")
    respondText("用户名填账号，密码填 API Key", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
}

private fun feed(id: String, title: String, selfHref: String, selfType: String, entries: String) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    append("""<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog">""")
    append("<id>").append(esc(id)).append("</id>")
    append("<title>").append(esc(title)).append("</title>")
    append("<updated>").append(iso(0)).append("</updated>")
    append("""<author><name>legado-web</name></author>""")
    append("""<link rel="self" href="${esc(selfHref)}" type="$selfType"/>""")
    append("""<link rel="start" href="/opds" type="$NAV_TYPE"/>""")
    append("""<link rel="search" href="/opds/search.xml" type="application/opensearchdescription+xml"/>""")
    append(entries)
    append("</feed>")
}

private fun navEntry(id: String, title: String, content: String, href: String) =
    "<entry><id>${esc(id)}</id><title>${esc(title)}</title><updated>${iso(0)}</updated>" +
        "<content type=\"text\">${esc(content)}</content>" +
        "<link rel=\"subsection\" href=\"${esc(href)}\" type=\"$ACQ_TYPE\"/></entry>"

/** [ext] 为 null 表示在线书籍：下载已缓存章节拼成的 TXT，[cached] 为已缓存章节数 */
private fun bookEntry(b: BookDto, ext: String?, cached: Int = 0): String = buildString {
    val mime = if (ext == null) "text/plain; charset=utf-8" else BOOK_MIME[ext] ?: "application/octet-stream"
    append("<entry>")
    append("<id>urn:legado-web:book:${b.id}</id>")
    append("<title>").append(esc(b.name)).append("</title>")
    if (b.author.isNotBlank()) append("<author><name>").append(esc(b.author)).append("</name></author>")
    append("<updated>").append(iso(maxOf(b.durChapterTime, b.latestChapterTime))).append("</updated>")
    append("<dc:format>").append(esc(mime.substringBefore(';'))).append("</dc:format>")
    val intro = (b.customIntro ?: b.intro)?.takeIf { it.isNotBlank() }?.take(2000)
    val summary = if (ext == null) listOfNotNull("已缓存 $cached/${b.totalChapterNum} 章 · ${b.originName}", intro).joinToString("\n") else intro
    summary?.let { append("<summary type=\"text\">").append(esc(it)).append("</summary>") }
    if (ext == null) {
        // 在线书籍的封面是书源站点地址，由客户端直接加载
        b.coverUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let {
            append("""<link rel="http://opds-spec.org/image" href="${esc(it)}"/>""")
            append("""<link rel="http://opds-spec.org/image/thumbnail" href="${esc(it)}"/>""")
        }
        append("""<link rel="http://opds-spec.org/acquisition" href="/opds/books/${b.id}/txt" type="${esc(mime)}"/>""")
        append("</entry>")
        return@buildString
    }
    if (b.coverUrl != null) {
        append("""<link rel="http://opds-spec.org/image" href="/opds/books/${b.id}/cover" type="image/jpeg"/>""")
        append("""<link rel="http://opds-spec.org/image/thumbnail" href="/opds/books/${b.id}/cover" type="image/jpeg"/>""")
    }
    append("""<link rel="http://opds-spec.org/acquisition" href="/opds/books/${b.id}/file" type="${esc(mime)}"/>""")
    append("</entry>")
}

/**
 * OPDS 1.2 书库，供 KOReader、Moon+ Reader、Librera 等客户端浏览和下载。
 * 本地书籍下载原文件；在线书籍只把服务器上已缓存的章节拼成 TXT，不联网获取未缓存章节。
 * - GET /opds                    首页：全部本地书 + 各分组
 * - GET /opds/books?group=&q=    书籍列表（可按分组、书名/作者筛选）
 * - GET /opds/books/{id}/file    下载原文件
 * - GET /opds/books/{id}/cover   封面
 * - GET /opds/online             在线书籍列表（已缓存 x/y 章）
 * - GET /opds/books/{id}/txt     在线书籍已缓存章节 TXT
 * - GET /opds/search.xml         OpenSearch 描述
 * 鉴权：HTTP Basic，用户名为账号，密码为 API Key
 */
fun Route.opdsRoutes(apiKeyService: ApiKeyService, shelfService: ShelfService, localBookService: LocalBookService) {
    route("/opds") {
        suspend fun ApplicationCall.localBooks(u: UserPrincipal) = shelfService.shelf(u).filter { it.isLocal }

        // 在线文字书籍（音频、漫画、视频拼不成 TXT）
        suspend fun onlineBooks(u: UserPrincipal) = shelfService.shelf(u)
            .filter { !it.isLocal && it.type and (BookType.audio or BookType.image or BookType.video) == 0 }

        fun extOf(u: UserPrincipal, b: BookDto) =
            runCatching { localBookService.fileOf(u.id, b.bookUrl).name.substringAfterLast('.').lowercase() }.getOrNull()

        get {
            val u = call.opdsUser(apiKeyService) ?: return@get call.challenge()
            val books = call.localBooks(u)
            val entries = buildString {
                append(navEntry("urn:legado-web:all", "全部本地书籍", "${books.size} 本", "/opds/books"))
                for (g in shelfService.groups(u)) {
                    val n = books.count { it.groupId == g.id }
                    if (n > 0) append(navEntry("urn:legado-web:group:${g.id}", g.name, "$n 本", "/opds/books?group=${g.id}"))
                }
                val ungrouped = books.count { it.groupId == 0L }
                if (ungrouped > 0 && ungrouped < books.size) {
                    append(navEntry("urn:legado-web:group:0", "未分组", "$ungrouped 本", "/opds/books?group=0"))
                }
                val online = onlineBooks(u)
                if (online.isNotEmpty()) {
                    append(navEntry("urn:legado-web:online", "在线书籍（已缓存章节）", "${online.size} 本，下载为 TXT", "/opds/online"))
                }
            }
            call.respondText(feed("urn:legado-web:root", "legado-web · ${u.username}", "/opds", NAV_TYPE, entries), ContentType.parse(NAV_TYPE))
        }

        get("/search.xml") {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
<ShortName>legado-web</ShortName><Description>搜索本地书籍</Description><InputEncoding>UTF-8</InputEncoding><OutputEncoding>UTF-8</OutputEncoding>
<Url type="$ACQ_TYPE" template="/opds/books?q={searchTerms}"/>
</OpenSearchDescription>"""
            call.respondText(xml, ContentType.parse("application/opensearchdescription+xml"))
        }

        get("/books") {
            val u = call.opdsUser(apiKeyService) ?: return@get call.challenge()
            val group = call.request.queryParameters["group"]?.toLongOrNull()
            val q = call.request.queryParameters["q"]?.trim().orEmpty()
            val books = call.localBooks(u)
                .filter { group == null || it.groupId == group }
                .filter { q.isEmpty() || it.name.contains(q, true) || it.author.contains(q, true) }
            val title = when {
                q.isNotEmpty() -> "搜索：$q"
                group == null -> "全部本地书籍"
                group == 0L -> "未分组"
                else -> shelfService.groups(u).firstOrNull { it.id == group }?.name ?: "分组"
            }
            val self = "/opds/books" + when {
                q.isNotEmpty() -> "?q=" + enc(q)
                group != null -> "?group=$group"
                else -> ""
            }
            val entries = books.mapNotNull { b -> extOf(u, b)?.let { bookEntry(b, it) } }.joinToString("")
            call.respondText(feed("urn:legado-web:books:${group ?: "all"}:${q}", title, self, ACQ_TYPE, entries), ContentType.parse(ACQ_TYPE))
        }

        get("/online") {
            val u = call.opdsUser(apiKeyService) ?: return@get call.challenge()
            val books = onlineBooks(u)
            val counts = shelfService.cachedChapterCounts(u, books.map { it.id })
            val entries = books.joinToString("") { bookEntry(it, null, counts[it.id] ?: 0) }
            call.respondText(feed("urn:legado-web:online", "在线书籍（已缓存章节）", "/opds/online", ACQ_TYPE, entries), ContentType.parse(ACQ_TYPE))
        }

        get("/books/{id}/txt") {
            val u = call.opdsUser(apiKeyService) ?: return@get call.challenge()
            val id = call.longParam("id")
            val book = shelfService.getBook(u, id)
            if (book.isLocal) throw NotFoundException("本地书籍请下载原文件")
            call.response.header(HttpHeaders.ContentDisposition, attachment(book, "txt"))
            call.respondTextWriter(ContentType.Text.Plain.withCharset(Charsets.UTF_8)) {
                shelfService.exportCachedTxt(u, id, this)
            }
        }

        get("/books/{id}/file") {
            val u = call.opdsUser(apiKeyService) ?: return@get call.challenge()
            val id = call.longParam("id")
            val book = shelfService.getBook(u, id)
            val file = localBookService.fileOf(u.id, shelfService.localBookUrl(u, id))
            if (!file.isFile) throw NotFoundException("文件不存在")
            val ext = file.name.substringAfterLast('.').lowercase()
            call.response.header(HttpHeaders.ContentDisposition, attachment(book, ext))
            call.respond(LocalFileContent(file, ContentType.parse(BOOK_MIME[ext] ?: "application/octet-stream")))
        }

        get("/books/{id}/cover") {
            val u = call.opdsUser(apiKeyService) ?: return@get call.challenge()
            val id = call.longParam("id")
            val (bytes, type) = localBookService.cover(u.id, shelfService.localBookUrl(u, id))
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondBytes(bytes, ContentType.parse(type))
        }
    }
}
