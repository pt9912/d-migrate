package dev.dmigrate.streaming

/**
 * Statistik-Aggregat einer [StreamingExporter]-Operation. Wird vom CLI
 * zur stderr-Ausgabe als ProgressSummary verwendet (Plan §3.6).
 *
 * @property tables Pro-Tabelle-Statistiken in der Reihenfolge der Verarbeitung.
 * @property totalRows Summe aller Rows über alle Tabellen.
 * @property totalChunks Summe aller Chunks (inkl. Empty-Chunks aus §6.17).
 * @property totalBytes Summe der vom Writer in den Output geschriebenen Bytes,
 *   falls der Writer das tracken kann (über einen [java.io.OutputStream]
 *   `CountingOutputStream`-Wrapper). Sonst 0.
 * @property durationMs Wall-clock Dauer in Millisekunden.
 */
data class ExportResult(
    val tables: List<TableExportSummary>,
    val totalRows: Long,
    val totalChunks: Long,
    val totalBytes: Long,
    val durationMs: Long,
    /**
     * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §4.5): stabile
     * `operationId` des Laufs. Wird von Runnern gesetzt, damit
     * stderr-Summary, Logs und Resume-Manifest auf denselben Lauf
     * referenzieren. `null` bedeutet: Pre-Phase-B-Callsite hat keine
     * ID geliefert.
     */
    val operationId: String? = null,
) {
    /** True wenn keine Tabelle geskippt wurde und alle Tabellen mindestens 0 Rows hatten. */
    val success: Boolean get() = tables.all { it.error == null }
}

/**
 * Pro-Tabelle-Statistik. [error] ist `null` bei erfolgreichem Export,
 * sonst ein Beschreibungstext (Stack-Trace landet im Log, nicht hier).
 */
data class TableExportSummary(
    val table: String,
    val rows: Long,
    val chunks: Long,
    val bytes: Long,
    val durationMs: Long,
    val error: String? = null,
)
