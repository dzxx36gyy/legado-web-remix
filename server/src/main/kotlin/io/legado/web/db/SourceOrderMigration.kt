package io.legado.web.db

import com.google.gson.JsonParser
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

internal fun repairLegacySourceOrder() = transaction(DatabaseFactory.database) {
    val marker = "migration.sourceOrder.nonnegative.v1"
    if (Settings.selectAll().where { Settings.key eq marker }.any()) return@transaction
    val rows = BookSources.selectAll()
        .orderBy(BookSources.customOrder to SortOrder.ASC, BookSources.id to SortOrder.ASC).toList()
    if (rows.any { it[BookSources.customOrder] < 0 }) {
        rows.forEachIndexed { order, row ->
            val source = JsonParser.parseString(row[BookSources.json]).asJsonObject
            source.addProperty("customOrder", order)
            BookSources.update({ BookSources.id eq row[BookSources.id] }) {
                it[customOrder] = order
                it[json] = source.toString()
            }
        }
    }
    Settings.upsert {
        it[key] = marker
        it[value] = "done"
    }
}
