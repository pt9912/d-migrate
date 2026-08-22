package dev.dmigrate.driver.mssql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

/**
 * Generator-Nachführung für SQL Server nach einem Import: `DBCC CHECKIDENT`
 * setzt den IDENTITY-Zähler auf den höchsten importierten Wert, damit die
 * nächste vom Server vergebene Nummer kollisionsfrei ist.
 *
 * `DBCC CHECKIDENT(t, RESEED, n)` gibt als **nächsten** Wert `n + increment`
 * aus; [SequenceAdjustment.newValue] meldet daher `max + increment` (der
 * Vertrag des Ports: „nächster ohne expliziten Generatorwert ausgegebener
 * Wert"). Ausnahme: eine nach `--truncate` leer gebliebene Tabelle bekommt
 * wieder den deklarierten `IDENTITY(seed, increment)`-Startwert — analog
 * MySQL/SQLite. Dabei zählt, ob je eine Zeile eingefügt wurde:
 * `sys.identity_columns.last_value` ist dann `null`, und SQL Server nimmt den
 * RESEED-Wert **wörtlich** als ersten Wert (sonst `n + increment`). Seed,
 * Increment und dieser Zustand kommen aus dem Katalog, nichts wird geraten.
 */
class MssqlSchemaSync(
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
        val qualified = MssqlQualifiedTableName.parse(table, MssqlQualifiedTableName.defaultSchema(jdbc))
        val identity = MssqlMetadataQueries.identityColumn(jdbc, qualified.quotedPath()) ?: return emptyList()
        if (importedColumns.none { it.name == identity.column } && !truncatePerformed) return emptyList()

        val maxValue = MssqlMetadataQueries.maxValue(jdbc, qualified.quotedPath(), identity.column)
        if (maxValue == null) {
            if (!truncatePerformed) return emptyList()
            // Nie befüllt → der RESEED-Wert ist der erste Wert; sonst kommt noch
            // ein Increment obendrauf.
            val reseedTo = if (identity.lastValue == null) identity.seed else identity.seed - identity.increment
            reseed(jdbc, qualified, reseedTo)
            return listOf(adjustment(table, identity.column, newValue = identity.seed))
        }
        reseed(jdbc, qualified, maxValue)
        return listOf(adjustment(table, identity.column, newValue = maxValue + identity.increment))
    }

    /**
     * `DBCC CHECKIDENT` nimmt den Tabellennamen als String-**Literal**. Darin
     * steht die geklammerte Form, sonst zerfiele ein Name mit Punkt
     * (`[order.details]`) beim Auflösen in falsche Bestandteile.
     */
    private fun reseed(jdbc: JdbcOperations, table: MssqlQualifiedTableName, seed: Long) {
        val literal = table.quotedPath().replace("'", "''")
        jdbc.execute("DBCC CHECKIDENT ('$literal', RESEED, $seed)")
    }

    private fun adjustment(table: String, column: String, newValue: Long) = SequenceAdjustment(
        table = table,
        column = column,
        sequenceName = null,
        newValue = newValue,
    )
}
