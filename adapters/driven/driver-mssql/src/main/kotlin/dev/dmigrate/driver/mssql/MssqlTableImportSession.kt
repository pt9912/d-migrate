package dev.dmigrate.driver.mssql

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.data.AbstractTableImportSession
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.JdbcForeignValueNormalizer
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types

/**
 * Import-Session für SQL Server.
 *
 * - `abort` → gebatchtes `INSERT`.
 * - `skip` / `update` → `MERGE … OUTPUT $action` je Zeile: T-SQL hat kein
 *   `INSERT IGNORE`/`ON CONFLICT`, und `OUTPUT $action` liefert die exakte
 *   Zeilen-Buchführung (`INSERT`/`UPDATE`), statt sie zu schätzen. `MERGE`
 *   braucht einen Schlüssel — der Writer verlangt dafür einen Primärschlüssel.
 * - Enthält der Chunk die IDENTITY-Spalte, läuft der Import mit
 *   `SET IDENTITY_INSERT … ON` (SQL Server lehnt explizite Werte sonst ab) und
 *   schaltet sie im Abschluss/Cleanup wieder aus.
 * - Geometrie: der Read-Pfad liefert kanonisches WKB; der Insert konstruiert
 *   daraus wieder `geometry`/`geography`. T-SQL kennt dafür nur die statische
 *   Methodensyntax **mit** SRID (`geometry::STGeomFromWKB(?, 0)`), die nicht in
 *   die generische `geometryBindConstructor`-Naht der Basisklasse passt — das
 *   SQL baut deshalb [MssqlInsertSql].
 */
internal class MssqlTableImportSession(
    conn: Connection,
    savedAutoCommit: Boolean,
    table: String,
    private val qualifiedTable: MssqlQualifiedTableName,
    targetColumns: List<TargetColumn>,
    primaryKeyColumns: List<String>,
    private val identityColumns: Set<String>,
    private val computedColumns: Set<String>,
    options: ImportOptions,
    private val jdbc: JdbcOperations,
    private val schemaSync: MssqlSchemaSync,
) : AbstractTableImportSession(conn, savedAutoCommit, table, targetColumns, primaryKeyColumns, options) {

    private var identityInsertEnabled: Boolean = false
    private var discardConnection: Boolean = false

    override fun isGeometryTypeName(typeNameLower: String): Boolean =
        MssqlInsertSql.isGeometryTypeName(typeNameLower)

    /**
     * `MERGE` bindet die Schlüsselspalten aus der `src`-Zeile — fehlt eine im
     * Chunk, gäbe es nur ein unverständliches „could not be bound" vom Treiber.
     * Gilt hier auch für `skip` (die Basisklasse prüft nur `update`, weil
     * PG/MySQL dort eine schlüsselfreie Form haben).
     */
    override fun validateUpsertColumns(resolvedTargetColumns: List<TargetColumn>) {
        super.validateUpsertColumns(resolvedTargetColumns)
        if (options.onConflict != OnConflict.SKIP || primaryKeyColumns.isEmpty()) return
        val imported = resolvedTargetColumns.mapTo(mutableSetOf()) { it.name }
        val missing = primaryKeyColumns.filterNot { it in imported }
        if (missing.isNotEmpty()) {
            throw ImportSchemaMismatchException(
                "onConflict=skip for table '$table' requires all primary key columns on SQL Server " +
                    "(the MERGE predicate binds them); missing ${missing.joinToString()}",
            )
        }
    }

    override fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String {
        // Der einzige Hook, der einmalig vor dem ersten Chunk laeuft und die
        // tatsaechlich importierten Spalten kennt — hier fallen die
        // Computed-Column-Pruefung und die IDENTITY_INSERT-Entscheidung.
        rejectComputedColumns(importedTargetColumns)
        enableIdentityInsertIfNeeded(importedTargetColumns)
        return MssqlInsertSql.build(qualifiedTable, importedTargetColumns, primaryKeyColumns, options.onConflict)
    }

    override fun executeChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult = when (options.onConflict) {
        OnConflict.ABORT -> executeBatchChunk(importedTargetColumns, rows)
        OnConflict.SKIP, OnConflict.UPDATE -> executeMergeChunk(importedTargetColumns, rows)
    }

    override fun bindRow(
        stmt: PreparedStatement,
        importedTargetColumns: List<TargetColumn>,
        row: Array<Any?>,
    ) {
        importedTargetColumns.forEachIndexed { index, targetColumn ->
            val value = row[index]
            when {
                // Geometriespalten binden an das `varbinary`-Argument von
                // STGeomFromWKB — mssql-jdbc meldet als Spaltentyp aber GEOMETRY/
                // GEOGRAPHY, was an dieser Bind-Position ein Typkonflikt waere.
                value == null && isGeometryColumn(targetColumn) -> stmt.setNull(index + 1, Types.VARBINARY)
                value == null -> stmt.setNull(index + 1, targetColumn.jdbcType)
                // WKB explizit binär binden, damit STGeomFromWKB das Blob erhält.
                isGeometryColumn(targetColumn) && value is ByteArray -> stmt.setBytes(index + 1, value)
                // Fremde Treiber-Wrapper (PG `PGobject`, Arrays) vor dem Bind normalisieren.
                else -> stmt.setObject(index + 1, JdbcForeignValueNormalizer.normalize(value))
            }
        }
    }

    private fun executeBatchChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        for (row in rows) {
            bindRow(stmt, importedTargetColumns, row)
            stmt.addBatch()
        }
        var inserted = 0L
        for (count in stmt.executeBatch()) {
            inserted += when {
                count == Statement.SUCCESS_NO_INFO -> 1L
                count > 0 -> 1L
                else -> 0L
            }
        }
        return WriteResult(rowsInserted = inserted, rowsUpdated = 0, rowsSkipped = 0)
    }

    private fun executeMergeChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        var inserted = 0L
        var updated = 0L
        var skipped = 0L
        for (row in rows) {
            bindRow(stmt, importedTargetColumns, row)
            when (readMergeAction(stmt)) {
                "INSERT" -> inserted++
                "UPDATE" -> updated++
                else -> skipped++
            }
        }
        return WriteResult(rowsInserted = inserted, rowsUpdated = updated, rowsSkipped = skipped)
    }

    /** `$action` der MERGE-Zeile; `null`, wenn keine Zeile ausgegeben wurde (skip). */
    private fun readMergeAction(stmt: PreparedStatement): String? =
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1)?.uppercase() else null }

    override fun reseedSequences(): List<SequenceAdjustment> = schemaSync.reseedGenerators(
        conn = conn,
        table = table,
        importedColumns = importedColumns.orEmpty(),
        truncatePerformed = truncatePerformed,
    )

    override fun finishDialectCleanup(): Throwable? = disableIdentityInsert()

    override fun closeFinally() {
        disableIdentityInsert()?.let {
            // Die Connection traegt sonst IDENTITY_INSERT fuer DIESE Tabelle in den
            // Pool zurueck; der naechste Borger scheitert dann an Msg 8107 mit einem
            // fremden Tabellennamen. Lieber verwerfen als vergiften.
            discardConnection = true
            recordCleanupFailure(it)
        }
        runCatching { conn.autoCommit = savedAutoCommit }.onFailure(::recordCleanupFailure)
        if (discardConnection) {
            runCatching { conn.abort(DIRECT_EXECUTOR) }.onFailure(::recordCleanupFailure)
        }
    }

    /**
     * SQL Server lehnt jedes Schreiben auf eine Computed Column ab (Msg 271) —
     * auch mit `SET IDENTITY_INSERT`. Statt den Treiberfehler mitten im ersten
     * Chunk durchzureichen, benennt der Import die Spalte vorab.
     */
    private fun rejectComputedColumns(importedTargetColumns: List<TargetColumn>) {
        val offending = importedTargetColumns.map { it.name }.filter { it in computedColumns }
        if (offending.isEmpty()) return
        throw ImportSchemaMismatchException(
            "Target table '$table' has computed column(s) ${offending.joinToString()}; SQL Server does not " +
                "allow writing them. Exclude the column(s) from the export/transfer.",
        )
    }

    private fun enableIdentityInsertIfNeeded(importedTargetColumns: List<TargetColumn>) {
        if (identityInsertEnabled) return
        if (importedTargetColumns.none { it.name in identityColumns }) return
        MssqlDataWriter.setIdentityInsert(jdbc, qualifiedTable, enabled = true)
        identityInsertEnabled = true
    }

    private fun disableIdentityInsert(): Throwable? {
        if (!identityInsertEnabled) return null
        return runCatching {
            MssqlDataWriter.setIdentityInsert(jdbc, qualifiedTable, enabled = false)
            identityInsertEnabled = false
        }.exceptionOrNull()
    }
}
