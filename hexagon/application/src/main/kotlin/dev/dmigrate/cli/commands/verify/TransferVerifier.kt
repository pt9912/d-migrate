package dev.dmigrate.cli.commands.verify

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.verify.ValueCanonicalizationException
import dev.dmigrate.verify.ValueCanonicalizer

/**
 * LN-009: Quelle↔Ziel-Reconciliation eines abgeschlossenen Transfers.
 *
 * Streamt je Tabelle Quelle **und** Ziel erneut, kanonisiert die Werte
 * dialekt-neutral (jede Seite gegen ihren **eigenen** reverse-engineerten
 * Spaltentyp) und vergleicht die reihenfolge-unabhängigen SHA-256-Tabellen-
 * Prüfsummen samt Zeilenzahl.
 *
 * **Familien-basierter Ausschluss (ADR 0030).** Byte-Kanonik kann eine
 * *repräsentations-transformierende* Cross-Dialekt-Konversion (z. B. `text[]`→
 * `json`, `tsvector`→`text`, tz-behaftet→lokal) nicht bestätigen: die Quelle
 * liefert weiter den quell-typisierten Wert. Solche Spalten — deren Quell- und
 * Zieltyp in **verschiedenen Kanonik-Familien** liegen — werden mit einem W-Code
 * **ausgeschlossen** (kein False-Positive), nicht als Divergenz gemeldet.
 * Innerhalb einer Familie (bool↔int, uuid↔text, decimal-Weite, temporal gleicher
 * Art) kollidieren die kanonischen Formen und werden verglichen.
 *
 * Vertrag: exakte Übereinstimmung setzt einen **sauberen Load** voraus (leeres/
 * getrunctes Ziel). Ein Wert, der nicht kanonisiert werden kann, macht die
 * Tabelle **inkonklusiv** (Fehler im Ergebnis) — nie ein stiller Pass.
 */
/**
 * Eine Seite des Vergleichs: woher gelesen wird und wonach.
 *
 * Reader, Pool und Schema traten immer gemeinsam auf, einmal fuer die Quelle
 * und einmal fuer das Ziel — sechs Parameter, die drei Begriffe waren. Als
 * eigener Typ ist ausserdem nicht mehr verwechselbar, welche Haelfte gemeint
 * ist: `verify(source, target)` statt sechs gleichartiger Argumente in Folge.
 */
data class VerifySide(
    val reader: DataReader,
    val pool: ConnectionPool,
    val schema: SchemaDefinition,
)

class TransferVerifier(
    private val canonicalizer: ValueCanonicalizer,
    /**
     * Ob der Lauf eine **erklaerte Ersetzung** des leeren Strings vornimmt
     * (Oracle-Ziel, `write.oracle.empty_string: literal:<text>`). Dann ist
     * eine `NOT NULL`-Textspalte nicht mehr byte-verifizierbar -- d-migrate
     * hat den Wert auf Anweisung des Anwenders geaendert, genau wie bei den
     * Darstellungs-Umformungen unten. Ohne Ersetzung (Default `error`) aendert
     * sich nichts.
     */
    private val substitutesEmptyStrings: Boolean = false,
    /**
     * Ob das Ziel den leeren String als NULL speichert (Oracle). Dann sind
     * `''` in der Quelle und `NULL` im Ziel **derselbe** Wert, und der
     * Vergleich muss sie gleich behandeln -- sonst meldet er eine Abweichung,
     * die keine ist. Betrifft nullbare Spalten und gilt unabhaengig von jeder
     * Praeferenz; bei `NOT NULL` greift stattdessen der Ausschluss oben.
     */
    private val targetFoldsEmptyStringToNull: Boolean = false,
) {

    fun verify(
        tables: List<String>,
        source: VerifySide,
        target: VerifySide,
        filter: DataFilter?,
        chunkSize: Int,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): VerifyReport {
        val results = tables.map { table ->
            cancellationToken.throwIfCancellationRequested()
            verifyTable(table, source, target, filter, chunkSize, cancellationToken)
        }
        return VerifyReport(results)
    }

    private fun verifyTable(
        table: String,
        source: VerifySide,
        target: VerifySide,
        filter: DataFilter?,
        chunkSize: Int,
        cancellationToken: CancellationToken,
    ): TableVerifyResult {
        val sourceColumns = columnsFor(source.schema, table)
        val targetColumns = columnsFor(target.schema, table)
        val shared = sourceColumns.keys.intersect(targetColumns.keys).sorted()

        val excluded = mutableListOf<ColumnExclusion>()
        val active = mutableListOf<String>()
        for (column in shared) {
            val reason = exclusionReason(
                sourceColumns.getValue(column),
                targetColumns.getValue(column),
            )
            if (reason != null) excluded.add(ColumnExclusion(table, column, reason)) else active.add(column)
        }
        val sourceTypes = active.associateWith { sourceColumns.getValue(it).type }
        val targetTypes = active.associateWith { targetColumns.getValue(it).type }

        val scope = ChecksumScope(table, filter, chunkSize, active, cancellationToken)
        return try {
            val sourceSum = checksum(source, scope, sourceTypes)
            val targetSum = checksum(target, scope, targetTypes)
            TableVerifyResult(
                table = table,
                sourceRows = sourceSum.rowCount(),
                targetRows = targetSum.rowCount(),
                sourceHash = sourceSum.digestHex(),
                targetHash = targetSum.digestHex(),
                excluded = excluded,
            )
        } catch (e: ValueCanonicalizationException) {
            TableVerifyResult(table, 0, 0, "", "", excluded, error = e.message)
        }
    }

    /**
     * Was fuer beide Seiten gleich ist: welche Tabelle, welcher Ausschnitt,
     * welche Spalten. Nur die Typen unterscheiden sich je Seite — sie bleiben
     * deshalb eigener Parameter.
     */
    private data class ChecksumScope(
        val table: String,
        val filter: DataFilter?,
        val chunkSize: Int,
        val active: List<String>,
        val cancellationToken: CancellationToken,
    )

    private fun checksum(
        side: VerifySide,
        scope: ChecksumScope,
        types: Map<String, NeutralType>,
    ): TableChecksum {
        val checksum = TableChecksum()
        side.reader.streamTable(side.pool, scope.table, scope.filter, scope.chunkSize).use { sequence ->
            for (chunk in sequence) {
                scope.cancellationToken.throwIfCancellationRequested()
                val indexByName = chunk.columns.withIndex().associate { (i, c) -> c.name to i }
                for (row in chunk.rows) {
                    checksum.addRow(
                        scope.active.map { column ->
                            val index = indexByName[column] ?: return@map null
                            foldEmptyString(row[index])?.let { canonicalizer.canonicalize(it, types.getValue(column)) }
                        },
                    )
                }
            }
        }
        return checksum
    }

    /** `''` und `NULL` sind im Ziel derselbe Wert -- beide Seiten auf NULL bringen. */
    private fun foldEmptyString(value: Any?): Any? =
        if (targetFoldsEmptyStringToNull && value == "") null else value

    /** Spalten einer (ggf. schema-qualifizierten) Tabelle aus dem Schema. */
    private fun columnsFor(schema: SchemaDefinition, table: String): Map<String, ColumnDefinition> {
        schema.tables[table]?.let { return it.columns }
        val bare = table.substringAfterLast('.')
        schema.tables[bare]?.let { return it.columns }
        return schema.tables.entries.firstOrNull { it.key.substringAfterLast('.') == bare }?.value?.columns ?: emptyMap()
    }

    /** Grund für einen Spalten-Ausschluss, oder null wenn beide Seiten byte-verifizierbar sind. */
    private fun exclusionReason(sourceColumn: ColumnDefinition, targetColumn: ColumnDefinition): String? {
        val source = sourceColumn.type
        val target = targetColumn.type
        // Eine erklaerte Ersetzung veraendert genau die Spalten, die NOT NULL
        // und textartig sind -- dort kann ein leerer Quellwert durch den
        // Ersatztext ersetzt worden sein. Welche Zeile es traf, weiss der
        // Vergleich nicht; byte-verifizierbar ist die Spalte damit nicht mehr.
        if (substitutesEmptyStrings && targetColumn.required && canonicalFamily(target) == "text") {
            return "declared empty-string substitution (write.oracle.empty_string), not byte-verifiable"
        }
        val sourceFamily = canonicalFamily(source)
        val targetFamily = canonicalFamily(target)
        if (sourceFamily != targetFamily) {
            return "cross-dialect representation transform ($sourceFamily -> $targetFamily), not byte-verifiable"
        }
        if (source is NeutralType.Float && target is NeutralType.Float && source.floatPrecision != target.floatPrecision) {
            return "float width mismatch (${source.floatPrecision} vs ${target.floatPrecision})"
        }
        return null
    }

    /**
     * Kanonik-Familie eines Neutraltyps. Zwei Typen sind byte-verifizierbar
     * kompatibel, wenn ihre Familien übereinstimmen — dann liefern die
     * kanonischen Formen für gleiche logische Werte identische Bytes.
     */
    private fun canonicalFamily(type: NeutralType): String = when (type) {
        is NeutralType.Text, NeutralType.Xml, NeutralType.Email, is NeutralType.Enum,
        is NeutralType.Char, NeutralType.Uuid -> "text"
        NeutralType.Integer, NeutralType.SmallInt, NeutralType.BigInteger,
        is NeutralType.Identifier, is NeutralType.Decimal, NeutralType.BooleanType -> "numeric"
        is NeutralType.Float -> "float"
        NeutralType.Date -> "date"
        NeutralType.Time -> "time"
        is NeutralType.DateTime -> if (type.timezone) "datetime-tz" else "datetime-local"
        NeutralType.Json -> "json"
        is NeutralType.Array -> "array"
        NeutralType.Binary -> "binary"
        is NeutralType.Geometry -> "geometry"
        NeutralType.FullText -> "fulltext"
    }
}
