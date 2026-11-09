package io.legado.web

import io.legado.web.service.BackupSecrets
import io.legado.web.auth.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class BackupSecretsTest {
    @Test
    fun passwordBackupRoundTripsAndRejectsWrongPassword() {
        val first = BackupSecrets.encode("api-secret", "backup-password")
        val second = BackupSecrets.encode("api-secret", "backup-password")
        assertNotEquals(first, second)
        assertEquals("api-secret", BackupSecrets.decode(first, "backup-password"))
        assertFailsWith<BadRequestException> { BackupSecrets.decode(first, "wrong-password") }
        assertFailsWith<BadRequestException> { BackupSecrets.encode("secret", "short") }
    }
}
