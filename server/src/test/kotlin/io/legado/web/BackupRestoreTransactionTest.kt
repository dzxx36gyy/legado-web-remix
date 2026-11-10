package io.legado.web

import io.legado.app.utils.GSON
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.AppSettings
import io.legado.web.config.DbSettings
import io.legado.web.db.*
import io.legado.web.service.*
import io.legado.web.service.ai.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupRestoreTransactionTest {
    @Test
    fun invalidRuleWritesNothing() = runBlocking {
        val fixture = Fixture()
        val result = fixture.backup.import(fixture.user, zip("bookSource.json" to SOURCE, "replaceRule.json" to """[{"pattern":"[","isRegex":true}]"""), BackupService.Parts())
        assertEquals(false, result.committed)
        assertEquals(false, result.rolledBack)
        assertEquals(listOf("replaceRule.json"), result.failures.map { it.file })
        assertEquals(0L, dbQuery { BookSources.selectAll().count() })
    }

    @Test
    fun laterDatabaseFailureRollsBackEarlierSourceWrites() = runBlocking {
        val fixture = Fixture()
        transaction(DatabaseFactory.database) {
            exec("CREATE TRIGGER reject_restore_book BEFORE INSERT ON books BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        }
        val result = fixture.backup.import(fixture.user, zip("bookSource.json" to SOURCE, "bookshelf.json" to """[{"bookUrl":"https://book","name":"book","origin":"https://source"}]"""), BackupService.Parts())
        assertEquals(false, result.committed)
        assertEquals(true, result.rolledBack)
        assertEquals("bookshelf.json", result.failures.single().file)
        assertEquals(0L, dbQuery { BookSources.selectAll().count() })
        assertEquals(0L, dbQuery { Books.selectAll().count() })
    }

    @Test
    fun replaceRulesSkipDuplicatesAndRollBackWithLaterFailure() = runBlocking {
        val fixture = Fixture()
        val rules = """[{"name":"r","pattern":"a","replacement":"b"},{"name":"r","pattern":"a","replacement":"b"}]"""
        val first = fixture.backup.import(fixture.user, zip("replaceRule.json" to rules), BackupService.Parts())
        assertEquals(true, first.committed)
        assertEquals(1, first.rulesAdded)
        assertEquals(0, fixture.backup.import(fixture.user, zip("replaceRule.json" to rules), BackupService.Parts()).rulesAdded)

        transaction(DatabaseFactory.database) {
            exec("CREATE TRIGGER reject_restore_dict BEFORE INSERT ON dict_rules BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        }
        val other = """[{"name":"x","pattern":"c","replacement":"d"}]"""
        val dict = """[{"name":"d","urlRule":"https://dict/{{key}}"}]"""
        val failed = fixture.backup.import(fixture.user, zip("replaceRule.json" to other, "dictRule.json" to dict), BackupService.Parts())
        assertEquals(true, failed.rolledBack)
        assertEquals(1L, dbQuery { ReplaceRules.selectAll().count() })
    }

    @Test
    fun rejectedModelHostNeverStartsDatabaseWrites() = runBlocking {
        val fixture = Fixture()
        val data = BackupService.Integrations(emptyList(), listOf(AiModelInput("model", "http://127.0.0.1:1", "model", "secret")), emptyList())
        transaction(DatabaseFactory.database) {
            exec("CREATE TRIGGER reject_any_source BEFORE INSERT ON book_sources BEGIN SELECT RAISE(ABORT, 'must not write'); END")
        }
        io.legado.web.security.NetworkGuard.configure { "" }
        val result = try {
            fixture.backup.import(fixture.user, zip("bookSource.json" to SOURCE, "webIntegrations.enc" to BackupSecrets.encode(GSON.toJson(data), "password123")), BackupService.Parts(integrations = true, backupPassword = "password123"))
        } finally { io.legado.web.security.NetworkGuard.resetForTests() }
        assertEquals(false, result.committed)
        assertEquals("webIntegrations.enc", result.failures.single().file)
        assertEquals(0L, dbQuery { BookSources.selectAll().count() })
    }

    private class Fixture {
        val user = UserPrincipal(1, "admin", "admin")
        val backup: BackupService
        init {
            val dir = Files.createTempDirectory("restore-test-").toFile().apply { deleteOnExit() }
            val db = DbSettings("sqlite", "jdbc:sqlite:${dir.absolutePath}/test.db", "", "", 1)
            DatabaseFactory.init(db)
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val replace = ReplaceRuleService()
            val shelf = ShelfService(sources, replace, AppSettings(dir, db, "j", "e", "i", 1, emptyList(), true))
            backup = BackupService(sources, shelf, replace, DictRuleService(sources), TxtTocRuleService(sources), TtsService(sources), AiModelService())
        }
    }

    companion object {
        private const val SOURCE = """[{"bookSourceUrl":"https://source","bookSourceName":"source"}]"""
        private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            } }
        }.toByteArray()
    }
}
