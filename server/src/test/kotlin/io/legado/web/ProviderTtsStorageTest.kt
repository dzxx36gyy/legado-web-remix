package io.legado.web

import io.legado.app.utils.GSON
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.service.SettingService
import io.legado.web.service.SourceService
import io.legado.web.service.TtsService
import io.legado.web.service.UserService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderTtsStorageTest {
    @Test
    fun modelSurvivesSaveEditBackupJsonAndRestoreWithoutReturningSavedKey() = runBlocking {
        val dir = Files.createTempDirectory("tts-model-").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${dir.absolutePath}/test.db", "", "", 1))
        val settings = SettingService()
        val service = TtsService(SourceService(settings, UserService(settings)))
        val user = UserPrincipal(1, "owner", "admin")
        val input = TtsService.ProviderEngineInput(name = "Fish", provider = "fishaudio", apiKey = "test-secret", model = "s2-pro")
        val saved = service.saveProvider(user, null, input)
        assertEquals("s2-pro", saved.model)
        assertEquals("", saved.apiKey)
        assertTrue(saved.hasApiKey)
        val id = service.list(user).single().id
        val edited = service.saveProvider(user, id, saved.copy(model = "drama-3"))
        assertEquals("drama-3", edited.model)
        assertFalse(GSON.toJson(edited).contains("test-secret"))
        val backup = service.backup(user).single()
        val wire = GSON.toJson(backup)
        val decoded = GSON.fromJson(wire, TtsService.BackupEngine::class.java)
        service.delete(user, id)
        service.restore(user, listOf(decoded))
        val restored = service.getProvider(user, service.list(user).single().id)
        assertEquals("drama-3", restored.model)
        assertEquals("test-secret", service.backup(user).single().provider!!.apiKey)
        assertEquals("", restored.apiKey)
    }

    @Test
    fun discoveryRejectsAnotherOwnersIdBeforeUsingSavedCredentials() = runBlocking {
        val dir = Files.createTempDirectory("tts-owner-").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${dir.absolutePath}/test.db", "", "", 1))
        val settings = SettingService()
        val service = TtsService(SourceService(settings, UserService(settings)))
        val owner = UserPrincipal(1, "owner", "admin")
        val other = UserPrincipal(2, "other", "admin")
        service.saveProvider(owner, null, TtsService.ProviderEngineInput(name = "Azure", provider = "azure", region = "eastus", apiKey = "test-secret"))
        val id = service.list(owner).single().id
        assertFailsWith<NotFoundException> { service.providerVoices(other, TtsService.ProviderVoicesInput(id = id)) }
        assertFailsWith<NotFoundException> { service.testProvider(other, id, TtsService.ProviderEngineInput(provider = "azure", region = "eastus"), "test", 10) }
        assertEquals(emptyList(), service.list(other))
        val metadata = service.providers().first { it.id == "azure" }
        assertTrue(metadata.needsRegion)
        assertFalse(metadata.needsSecretId)
        val fish = service.providers().first { it.id == "fishaudio" }
        assertEquals("s2.1-pro-free", fish.defaultModel)
    }
}
