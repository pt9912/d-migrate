package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import org.postgresql.PGConnection
import java.io.StringReader
import java.sql.Connection
import java.sql.Types

/**
 * COPY-Bulk-Fast-Path für den PostgreSQL-Import (`import-throughput-copy-path.md`, zweiter Hebel).
 *
 * COPY (pgjdbc `CopyManager`) ist der native Bulk-Pfad und schließt den Abstand zur COPY-Decke
 * (~460k rows/s), greift aber **nur dort, wo er KORREKT ist**. COPY ist ein roher Wert-Stream:
 * er führt keine Per-Wert-SQL-Ausdrücke aus und kennt kein `ON CONFLICT` / kein
 * `OVERRIDING SYSTEM VALUE`. Daher die harten Sperren (Aufrufer garantiert bereits ABORT):
 * keine `GENERATED ALWAYS`-Wert-Übernahme, kein SQL-Wrapping (Geometrie), nur eindeutig
 * COPY-TEXT-kodierbare Skalartypen ([COPY_TEXT_SAFE_JDBC_TYPES]). Trifft etwas nicht zu →
 * Rückfall auf den (mit `reWriteBatchedInserts` ohnehin schnelleren) Batch-INSERT, kein
 * Korrektheitsrisiko. Verlustfreiheit hart über den 4c-SHA-256.
 *
 * Vom [PostgresTableImportSession] herausgelöst (eigene kohäsive Verantwortung; hält den
 * Session-Umfang unter dem Detekt-Limit, kein @Suppress). Encoding: [PostgresCopyText].
 */
internal object PostgresCopyFastPath {

    /**
     * Ob der COPY-Fast-Path für diesen Chunk zulässig ist. Konservativ text-sicher gescopt:
     * jede Spalte bindet ein nacktes `?` über `setObject` (kein SQL-Wrapping, kein PGobject/Array)
     * **und** trägt einen eindeutig COPY-TEXT-kodierbaren Skalartyp; keine `GENERATED ALWAYS`-
     * Wert-Übernahme. [isGeometry]/[isEnum] sind die session-spezifischen Spalten-Prädikate.
     */
    fun isEligible(
        columns: List<TargetColumn>,
        generatedAlwaysColumns: Set<String>,
        isGeometry: (TargetColumn) -> Boolean,
        isEnum: (TargetColumn) -> Boolean,
    ): Boolean =
        columns.isNotEmpty() &&
            columns.none { it.name in generatedAlwaysColumns } &&
            columns.all { !isGeometry(it) && !isEnum(it) && it.jdbcType in COPY_TEXT_SAFE_JDBC_TYPES }

    /**
     * Streamt den Chunk per `COPY … FROM STDIN WITH (FORMAT text)`. Unter ABORT ist COPY
     * all-or-nothing: ein Konflikt/Parse-Fehler wirft (`copyIn`) → kein Teilstand, gleiche
     * Fehlersemantik wie der INSERT-Pfad. `copyIn` liefert die Zeilenzahl → `rowsInserted`
     * (gröberes Accounting als der INSERT-Pfad, bewusste Sperre).
     */
    fun execute(
        conn: Connection,
        quotedTablePath: String,
        columns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        if (rows.isEmpty()) return WriteResult(rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 0)
        val columnList = columns.joinToString(", ") { quotePostgresIdentifier(it.name) }
        val sql = "COPY $quotedTablePath ($columnList) FROM STDIN WITH (FORMAT text)"
        val copied = conn.unwrap(PGConnection::class.java).copyAPI
            .copyIn(sql, StringReader(PostgresCopyText.encode(rows)))
        return WriteResult(rowsInserted = copied, rowsUpdated = 0, rowsSkipped = 0)
    }

    /**
     * JDBC-Typen, deren Werte eindeutig + verlustfrei in COPY-TEXT kodierbar sind
     * (kanonische `toString()`/`toPlainString()`, PG-akzeptiertes Text-Input). Bewusst
     * KONSERVATIV: keine `Types.OTHER` (json/jsonb/interval/xml/uuid), kein `ARRAY`, kein
     * `BINARY`/bytea — die bleiben beim INSERT-Pfad. Benannte Enums sind `VARCHAR`, werden aber
     * über das `isEnum`-Prädikat separat ausgeschlossen.
     */
    private val COPY_TEXT_SAFE_JDBC_TYPES = setOf(
        Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT,
        Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE,
        Types.BOOLEAN, Types.BIT,
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
        Types.DATE, Types.TIME, Types.TIMESTAMP,
    )
}
