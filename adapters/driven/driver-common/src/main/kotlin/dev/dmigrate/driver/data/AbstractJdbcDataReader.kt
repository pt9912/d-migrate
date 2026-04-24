package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Gemeinsame Implementierung des [DataReader]-Vertrags für alle JDBC-basierten
 * Treiber. Konkrete Treiber überschreiben:
 *
 * - [quoteIdentifier] — Treiber-spezifisches Quoting (`"x"` für PG/SQLite, `` `x` `` für MySQL)
 * - [fetchSize] — Treiber-interne Streaming-Tuning-Konstante
 * - [needsAutoCommitFalse] — ob `setAutoCommit(false)` für Cursor-Streaming nötig ist
 *   (PostgreSQL: ja; MySQL mit useCursorFetch: nein zwingend; SQLite: irrelevant)
 * - optional [buildSelectSql] für treiberspezifische Variationen
 *
 * Der JDBC-Lifecycle ist hier zentral implementiert: jede streamTable-Operation
 * läuft in einer eigenen Transaktion, die in [ChunkSequence.close] mit
 * `rollback()`, `setAutoCommit(true)` und `conn.close()` (= Hikari-Return)
 * abgeschlossen wird — auch bei Exception.
 *
 * Auch leere Tabellen emittieren einen Chunk mit den Spaltenmetadaten und
 * `rows = emptyList()`.
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

    /** Mid-Table-Resume nutzt denselben JDBC-Lifecycle wie ein normaler Stream. */
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
            // buildSelectQuery liefert SQL + Bind-Parameter, damit Filter und
            // Resume-Marker ohne String-Konkatenation parametrisiert bleiben.
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
        val columnList = JdbcSelectQuerySupport.projection(filter, ::quoteIdentifier)
        val fragments = JdbcSelectQuerySupport.collectWhereFragments(filter).toMutableList()
        // Marker-Position liefert ggf. eine zusaetzliche WHERE-Cascade;
        // die Ordering (ORDER BY) gilt in jedem Fall, sobald ein
        // ResumeMarker gesetzt ist — auch im Fresh-Track-Modus ohne
        // Position, damit ein spaeteres Resume dieselbe Ordnung
        // reproduzieren kann.
        val markerFragment = resumeMarker?.position?.let {
            JdbcSelectQuerySupport.buildMarkerFragment(resumeMarker, it, ::quoteIdentifier)
        }
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

}
