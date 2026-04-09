package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Gemeinsame Implementierung des [DataReader]-Vertrags für alle JDBC-basierten
 * Treiber. Konkrete Treiber überschreiben:
 *
 * - [quoteIdentifier] — Treiber-spezifisches Quoting (`"x"` für PG/SQLite, `` `x` `` für MySQL)
 * - [fetchSize] — Treiber-interne Streaming-Tuning-Konstante (siehe §6.13)
 * - [needsAutoCommitFalse] — ob `setAutoCommit(false)` für Cursor-Streaming nötig ist
 *   (PostgreSQL: ja; MySQL mit useCursorFetch: nein zwingend; SQLite: irrelevant)
 * - optional [buildSelectSql] für treiberspezifische Variationen
 *
 * Der Lifecycle aus §6.12 ist hier zentral implementiert: jede streamTable-
 * Operation läuft in einer eigenen Transaktion, die in [ChunkSequence.close]
 * mit `rollback()`, `setAutoCommit(true)` und `conn.close()` (= Hikari-Return)
 * abgeschlossen wird — auch bei Exception.
 *
 * Der Empty-Table-Vertrag aus §6.17 wird hier ebenfalls erfüllt: auch wenn
 * das ResultSet keine Rows liefert, emittiert die ChunkSequence einen Chunk
 * mit den `columns` und `rows = emptyList()`.
 */
abstract class AbstractJdbcDataReader : DataReader {

    /** Quoting für Spalten- und Tabellennamen. */
    protected abstract fun quoteIdentifier(name: String): String

    /** JDBC fetchSize für das Cursor-Streaming. */
    protected open val fetchSize: Int = 1_000

    /**
     * Ob `setAutoCommit(false)` vor `executeQuery` nötig ist.
     * - PostgreSQL: true (Cursor-Streaming braucht eine offene Transaktion)
     * - MySQL: false (mit `useCursorFetch=true` reicht der serverseitige Cursor)
     * - SQLite: false (kein Cursor-Konzept)
     */
    protected open val needsAutoCommitFalse: Boolean = true

    final override fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
    ): ChunkSequence {
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }

        // Connection borgen — alles weitere muss bei Exception aufgeräumt werden
        val conn = pool.borrow()
        var savedAutoCommit: Boolean? = null
        var stmt: PreparedStatement? = null
        var rs: ResultSet? = null
        try {
            savedAutoCommit = conn.autoCommit
            if (needsAutoCommitFalse) {
                conn.autoCommit = false
            }
            // M-R6: buildSelectQuery liefert jetzt SQL + zu bindende Params in einem
            // WhereFragment-Tripel, damit DataFilter.ParameterizedClause (LF-013,
            // siehe implementation-plan-0.4.0.md §3.8 / §6.12.1) sauber
            // parametrisiert werden kann, ohne String-Konkatenation.
            val query = buildSelectQuery(table, filter)
            stmt = conn.prepareStatement(
                query.sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY,
            ).also { it.fetchSize = fetchSize }
            bindParams(stmt, query.params)
            rs = stmt.executeQuery()

            return JdbcChunkSequence(
                table = table,
                rs = rs,
                stmt = stmt,
                conn = conn,
                savedAutoCommit = savedAutoCommit,
                chunkSize = chunkSize,
            )
        } catch (t: Throwable) {
            // Cleanup bei Setup-Fehler — nicht den ChunkSequence-Lifecycle aufrufen,
            // weil der noch nicht initialisiert ist.
            try { rs?.close() } catch (_: Throwable) {}
            try { stmt?.close() } catch (_: Throwable) {}
            try {
                if (savedAutoCommit != null && needsAutoCommitFalse) {
                    conn.rollback()
                    conn.autoCommit = savedAutoCommit
                }
            } catch (_: Throwable) {}
            try { conn.close() } catch (_: Throwable) {}
            throw t
        }
    }

    /**
     * Baut das SELECT-Statement für den Stream. Default: `SELECT <cols> FROM <table> [WHERE <filter>]`.
     * Treiber können das überschreiben, wenn sie spezielle Tricks brauchen.
     *
     * **M-R6**: Rückgabe ist jetzt ein [SelectQuery] mit SQL + Bind-Params,
     * damit [DataFilter.ParameterizedClause] positional gebundene Werte
     * mitführen kann. Pure [DataFilter.WhereClause]/[DataFilter.ColumnSubset]-
     * Pfade liefern `params = emptyList()` und verhalten sich identisch
     * zum 0.3.0-Vertrag.
     */
    protected open fun buildSelectQuery(table: String, filter: DataFilter?): SelectQuery {
        validateM5LiteralQuestionMark(filter)
        val columnList = projection(filter)
        val fragments = collectWhereFragments(filter)
        val sql = buildString {
            append("SELECT ").append(columnList)
            append(" FROM ").append(quoteTablePath(table))
            if (fragments.isNotEmpty()) {
                append(" WHERE ")
                when (fragments.size) {
                    1 -> append(fragments.single().sql)
                    else -> fragments.joinTo(this, " AND ") { "(${it.sql})" }
                }
            }
        }
        val flatParams = fragments.flatMap { it.params }
        return SelectQuery(sql, flatParams)
    }

    /**
     * Legacy-Shim für Treiber oder Tests aus 0.3.0, die nur den reinen
     * SQL-String gebraucht haben. Baut [buildSelectQuery] auf und wirft,
     * wenn der Filter Parameter enthalten würde — so fällt jeder
     * fälschlich String-basierte Zugriff auf [DataFilter.ParameterizedClause]
     * früh auf und verhindert stille Bind-Drift.
     */
    protected fun buildSelectSql(table: String, filter: DataFilter?): String {
        val query = buildSelectQuery(table, filter)
        check(query.params.isEmpty()) {
            "buildSelectSql(...) does not support parameterized filters; use buildSelectQuery(...)"
        }
        return query.sql
    }

    /**
     * Bindet die flach aggregierten Parameter aus [SelectQuery.params] per
     * 1-basierter `setObject(idx, value)`-Folge an das vorbereitete
     * Statement. Null-Werte gehen über den `setObject(idx, null)`-Pfad, der
     * von PG/MySQL/SQLite unterstützt wird; treiberspezifische Sonderpfade
     * können das bei Bedarf überschreiben.
     */
    protected open fun bindParams(stmt: PreparedStatement, params: List<Any?>) {
        if (params.isEmpty()) return
        for ((zeroBased, value) in params.withIndex()) {
            stmt.setObject(zeroBased + 1, value)
        }
    }

    /** Quotet einen evtl. schema-qualifizierten Tabellennamen `schema.table` Stück für Stück. */
    protected fun quoteTablePath(table: String): String =
        table.split('.').joinToString(".") { quoteIdentifier(it) }

    private fun projection(filter: DataFilter?): String {
        val cols = collectColumnSubset(filter)
        return if (cols == null) "*" else cols.joinToString(", ") { quoteIdentifier(it) }
    }

    private fun collectColumnSubset(filter: DataFilter?): List<String>? = when (filter) {
        null -> null
        is DataFilter.ColumnSubset -> filter.columns
        is DataFilter.WhereClause -> null
        is DataFilter.ParameterizedClause -> null
        is DataFilter.Compound -> filter.parts.firstNotNullOfOrNull { collectColumnSubset(it) }
    }

    /**
     * M-R5: Ein roher [DataFilter.WhereClause] mit literalem `?` darf nicht
     * im selben [DataFilter.Compound] wie eine [DataFilter.ParameterizedClause]
     * auftauchen. Sonst driftet die JDBC-Bind-Position des echten
     * Parameter-Clauses von den rohen `?`-Zeichen im String-Pfad weg.
     *
     * Der Plan ordnet die Prüfung dem CLI-Pre-Flight zu; wir erzwingen sie
     * zusätzlich hier auf Reader-Ebene, damit jeder direkte Programmatic-
     * Caller denselben Schutz bekommt und der Verbotstest nicht nur auf
     * CLI-Verhalten beruht.
     */
    private fun validateM5LiteralQuestionMark(filter: DataFilter?) {
        when (filter) {
            null,
            is DataFilter.WhereClause,
            is DataFilter.ColumnSubset,
            is DataFilter.ParameterizedClause,
                -> return

            is DataFilter.Compound -> {
                if (containsParameterizedClause(filter)) {
                    firstRawWhereClauseWithQuestionMark(filter)?.let { sql ->
                        throw IllegalArgumentException(
                            "Raw WhereClause must not contain '?' when combined with " +
                                "ParameterizedClause in the same Compound: $sql"
                        )
                    }
                }
                filter.parts.forEach(::validateM5LiteralQuestionMark)
            }
        }
    }

    private fun containsParameterizedClause(filter: DataFilter): Boolean = when (filter) {
        is DataFilter.ParameterizedClause -> true
        is DataFilter.Compound -> filter.parts.any(::containsParameterizedClause)
        else -> false
    }

    private fun firstRawWhereClauseWithQuestionMark(filter: DataFilter): String? = when (filter) {
        is DataFilter.WhereClause -> filter.sql.takeIf { it.contains('?') }
        is DataFilter.Compound -> filter.parts.firstNotNullOfOrNull(::firstRawWhereClauseWithQuestionMark)
        else -> null
    }

    /**
     * Sammelt alle WHERE-Fragmente (rohe + parametrisierte) aus dem Filter-
     * Baum in Traversierungsreihenfolge. [DataFilter.Compound] liefert
     * seine Parts linker-nach-rechts; das garantiert eine deterministische
     * `?`-Positionierung in der finalen SQL-Form.
     */
    private fun collectWhereFragments(filter: DataFilter?): List<WhereFragment> = when (filter) {
        null -> emptyList()
        is DataFilter.WhereClause -> listOf(WhereFragment(filter.sql, emptyList()))
        is DataFilter.ColumnSubset -> emptyList()
        is DataFilter.ParameterizedClause -> listOf(WhereFragment(filter.sql, filter.params))
        is DataFilter.Compound -> filter.parts.flatMap { collectWhereFragments(it) }
    }

    /**
     * Internes SQL-Fragment mit optionalen Bind-Params. [WhereClause] liefert
     * immer `params = emptyList()`; [ParameterizedClause] trägt seine
     * positional gebundenen Werte mit.
     */
    private data class WhereFragment(val sql: String, val params: List<Any?>)
}

/**
 * Ergebnis von [AbstractJdbcDataReader.buildSelectQuery]: das finale
 * SELECT-Statement plus die flach aggregierte Parameter-Liste in korrekter
 * `?`-Bind-Reihenfolge. Top-Level statt nested, damit Treiber-Module
 * (PG/MySQL/SQLite) den Rückgabetyp in eigenen `buildSelectQuery`-Overrides
 * benutzen können; `AbstractJdbcDataReader.buildSelectQuery` ist protected,
 * aber der Typ ist public, weil Kotlin-Module den eigenen `protected`-Scope
 * nicht über Modul-Kanten hinweg vererben.
 */
data class SelectQuery(val sql: String, val params: List<Any?>)

/**
 * Single-use [ChunkSequence] über ein offenes JDBC-[ResultSet].
 *
 * Garantiert §6.17: emittiert immer mindestens einen Chunk mit den
 * `columns` aus dem [ResultSetMetaData], auch wenn das ResultSet leer ist
 * (`rows = emptyList()`, `chunkIndex = 0`).
 *
 * `close()` führt rollback + autoCommit-Reset + conn.close() aus
 * (siehe §6.12) und ist idempotent.
 */
internal class JdbcChunkSequence(
    private val table: String,
    private val rs: ResultSet,
    private val stmt: PreparedStatement,
    private val conn: Connection,
    private val savedAutoCommit: Boolean,
    private val chunkSize: Int,
) : ChunkSequence {

    private var iteratorRequested = false
    private var closed = false

    override fun iterator(): Iterator<DataChunk> {
        check(!iteratorRequested) {
            "ChunkSequence already consumed; JDBC ResultSet cannot be re-iterated"
        }
        check(!closed) {
            "ChunkSequence is closed"
        }
        iteratorRequested = true
        return JdbcChunkIterator()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { rs.close() } catch (_: Throwable) {}
        try { stmt.close() } catch (_: Throwable) {}
        try { conn.rollback() } catch (_: Throwable) {}
        try { conn.autoCommit = savedAutoCommit } catch (_: Throwable) {}
        try { conn.close() } catch (_: Throwable) {}
    }

    private inner class JdbcChunkIterator : Iterator<DataChunk> {
        private val columns: List<ColumnDescriptor> = readColumnMetadata()
        private val columnCount: Int = columns.size
        private var chunkIndex: Long = 0
        private var nextChunk: DataChunk? = null
        private var firstChunkEmitted = false
        private var rsExhausted = false

        init {
            // Erst-Chunk vorladen oder leeren Chunk vorbereiten
            prepareNext()
        }

        override fun hasNext(): Boolean = nextChunk != null

        override fun next(): DataChunk {
            val chunk = nextChunk ?: throw NoSuchElementException("ChunkSequence exhausted")
            firstChunkEmitted = true
            prepareNext()
            // Auto-close wenn das die letzte Iteration ist (chunk war der letzte)
            if (nextChunk == null) {
                close()
            }
            return chunk
        }

        private fun prepareNext() {
            if (rsExhausted) {
                nextChunk = null
                return
            }
            val rows = ArrayList<Array<Any?>>(chunkSize)
            while (rows.size < chunkSize && rs.next()) {
                val row = arrayOfNulls<Any?>(columnCount)
                for (i in 0 until columnCount) {
                    row[i] = rs.getObject(i + 1)
                }
                rows += row
            }
            if (rows.size < chunkSize) {
                rsExhausted = true
            }
            if (rows.isEmpty() && firstChunkEmitted) {
                // ResultSet ist nach dem letzten echten Chunk vollständig konsumiert
                nextChunk = null
                return
            }
            // §6.17: Auch wenn rows leer sind UND noch kein Chunk emittiert wurde,
            // einen Empty-Chunk mit columns liefern.
            nextChunk = DataChunk(
                table = table,
                columns = columns,
                rows = rows,
                chunkIndex = chunkIndex++,
            )
        }

        private fun readColumnMetadata(): List<ColumnDescriptor> {
            val md = rs.metaData
            val n = md.columnCount
            val list = ArrayList<ColumnDescriptor>(n)
            for (i in 1..n) {
                list += ColumnDescriptor(
                    name = md.getColumnLabel(i),
                    nullable = md.isNullable(i) != java.sql.ResultSetMetaData.columnNoNulls,
                    sqlTypeName = runCatching { md.getColumnTypeName(i) }.getOrNull(),
                )
            }
            return list
        }
    }
}
