package io.legado.web

import io.legado.web.config.DbSettings
import io.legado.web.db.BookSources
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.Settings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class PublicSourceMigrationTest {
    @Test
    fun upgradesOldUniqueIndexAndKeepsPublicIdsAcrossRestarts() {
        val file = Files.createTempFile("public-migration-", ".db").toFile()
        file.deleteOnExit()
        val settings = DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1)
        DatabaseFactory.init(settings)
        val publicId = transaction(DatabaseFactory.database) {
            Settings.deleteWhere { Settings.key eq "public_source_snapshots_v1" }
            exec("DROP INDEX ux_source_owner_url_scope")
            exec("CREATE UNIQUE INDEX ux_source_owner_url ON book_sources(owner_id,url)")
            val id = BookSources.insertAndGetId {
                it[ownerId] = 1
                it[url] = "https://public.example"
                it[name] = "Published"
                it[isPublic] = true
                it[json] = """{"bookSourceUrl":"https://public.example","bookSourceName":"Published"}"""
                it[createdAt] = 123
            }.value
            BookSources.insertAndGetId {
                it[ownerId] = 2
                it[url] = "https://private.example"
                it[name] = "Other user"
                it[json] = "{}"
                it[createdAt] = 456
            }
            id
        }
        DatabaseFactory.init(settings)
        transaction(DatabaseFactory.database) {
            val rows = BookSources.selectAll().toList()
            assertEquals(3, rows.size)
            val published = rows.single { it[BookSources.isPublic] }
            assertEquals(publicId, published[BookSources.id].value)
            val copy = rows.single { it[BookSources.ownerId] == 1L && !it[BookSources.isPublic] }
            assertEquals(published[BookSources.json], copy[BookSources.json])
            assertEquals(123L, copy[BookSources.createdAt])
            assertEquals("Other user", rows.single { it[BookSources.ownerId] == 2L }[BookSources.name])
            BookSources.deleteWhere { BookSources.id eq copy[BookSources.id] }
        }
        DatabaseFactory.init(settings)
        transaction(DatabaseFactory.database) {
            assertEquals(2L, BookSources.selectAll().count())
        }
    }
}
