package io.legado.web

import io.legado.app.utils.GSON
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.AppSettings
import io.legado.web.config.DbSettings
import io.legado.web.db.*
import io.legado.web.service.*
import io.legado.web.service.ai.AiModelService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupSubscriptionLinksTest {
    @Test
    fun roundTripRestoresSubscriptionNamesWithNewIdsAndBothSubscriptionFormats() = runBlocking {
        val origin = Fixture(10)
        origin.seed()
        val bytes = origin.backup.export(origin.user, BackupService.Parts(integrations = true, backupPassword = "password123"))
        val links = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var json = ""
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "webSourceSubscriptionLinks.json") json = zip.readBytes().toString(Charsets.UTF_8)
            }
            json
        }
        assertTrue(links.isNotEmpty(), "backup must include subscription links")
        val exported = GSON.fromJson(links, Array<BackupService.SourceSubscriptionLink>::class.java)
        assertEquals(setOf("https://a", "https://b"), exported.map { it.bookSourceUrl }.toSet())
        assertEquals(setOf("https://feed"), exported.map { it.subscriptionUrl }.toSet())
        assertEquals(2, exported.size)

        for (integrations in listOf(false, true)) {
            val destination = Fixture(20)
            destination.addSubscription(99, "unrelated", "https://unrelated")
            val parts = BackupService.Parts(subs = !integrations, integrations = integrations, backupPassword = "password123")
            repeat(2) {
                val result = destination.backup.import(destination.user, bytes, parts)
                assertTrue(result.committed, result.failures.toString())
                assertEquals(mapOf("https://a" to listOf("feed"), "https://b" to listOf("feed")), destination.labels())
                assertEquals(2L, dbQuery { SourceSubscriptionItems.selectAll().count() })
            }
        }
    }

    @Test
    fun selectiveRestoreOnlyLinksRestoredPrivateSources() = runBlocking {
        val origin = Fixture(10)
        origin.seed()
        val bytes = origin.backup.export(origin.user, BackupService.Parts())
        val destination = Fixture(20)
        destination.addSource(20, "https://b")
        destination.addSource(20, "https://a", public = true)
        destination.addSource(99, "https://a")
        destination.addSubscription(99, "other", "https://feed")
        val parts = BackupService.Parts(sourceUrls = listOf("https://a"))
        val result = destination.backup.import(destination.user, bytes, parts)
        assertTrue(result.committed, result.failures.toString())
        assertEquals(mapOf("https://a" to listOf("feed"), "https://b" to emptyList()), destination.labels())
        assertEquals(1L, dbQuery { SourceSubscriptionItems.selectAll().count() })

        val unselected = Fixture(30)
        unselected.addSource(30, "https://a")
        assertTrue(unselected.backup.import(unselected.user, bytes, BackupService.Parts(sources = false)).committed)
        assertEquals(mapOf("https://a" to emptyList()), unselected.labels())
        assertEquals(0L, dbQuery { SourceSubscriptionItems.selectAll().count() })
    }

    @Test
    fun malformedLinksPreventAllRestoreWrites() = runBlocking {
        for (links in listOf("[", """[{"kind":"source"}]""")) {
            val fixture = Fixture(10)
            val bytes = ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { zip ->
                    for ((name, text) in listOf(
                        "bookSource.json" to """[{"bookSourceUrl":"https://a","bookSourceName":"a"}]""",
                        "webSourceSubscriptionLinks.json" to links,
                    )) {
                        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                    }
                }
            }.toByteArray()
            val result = fixture.backup.import(fixture.user, bytes, BackupService.Parts())
            assertEquals(false, result.committed)
            assertEquals(false, result.rolledBack)
            assertEquals(listOf("webSourceSubscriptionLinks.json"), result.failures.map { it.file })
            assertEquals(0L, dbQuery { BookSources.selectAll().count() })
        }
    }

    private class Fixture(owner: Long) {
        val user = UserPrincipal(owner, "owner", "admin")
        val sources: SourceService
        val backup: BackupService

        init {
            val dir = Files.createTempDirectory("backup-links-").toFile().apply { deleteOnExit() }
            val db = DbSettings("sqlite", "jdbc:sqlite:${dir.absolutePath}/test.db", "", "", 1)
            DatabaseFactory.init(db)
            val settings = SettingService()
            sources = SourceService(settings, UserService(settings))
            val replace = ReplaceRuleService()
            val shelf = ShelfService(sources, replace, AppSettings(dir, db, "j", "e", "i", 1, emptyList(), true))
            backup = BackupService(sources, shelf, replace, DictRuleService(sources), TxtTocRuleService(sources), TtsService(sources), AiModelService())
        }

        suspend fun labels() = sources.list(user).filter { it.owned }.associate { it.bookSourceUrl to it.subscriptionNames }

        suspend fun addSource(owner: Long, url: String, public: Boolean = false): Long = dbQuery {
            BookSources.insertAndGetId {
                it[ownerId] = owner; it[BookSources.url] = url; it[name] = url
                it[isPublic] = public; it[json] = """{"bookSourceUrl":"$url","bookSourceName":"$url"}"""; it[createdAt] = 0
            }.value
        }

        suspend fun addSubscription(owner: Long, name: String, url: String): Long = dbQuery {
            SourceSubscriptions.insertAndGetId {
                it[ownerId] = owner; it[SourceSubscriptions.name] = name; it[SourceSubscriptions.url] = url; it[createdAt] = 0
            }.value
        }

        suspend fun seed() {
            val a = addSource(user.id, "https://a")
            val b = addSource(user.id, "https://b")
            val public = addSource(user.id, "https://public", public = true)
            val other = addSource(99, "https://other")
            val sub = addSubscription(user.id, "feed", "https://feed")
            val otherSub = addSubscription(99, "private name", "https://private-feed")
            dbQuery {
                for ((subscription, source) in listOf(sub to a, sub to b, sub to public, sub to other, otherSub to a)) {
                    SourceSubscriptionItems.insert { it[subscriptionId] = subscription; it[sourceId] = source }
                }
            }
        }
    }
}
