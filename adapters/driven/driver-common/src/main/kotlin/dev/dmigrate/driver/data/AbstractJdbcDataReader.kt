package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import org.slf4j.LoggerFactory
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
    ): ChunkSequence = streamTableInternal(
        pool = pool,
        table = table,
        filter = filter,
        chunkSize = chunkSize,
        resumeMarker = null,
    )

    /**
     * 0.9.0 Phase C.2: Mid-Table-Resume-Pfad. Delegiert an den
     * gemeinsamen JDBC-Lifecycle. Marker-Paging-spezifische SQL-
     * Erzeugung geschieht in [buildSelectQuery].
     */
    final override fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
        resumeMarker: ResumeMarker?,
    ): ChunkSequence = streamTableInternal(
        pool = pool,
        table = table,
        filter = filter,
        chunkSize = chunkSize,
        resumeMarker = resumeMarker,
    )

    private fun streamTableInternal(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
        resumeMarker: ResumeMarker?,
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
            // 0.9.0 Phase C.2: zusaetzlich wird ein optionaler `resumeMarker`
            // in die SQL-Erzeugung verschoben (lexikografischer Composite-
            // Marker-Filter + deterministische Sortierung).
            val query = buildSelectQuery(table, filter, resumeMarker)
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
            t.runSuppressing { rs?.close() }
            t.runSuppressing { stmt?.close() }
            t.runSuppressing {
                if (savedAutoCommit != null && needsAutoCommitFalse) {
                    conn.rollback()
                    conn.autoCommit = savedAutoCommit
                }
            }
            t.runSuppressing { conn.close() }
            throw t
        }
    }

    /**
     * Baut das SELECT-Statement für den Stream. Default: `SELECT <cols> FROM <table> [WHERE <filter>]`.
     * Treiber können das überschreiben, wenn sie spezielle Tricks brauchen.
     *
     * **M-R6**: Rückgabe ist jetzt ein [SelectQuery] mit SQL + Bind-Params,
     * damit [DataFilter.ParameterizedClause] positional gebundene Werte
     * mitführen kann. [DataFilter.ColumnSubset]-Pfade liefern
     * `params = emptyList()`.
     */
    protected open fun buildSelectQuery(table: String, filter: DataFilter?): SelectQuery =
        buildSelectQuery(table, filter, resumeMarker = null)

    /**
     * 0.9.0 Phase C.2: Overload mit optionalem [resumeMarker]. Wenn
     * gesetzt, wird dem bestehenden WHERE-Baum eine Marker-Cascade
     * angehaengt und die Projektion zusaetzlich deterministisch
     * sortiert (`ORDER BY markerColumn, tieBreakers...` in ASC).
     *
     * Treiber, die [buildSelectQuery] ueberschreiben, koennen diese
     * Overload ebenfalls ueberschreiben oder sich auf die
     * Default-Delegation verlassen (ohne Marker-Pfad bleibt das SQL
     * identisch zum 0.3.0/0.4.0-Vertrag).
     */
    protected open fun buildSelectQuery(
        table: String,
        filter: DataFilter?,
        resumeMarker: ResumeMarker?,
    ): SelectQuery {
        // M-R5 validation removed in 0.9.3: WhereClause no longer exists,
        // all user filters are ParameterizedClause from the DSL parser.
        val columnList = projection(filter)
        val fragments = collectWhereFragments(filter).toMutableList()
        // Marker-Position liefert ggf. eine zusaetzliche WHERE-Cascade;
        // die Ordering (ORDER BY) gilt in jedem Fall, sobald ein
        // ResumeMarker gesetzt ist — auch im Fresh-Track-Modus ohne
        // Position, damit ein spaeteres Resume dieselbe Ordnung
        // reproduzieren kann.
        val markerFragment = resumeMarker?.position?.let { buildMarkerFragment(resumeMarker, it) }
        if (markerFragment != null) fragments += markerFragment
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
            if (resumeMarker != null) {
                append(" ORDER BY ")
                append(quoteIdentifier(resumeMarker.markerColumn)).append(" ASC")
                for (tieBreaker in resumeMarker.tieBreakerColumns) {
                    append(", ").append(quoteIdentifier(tieBreaker)).append(" ASC")
                }
            }
        }
        val flatParams = fragments.flatMap { it.params }
        return SelectQuery(sql, flatParams)
    }

    /**
     * 0.9.0 Phase C.2 §4.1: erzeugt den Composite-Marker-Filter fuer
     * `(markerColumn, tieBreakers...) > (lastMarkerValue, lastTieBreakerValues...)`
     * in lexikografischer Reihenfolge. Ohne Tie-Breaker entartet der
     * Ausdruck zu einem strikten `markerColumn > ?`.
     *
     * Das Ergebnis ist ein einzelnes [WhereFragment], das im
     * gemeinsamen WHERE-Pfad mit den uebrigen Filter-Fragmenten per
     * `AND` verknuepft wird.
     *
     * Nullable Marker- oder Tie-Breaker-Spalten sind eine
     * Nutzereinschraenkung (siehe `docs/ImpPlan-0.9.0-C2.md` §8.2):
     * `>`-Vergleiche gegen NULL sind immer UNKNOWN, der Resume-Pfad
     * filtert betroffene Zeilen deshalb aus.
     */
    private fun buildMarkerFragment(
        marker: ResumeMarker,
        position: ResumeMarker.Position,
    ): WhereFragment {
        val markerCol = quoteIdentifier(marker.markerColumn)
        if (marker.tieBreakerColumns.isEmpty()) {
            return WhereFragment("$markerCol > ?", listOf(position.lastMarkerValue))
        }
        val tieCascade = buildTieCascade(
            marker.tieBreakerColumns.map(::quoteIdentifier),
            position.lastTieBreakerValues,
        )
        val sql = "$markerCol > ? OR ($markerCol = ? AND (${tieCascade.sql}))"
        val params = listOf<Any?>(position.lastMarkerValue, position.lastMarkerValue) +
            tieCascade.params
        return WhereFragment(sql, params)
    }

    private fun buildTieCascade(cols: List<String>, values: List<Any?>): WhereFragment {
        // [c1] [v1] -> "c1 > ?"                  params = [v1]
        // [c1,c2] [v1,v2] -> "c1 > ? OR (c1 = ? AND c2 > ?)"
        //                                        params = [v1, v1, v2]
        // [c1,c2,c3] [v1,v2,v3] ->
        //   "c1 > ? OR (c1 = ? AND (c2 > ? OR (c2 = ? AND c3 > ?)))"
        //                                        params = [v1, v1, v2, v2, v3]
        require(cols.size == values.size) {
            "tie-cascade cols/values size mismatch: ${cols.size} vs ${values.size}"
        }
        require(cols.isNotEmpty()) { "tie-cascade requires at least one column" }
        if (cols.size == 1) {
            return WhereFragment("${cols[0]} > ?", listOf(values[0]))
        }
        val rest = buildTieCascade(cols.drop(1), values.drop(1))
        val sql = "${cols[0]} > ? OR (${cols[0]} = ? AND (${rest.sql}))"
        val params = listOf<Any?>(values[0], values[0]) + rest.params
        return WhereFragment(sql, params)
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
        is DataFilter.ParameterizedClause -> null
        is DataFilter.Compound -> filter.parts.firstNotNullOfOrNull { collectColumnSubset(it) }
    }

    /**
     * Sammelt alle WHERE-Fragmente aus dem Filter-Baum in
     * Traversierungsreihenfolge. [DataFilter.Compound] liefert seine Parts
     * links-nach-rechts; das garantiert eine deterministische
     * `?`-Positionierung in der finalen SQL-Form.
     */
    private fun collectWhereFragments(filter: DataFilter?): List<WhereFragment> = when (filter) {
        null -> emptyList()
        is DataFilter.ColumnSubset -> emptyList()
        is DataFilter.ParameterizedClause -> listOf(WhereFragment(filter.sql, filter.params))
        is DataFilter.Compound -> filter.parts.flatMap { collectWhereFragments(it) }
    }

    /**
     * Internes SQL-Fragment mit Bind-Params. [ParameterizedClause] trägt seine
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

    private val log = LoggerFactory.getLogger(JdbcChunkSequence::class.java)
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
        tryClose { rs.close() }
        tryClose { stmt.close() }
        tryClose { conn.rollback() }
        tryClose { conn.autoCommit = savedAutoCommit }
        tryClose { conn.close() }
    }

    private inline fun tryClose(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            log.debug("Cleanup failure while closing ChunkSequence for table '{}': {}", table, t.message)
        }
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
