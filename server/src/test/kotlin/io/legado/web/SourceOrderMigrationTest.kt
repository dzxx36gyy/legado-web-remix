package io.legado.web

import com.google.gson.JsonParser
import io.legado.web.config.DbSettings
import io.legado.web.db.BookSources
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.Settings
import io.legado.web.db.repairLegacySourceOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class SourceOrderMigrationTest {
    @Test
    fun repairsLegacyNegativesPreservingGlobalOrderAndJsonOnlyOnce() {
        val file = Files.createTempFile("source-order-", ".db").toFile()
        file.deleteOnExit()
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        transaction(DatabaseFactory.database) {
            Settings.deleteWhere { Settings.key eq "migration.sourceOrder.nonnegative.v1" }
            listOf("later" to 8, "first" to -4, "second" to -1).forEach { (name, order) ->
                BookSources.insert {
                    it[ownerId] = if (name == "second") 2 else 1
                    it[url] = "https://$name.example"
                    it[BookSources.name] = name
                    it[customOrder] = order
                    it[json] = """{"bookSourceName":"$name","customOrder":$order}"""
                    it[createdAt] = 0
                }
            }
        }
        repairLegacySourceOrder()
        repairLegacySourceOrder()
        transaction(DatabaseFactory.database) {
            val rows = BookSources.selectAll().orderBy(BookSources.customOrder to SortOrder.ASC).toList()
            assertEquals(listOf("first", "second", "later"), rows.map { it[BookSources.name] })
            assertEquals(listOf(0, 1, 2), rows.map { it[BookSources.customOrder] })
            assertEquals(listOf(0, 1, 2), rows.map { JsonParser.parseString(it[BookSources.json]).asJsonObject["customOrder"].asInt })
        }
    }
}
