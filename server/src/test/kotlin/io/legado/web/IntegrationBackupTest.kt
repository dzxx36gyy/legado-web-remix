package io.legado.web

import io.legado.app.help.engine.Engine
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.service.*
import io.legado.web.service.ai.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class IntegrationBackupTest {
    @Test
    fun corruptProviderRecordReportsErrorInsteadOfBecomingHttpEngine() = runBlocking {
        val file = Files.createTempFile("corrupt-provider-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val settings = SettingService()
        val tts = TtsService(SourceService(settings, UserService(settings)))
        val user = UserPrincipal(1, "owner", "admin")
        val id = org.jetbrains.exposed.v1.jdbc.transactions.transaction(DatabaseFactory.database) {
            io.legado.web.db.TtsEngines.insertAndGetId {
                it[userId] = 1; it[name] = "broken"
                it[json] = """{"name":"broken","url":"https://unused","provider":"corrupt"}"""
                it[createdAt] = 0
            }.value
        }
        val error = kotlin.test.assertFailsWith<io.legado.web.auth.BadRequestException> { tts.get(user, id) }
        kotlin.test.assertTrue(error.message.orEmpty().contains("配置损坏"))
    }

    @Test
    fun restoredCredentialsAreReencryptedForDestinationServerAndUser() = runBlocking {
        val file = Files.createTempFile("integration-backup-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val originalSecret = Engine.secret
        try {
            Engine.secret = "source-server-key"
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val sourceTts = TtsService(sources)
            val models = AiModelService()
            val owner = UserPrincipal(1, "owner", "admin")
            val destination = UserPrincipal(2, "destination", "admin")
            models.save(owner, null, AiModelInput("model", "https://example.com/v1", "example", "ai-portable-key"))
            sourceTts.saveProvider(owner, null, TtsService.ProviderEngineInput("voice", "fishaudio", "", "tts-portable-key"))
            val ttsBackup = sourceTts.backup(owner)
            val aiBackup = models.backup(owner)

            Engine.secret = "destination-server-key"
            val destinationTts = TtsService(sources)
            destinationTts.restore(destination, ttsBackup)
            models.restore(destination, aiBackup)

            assertEquals("tts-portable-key", destinationTts.backup(destination).single().provider?.apiKey)
            assertEquals("ai-portable-key", models.usable(destination, models.list(destination).single().id).apiKey)
            assertEquals(listOf("voice"), destinationTts.list(destination).filter { it.owned }.map { it.name })
        } finally { Engine.secret = originalSecret }
    }

    @Test
    fun restoringProviderEngineReplacesSameNamedAppEngine() = runBlocking {
        val file = Files.createTempFile("integration-backup-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val settings = SettingService()
        val tts = TtsService(SourceService(settings, UserService(settings)))
        val user = UserPrincipal(1, "owner", "admin")
        tts.import(user, """[{"name":"voice","url":"http://127.0.0.1:1/tts"}]""")

        tts.restore(user, listOf(TtsService.BackupEngine("voice", provider = TtsService.ProviderEngineInput("voice", "fishaudio", "", "k"))))

        val owned = tts.list(user).filter { it.owned }
        assertEquals(listOf("voice" to "fishaudio"), owned.map { it.name to it.provider })
    }
}
