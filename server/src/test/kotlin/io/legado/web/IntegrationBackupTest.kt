package io.legado.web

import io.legado.app.help.engine.Engine
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.service.*
import io.legado.web.service.ai.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class IntegrationBackupTest {
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
}
