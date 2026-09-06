package dev.dmigrate.driver.oracle

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

/**
 * Generator-Nachführung für Oracle nach einem Import.
 *
 * Oracle-Identity-Spalten sind (anders als MSSQLs unbenanntes IDENTITY, aber
 * wie PostgreSQLs SERIAL) an eine echte, benannte Sequenz gebunden
 * (`ALL_TAB_IDENTITY_COLS.SEQUENCE_NAME`) -- **aber** diese Sequenz ist
 * system-generiert (`ISEQ$$_n`) und `ALTER SEQUENCE` darauf scheitert mit
 * `ORA-32793: Cannot alter a system-generated sequence` (real gegen den
 * Testcontainer verifiziert). Der einzige sanktionierte Weg, sie
 * vorzuruecken, fuehrt ueber die Identity-Klausel der Tabelle selbst:
 * `ALTER TABLE ... MODIFY <col> GENERATED <mode> AS IDENTITY (START WITH n)`
 * -- unabhaengig davon, ob die Spalte gerade `ALWAYS` oder (waehrend eines
 * laufenden [OracleTableImportSession]-Identity-Toggles) `BY DEFAULT` ist;
 * der aktuelle Modus wird frisch aus dem Katalog gelesen, nicht angenommen.
 */
class OracleSchemaSync(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaSync {

    override fun reseedGenerators(
        conn: DatabaseConnection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment> = reseedGenerators(
        conn = conn.asJdbc(),
        table = table,
        importedColumns = importedColumns,
        truncatePerformed = false,
    )

    fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
        truncatePerformed: Boolean,
    ): List<SequenceAdjustment> {
        val jdbc = jdbcFactory(conn)
        val qualified = OracleQualifiedTableName.parse(table, OracleIdentifiers.currentSchema(jdbc))
        val identities = OracleMetadataQueries.identityColumns(jdbc, qualified.schema, qualified.table)
        if (identities.isEmpty()) return emptyList()

        val importedNames = importedColumns.mapTo(mutableSetOf()) { it.name }
        val adjustments = mutableListOf<SequenceAdjustment>()
        for (identity in identities) {
            if (identity.column !in importedNames && !truncatePerformed) continue
            val maxValue = OracleMetadataQueries.maxValue(jdbc, qualified.quotedPath(), identity.column)
            val newValue = if (maxValue != null) {
                val next = maxValue + identity.increment
                restartWith(jdbc, qualified, identity, next)
                next
            } else {
                if (!truncatePerformed) continue
                // Keine Zeile importiert: die urspruenglich deklarierte
                // START-WITH-Herkunft ist aus dem Katalog nicht rekonstruierbar
                // (ALL_SEQUENCES.LAST_NUMBER fuehrt nur den aktuellen Stand,
                // spec/type-mapping.md R345) -- LIMIT VALUE laesst Oracle
                // selbst auf den naechsten sinnvollen Wert schliessen.
                restartWithLimitValue(jdbc, qualified, identity)
                currentSequenceValue(jdbc, qualified.schema, identity.sequenceName)
            }
            adjustments += SequenceAdjustment(
                table = table,
                column = identity.column,
                sequenceName = identity.sequenceName,
                newValue = newValue,
            )
        }
        return adjustments
    }

    private fun restartWith(
        jdbc: JdbcOperations,
        table: OracleQualifiedTableName,
        identity: OracleMetadataQueries.IdentityColumnRow,
        startWith: Long,
    ) {
        jdbc.execute(
            "ALTER TABLE ${table.quotedPath()} MODIFY ${OracleIdentifiers.quote(identity.column)} " +
                "GENERATED ${identity.generation} AS IDENTITY (START WITH $startWith)",
        )
    }

    private fun restartWithLimitValue(
        jdbc: JdbcOperations,
        table: OracleQualifiedTableName,
        identity: OracleMetadataQueries.IdentityColumnRow,
    ) {
        jdbc.execute(
            "ALTER TABLE ${table.quotedPath()} MODIFY ${OracleIdentifiers.quote(identity.column)} " +
                "GENERATED ${identity.generation} AS IDENTITY (START WITH LIMIT VALUE)",
        )
    }

    /** Best-effort Ablesung nach `START WITH LIMIT VALUE`, fuer den Report. */
    private fun currentSequenceValue(jdbc: JdbcOperations, schema: String, sequenceName: String): Long =
        (
            jdbc.querySingle(
                "SELECT last_number FROM all_sequences WHERE sequence_owner = ? AND sequence_name = ?",
                schema,
                sequenceName,
            )?.get("last_number") as? Number
            )?.toLong() ?: 0L
}
