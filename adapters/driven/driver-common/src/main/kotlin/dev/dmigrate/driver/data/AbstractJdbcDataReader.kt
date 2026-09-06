package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import java.sql.Connection
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

    /**
     * VA1b (Spatial-Slice): ob der Treiber Geometriespalten auf dem Read-Pfad in
     * ein kanonisches Binärformat projizieren kann (PostGIS/MySQL native). Ist es
     * `true`, führt der Reader vor dem Haupt-Stream eine billige Metadaten-
     * Vorabfrage aus (`SELECT * … WHERE 1 = 0`), um Geometriespalten zu finden,
     * und wrappt sie via [geometryReadExpression]. Default `false` → keine
     * Vorabfrage, kein Overhead (SQLite/SpatiaLite folgt mit VA4).
     */
    protected open val supportsGeometryRead: Boolean = false

    /**
     * Ob **diese Verbindung** Geometrie als WKB lesen kann.
     *
     * Bei den meisten Dialekten haengt das allein am Treiber, und die Antwort
     * ist [supportsGeometryRead]. SQLite entscheidet es je Verbindung: die
     * Geometriefunktionen kommen aus `mod_spatialite`, und das ist nur geladen,
     * wenn die Verbindung es angefordert hat.
     */
    protected open fun supportsGeometryRead(conn: Connection): Boolean = supportsGeometryRead

    /**
     * VA1b: dialekt-spezifischer Read-Ausdruck für eine Geometriespalte
     * (bereits gequotet), z. B. `ST_AsEWKB("g")` (PostGIS) oder `ST_AsBinary(\`g\`)`
     * (MySQL). Default: unverändert (kein Wrap) — nur relevant, wenn
     * [supportsGeometryRead] `true` ist.
     */
    protected open fun geometryReadExpression(quotedColumn: String): String = quotedColumn

    /**
     * VA1 (Spatial-Slice): **dialekt-bewusste** Erkennung, ob ein (lowercase)
     * `getColumnTypeName` eine WKB-fähige Geometriespalte bezeichnet. Default
     * `false`. PostGIS überschreibt mit nur `"geometry"` — NICHT die nativen
     * PG-Typen `point`/`polygon`/`line`/`box`/`path`/`circle`/`lseg`, die genauso
     * heißen wie OGC-Subtypen, aber kein WKB sind (sonst würde `ST_AsBinary` sie
     * fälschlich wrappen → Query-Fehler). MySQL überschreibt mit allen OGC-Namen.
     */
    protected open fun isGeometryTypeName(typeNameLower: String): Boolean = false

    /**
     * Wert-Naht: uebersetzt treibereigene Rueckgabetypen in Standardtypen, bevor
     * sie in den neutralen Chunk-Strom gelangen. Default: unveraendert. MSSQL
     * nutzt sie fuer `microsoft.sql.DateTimeOffset` → [java.time.OffsetDateTime]
     * — ohne das ist der Wert weder verify-kanonisierbar noch sauber
     * serialisierbar. Die Connection ist Teil der Signatur, weil Oracles
     * `TIMESTAMPTZ` → `OffsetDateTime`-Konvertierung sie zwingend braucht
     * (benannte Zeitzonen-Aufloesung); Treiber, die sie nicht brauchen,
     * ignorieren den Parameter.
     */
    protected open fun mapValue(value: Any?, conn: Connection): Any? = value

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
        val conn = pool.borrow().asJdbc()
        var savedAutoCommit: Boolean? = null
        var stmt: PreparedStatement? = null
        var rs: ResultSet? = null
        try {
            savedAutoCommit = conn.autoCommit
            if (needsAutoCommitFalse) {
                conn.autoCommit = false
            }
            // VA1b: Treiber mit Geometrie-Read-Support proben vorab die
            // Spaltentypen (billige WHERE-1=0-Query), damit Geometriespalten in
            // der Projektion in ein kanonisches Binärformat gewrappt werden.
            val probedColumns = if (supportsGeometryRead(conn)) probeColumns(conn, table) else emptyList()
            // buildSelectQuery liefert SQL + Bind-Parameter, damit Filter und
            // Resume-Marker ohne String-Konkatenation parametrisiert bleiben.
            val query = buildSelectQuery(table, filter, resumeMarker, probedColumns)
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
                // VA1b/R2: die gewrappte Geometriespalte meldet als JDBC-Typ
                // bytea/blob; ohne diese Liste trüge der Chunk-Header `Binary`
                // statt `Geometry`. Die dialekt-bewusste Markierung aus der
                // Vorabfrage überschreibt das im ChunkSchema.
                geometryColumns = probedColumns.filter { it.isGeometry }.mapTo(HashSet()) { it.name },
                valueMapper = ::mapValue,
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
     * LF-013 / LN-006 / LN-012: Overload mit optionalem [resumeMarker].
     * Wenn gesetzt, wird dem bestehenden WHERE-Baum eine Marker-Cascade
     * angehaengt und die Projektion zusaetzlich deterministisch
     * sortiert (`ORDER BY markerColumn, tieBreakers...` in ASC).
     *
     * Treiber, die [buildSelectQuery] ueberschreiben, koennen diese
     * Overload ebenfalls ueberschreiben oder sich auf die
     * Default-Delegation verlassen (ohne Marker-Pfad bleibt das SQL
     * identisch zum Basis-Streaming-Vertrag).
     */
    protected open fun buildSelectQuery(
        table: String,
        filter: DataFilter?,
        resumeMarker: ResumeMarker?,
    ): SelectQuery = buildSelectQuery(table, filter, resumeMarker, emptyList())

    /**
     * VA1b: Overload mit den vorab geprobten Spalten ([probedColumns]). Enthält
     * einen Geometrie-Treffer mindestens eine Spalte, baut die Projektion
     * geometrie-bewusst (`<geometryReadExpression>(col) AS col`); sonst bleibt
     * sie identisch zum Basis-Vertrag (`*` bzw. ColumnSubset). WHERE- und
     * ORDER-BY-Logik sind unverändert. Leere [probedColumns] (Default / Treiber
     * ohne [supportsGeometryRead]) reproduzieren exakt das alte SQL.
     */
    protected open fun buildSelectQuery(
        table: String,
        filter: DataFilter?,
        resumeMarker: ResumeMarker?,
        probedColumns: List<ProbedColumn>,
    ): SelectQuery {
        // M-R5 validation removed in 0.9.3: WhereClause no longer exists,
        // all user filters are ParameterizedClause from the DSL parser.
        val columnList = if (probedColumns.any { it.isGeometry }) {
            JdbcSelectQuerySupport.geometryAwareProjection(
                filter, probedColumns, ::quoteIdentifier, ::geometryReadExpression,
            )
        } else {
            JdbcSelectQuerySupport.projection(filter, ::quoteIdentifier)
        }
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

    /**
     * VA1b: billige Metadaten-Vorabfrage (`SELECT * … WHERE 1 = 0`, keine Zeilen),
     * um pro Spalte Name + Geometrie-Markierung zu ermitteln. Nur aufgerufen, wenn
     * [supportsGeometryRead]. Ueberschreibbar fuer Dialekte, denen der Typname
     * allein nicht genuegt — SQLite etwa erzwingt keine Typen, dort sagt erst
     * der Geometrie-Katalog, welche Spalte wirklich eine ist. Die Auswertung der Metadaten ist in
     * [JdbcSelectQuerySupport.probedColumnsFromMetaData] isoliert (testbar).
     */
    protected open fun probeColumns(conn: Connection, table: String): List<ProbedColumn> =
        conn.prepareStatement("SELECT * FROM ${quoteTablePath(table)} WHERE 1 = 0").use { ps ->
            ps.executeQuery().use { rs ->
                JdbcSelectQuerySupport.probedColumnsFromMetaData(rs.metaData, ::isGeometryTypeName)
            }
        }

}
