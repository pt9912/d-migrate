package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import java.sql.Connection

/**
 * Dialekt-spezifische Operationen rund um den Schreib-Zyklus.
 *
 * Vollständige Vertragsdokumentation und Tests: Schritt 13.
 */
interface SchemaSync {
    fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment>

    fun disableTriggers(conn: Connection, table: String)
    fun assertNoUserTriggers(conn: Connection, table: String)
    fun enableTriggers(conn: Connection, table: String)
}
