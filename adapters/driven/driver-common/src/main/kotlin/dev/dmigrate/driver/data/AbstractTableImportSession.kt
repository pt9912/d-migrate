package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.Executor

/**
 * Shared state-machine and template logic for JDBC-based [TableImportSession]
 * implementations. Subclasses override the dialect hooks ([buildInsertSql],
 * [executeChunk], [bindRow], etc.) while the lifecycle methods ([write],
 * [commitChunk], [rollbackChunk], [finishTable], [close]) are implemented
 * once.
 *
 * State-machine: OPEN -> WRITTEN -> OPEN (commit/rollback) -> FINISHED -> CLOSED.
 * Any exception transitions to FAILED; [close] always transitions to CLOSED.
 */
abstract class AbstractTableImportSession(
    protected val conn: Connection,
    protected val savedAutoCommit: Boolean,
    protected val table: String,
    final override val targetColumns: List<TargetColumn>,
    protected val primaryKeyColumns: List<String>,
    protected val options: ImportOptions,
) : TableImportSession {

    protected enum class State { OPEN, WRITTEN, FAILED, FINISHED, CLOSED }

    private val targetColumnsByName = targetColumns.associateBy { it.name }
    protected var state: State = State.OPEN
    protected var hasWritten: Boolean = false
    protected var truncatePerformed: Boolean = false
    protected var importedColumns: List<ColumnDescriptor>? = null
        private set
    protected var preparedStatement: PreparedStatement? = null
    protected var importedTargetColumns: List<TargetColumn>? = null
        private set
    protected var lastFailure: Throwable? = null

    // ─── Abstract dialect hooks ─────────────────────────────────────────

    /** Build the dialect-specific INSERT / UPSERT SQL for the given columns. */
    protected abstract fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String

    /**
     * VA1c (Spatial-Slice): dialekt-spezifischer SQL-Konstruktor, der einen
     * gebundenen WKB-Wert in eine Geometriespalte umwandelt — z. B.
     * `"ST_GeomFromWKB"` (PostGIS/MySQL). Ist er gesetzt, wrappt [valuePlaceholder]
     * Geometrie-Zielspalten als `<ctor>(?)` statt `?`; der gebundene Wert bleibt
     * das WKB-`byte[]` aus dem Read-Pfad (VA1b). Default `null` → kein Wrapping
     * (SQLite/SpatiaLite folgt mit VA4). VA2: trägt die Ziel-Spalte eine SRID
     * ([TargetColumn.srid]), wird sie als `<ctor>(?, srid)` mitgegeben, sonst
     * `<ctor>(?)` (→ SRID 0); beide Formen gelten für PostGIS und MySQL.
     */
    protected open val geometryBindConstructor: String? = null

    /**
     * VA1 (Spatial-Slice): **dialekt-bewusste** Erkennung, ob ein (lowercase)
     * Ziel-`sqlTypeName` eine WKB-fähige Geometriespalte bezeichnet. Default
     * `false`. PostGIS überschreibt mit nur `"geometry"` (NICHT die nativen
     * PG-Typen point/polygon/…); MySQL mit allen OGC-Namen. Steuert, ob
     * [valuePlaceholder] die Spalte mit [geometryBindConstructor] wrappt.
     */
    protected open fun isGeometryTypeName(typeNameLower: String): Boolean = false

    /**
     * VA2-X1 (Cross-Dialect-Achsenreihenfolge): zusätzliche Konstruktor-Argumente
     * nach der SRID, z. B. MySQLs `"'axis-order=long-lat'"`, damit der gebundene
     * WKB in OGC-X/Y-Reihenfolge interpretiert wird (PostGIS-kompatibel). Wird von
     * [valuePlaceholder] **nur** angehängt, wenn eine SRID vorliegt
     * (`<ctor>(?, srid, <options>)`) — ohne SRID gibt es kein Argument, an das die
     * Optionen syntaktisch andocken könnten. Default `null` → keine Optionen
     * (PostGIS liest/schreibt nativ OGC X/Y und braucht keine).
     */
    protected open val geometryBindOptions: String? = null

    /** Execute a chunk of rows using the dialect-specific conflict strategy. */
    protected abstract fun executeChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult

    /** Bind a single row to the [PreparedStatement]. */
    protected abstract fun bindRow(
        stmt: PreparedStatement,
        importedTargetColumns: List<TargetColumn>,
        row: Array<Any?>,
    )

    /** Reseed sequences/generators after successful import. */
    protected abstract fun reseedSequences(): List<SequenceAdjustment>

    /**
     * Dialect-specific cleanup in [finishTable] (e.g. FK-check reset,
     * trigger re-enable). Returns the failure [Throwable] or `null`.
     */
    protected abstract fun finishDialectCleanup(): Throwable?

    /**
     * Called in [close] try-block after rollback, before finally.
     * Override for cleanup that should happen before the finally block
     * (e.g. PostgreSQL trigger re-enable). Default: no-op.
     */
    protected open fun closePreFinally() {}

    /**
     * Called in [close] finally-block between [preparedStatement] close
     * and [conn] close. Each dialect handles autocommit restore,
     * FK/trigger cleanup, and connection discard here.
     */
    protected abstract fun closeFinally()

    // ─── Template implementations ───────────────────────────────────────

    override fun write(chunk: DataChunk): WriteResult {
        requireState(State.OPEN, "write")
        if (chunk.table != table) {
            state = State.FAILED
            val failure = ImportSchemaMismatchException(
                "Chunk table '${chunk.table}' does not match open target table '$table'"
            )
            lastFailure = failure
            throw failure
        }

        return try {
            val plan = ensureInsertPlan(chunk)
            validateRowWidths(chunk, plan.size)
            val result = executeChunk(plan, chunk.rows)
            state = State.WRITTEN
            hasWritten = true
            result
        } catch (t: Throwable) {
            state = State.FAILED
            lastFailure = t
            throw t
        }
    }

    override fun commitChunk() {
        requireState(State.WRITTEN, "commitChunk")
        try {
            conn.commit()
            preparedStatement?.clearBatch()
            state = State.OPEN
        } catch (t: Throwable) {
            state = State.FAILED
            lastFailure = t
            throw t
        }
    }

    override fun rollbackChunk() {
        requireState(State.WRITTEN, "rollbackChunk")
        try {
            conn.rollback()
            preparedStatement?.clearBatch()
            state = State.OPEN
        } catch (t: Throwable) {
            state = State.FAILED
            lastFailure = t
            throw t
        }
    }

    override fun markTruncatePerformed() {
        check(state == State.OPEN && !hasWritten) {
            "markTruncatePerformed() requires OPEN before any write, " +
                "current state: $state, hasWritten: $hasWritten"
        }
        truncatePerformed = true
    }

    override fun finishTable(): FinishTableResult {
        requireState(State.OPEN, "finishTable")
        return try {
            val adjustments = if (options.reseedSequences) {
                reseedSequences()
            } else {
                emptyList()
            }

            val cleanupFailure = finishDialectCleanup()

            state = State.FINISHED
            if (cleanupFailure == null) {
                FinishTableResult.Success(adjustments)
            } else {
                lastFailure = cleanupFailure
                FinishTableResult.PartialFailure(adjustments, cleanupFailure)
            }
        } catch (t: Throwable) {
            state = State.FAILED
            lastFailure = t
            throw t
        }
    }

    override fun close() {
        if (state == State.CLOSED) return
        try {
            if (state == State.WRITTEN || state == State.OPEN || state == State.FAILED) {
                runCatching { conn.rollback() }.onFailure(::recordCleanupFailure)
            }
            closePreFinally()
        } finally {
            runCatching { preparedStatement?.close() }.onFailure(::recordCleanupFailure)
            closeFinally()
            runCatching { conn.close() }.onFailure(::recordCleanupFailure)
            state = State.CLOSED
        }
    }

    // ─── Shared helpers ─────────────────────────────────────────────────

    protected fun ensureInsertPlan(chunk: DataChunk): List<TargetColumn> {
        importedColumns?.let { existing ->
            if (existing != chunk.columns) {
                throw ImportSchemaMismatchException(
                    "All chunks for table '$table' must use the same column layout; " +
                        "expected ${existing.map { it.name }}, got ${chunk.columns.map { it.name }}"
                )
            }
            return importedTargetColumns!!
        }

        val duplicates = chunk.columns.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ImportSchemaMismatchException(
                "Chunk for table '$table' contains duplicate columns: ${duplicates.joinToString()}"
            )
        }

        val resolvedTargetColumns = chunk.columns.map { sourceColumn ->
            targetColumnsByName[sourceColumn.name]
                ?: throw ImportSchemaMismatchException(
                    "Target table '$table' has no column '${sourceColumn.name}'"
                )
        }

        validateUpsertColumns(resolvedTargetColumns)

        preparedStatement = conn.prepareStatement(buildInsertSql(resolvedTargetColumns))
        importedColumns = chunk.columns
        importedTargetColumns = resolvedTargetColumns
        return resolvedTargetColumns
    }

    /**
     * Validates that all primary-key columns are present in the imported
     * columns when [OnConflict.UPDATE] is active. Subclasses may override
     * to customise or skip this check.
     */
    protected open fun validateUpsertColumns(resolvedTargetColumns: List<TargetColumn>) {
        if (options.onConflict == OnConflict.UPDATE && primaryKeyColumns.isNotEmpty()) {
            val importedNames = resolvedTargetColumns.map { it.name }.toSet()
            val missingPrimaryKeys = primaryKeyColumns.filterNot { it in importedNames }
            if (missingPrimaryKeys.isNotEmpty()) {
                throw ImportSchemaMismatchException(
                    "onConflict=update for table '$table' requires all primary key columns; " +
                        "missing ${missingPrimaryKeys.joinToString()}"
                )
            }
        }
    }

    /**
     * VA1c/VA2: liefert den VALUES-Platzhalter für eine Zielspalte — `?` normal, bzw.
     * `<geometryBindConstructor>(?)` für Geometrie-Zielspalten (typeName in
     * [GeometryType.KNOWN_VALUES], wie VA1a/VA1b). Trägt die Zielspalte eine SRID,
     * wird sie als zweites Konstruktor-Argument mitgegeben (`<ctor>(?, srid)`), damit
     * das Ziel die SRID nicht auf 0 fallen lässt. Die Bind-Position bleibt ein `?`
     * pro Spalte; [bindRow] bindet weiterhin genau einen Wert (das WKB-`byte[]`).
     */
    protected fun valuePlaceholder(column: TargetColumn): String {
        val constructor = geometryBindConstructor
        if (constructor == null || !isGeometryColumn(column)) return "?"
        val srid = column.srid ?: return "$constructor(?)"
        val options = geometryBindOptions
        return if (options != null) "$constructor(?, $srid, $options)" else "$constructor(?, $srid)"
    }

    /** VA1c: ob die Zielspalte eine WKB-Geometriespalte ist (dialekt-bewusst). */
    protected open fun isGeometryColumn(column: TargetColumn): Boolean {
        val typeName = column.sqlTypeName?.lowercase()
        return typeName != null && isGeometryTypeName(typeName)
    }

    protected fun validateRowWidths(chunk: DataChunk, columnCount: Int) {
        chunk.rows.forEachIndexed { rowIndex, row ->
            if (row.size != columnCount) {
                throw ImportSchemaMismatchException(
                    "Chunk row $rowIndex for table '$table' has ${row.size} values, " +
                        "expected $columnCount"
                )
            }
        }
    }

    protected fun requireState(expected: State, operation: String) {
        check(state == expected) {
            "$operation requires state $expected, current state: $state"
        }
    }

    protected fun recordCleanupFailure(t: Throwable) {
        if (lastFailure == null) {
            lastFailure = t
        } else {
            lastFailure?.addSuppressed(t)
        }
    }

    protected companion object {
        @JvmStatic
        protected val DIRECT_EXECUTOR: Executor = Executor { it.run() }
    }
}
