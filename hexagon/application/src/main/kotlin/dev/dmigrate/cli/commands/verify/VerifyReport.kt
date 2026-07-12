package dev.dmigrate.cli.commands.verify

/**
 * LN-009: Ergebnis der Quelle↔Ziel-Verifikation eines Transfers.
 */
data class VerifyReport(val tables: List<TableVerifyResult>) {
    /** True, wenn jede Tabelle abgeglichen werden konnte und übereinstimmt. */
    val allMatch: Boolean get() = tables.all { it.match }

    /** Alle spaltenweise Verify-Ausschlüsse (nicht kanonisierbar). */
    val exclusions: List<ColumnExclusion> get() = tables.flatMap { it.excluded }
}

/**
 * Verify-Ergebnis einer Tabelle. [match] ist genau dann true, wenn kein Fehler
 * auftrat, Zeilenzahlen übereinstimmen und die (reihenfolge-unabhängigen)
 * SHA-256-Prüfsummen der verglichenen Spalten identisch sind.
 */
data class TableVerifyResult(
    val table: String,
    val sourceRows: Long,
    val targetRows: Long,
    val sourceHash: String,
    val targetHash: String,
    val excluded: List<ColumnExclusion> = emptyList(),
    /** Gesetzt, wenn die Tabelle nicht abgeglichen werden konnte (z. B. Wert nicht kanonisierbar). */
    val error: String? = null,
) {
    val match: Boolean
        get() = error == null && sourceRows == targetRows && sourceHash == targetHash
}

/** Eine Spalte, die aus dem Verify ausgeschlossen wurde (nicht deterministisch kanonisierbar). */
data class ColumnExclusion(val table: String, val column: String, val reason: String)
