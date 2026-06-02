package dev.dmigrate.streaming.checkpoint

import java.time.Instant

/**
 * LF-013 / LN-012 / LN-013: versioniertes, persistierbares
 * Manifest-Grundmodell fuer Export- und Import-Laeufe.
 *
 * Das Manifest ist bewusst **klein und erweiterbar**: Grundfelder hier,
 * operationsspezifische Erweiterungen liegen unter [operationSpecific] als
 * typisiertes [CheckpointOperationSpecifics] und werden pro Operation
 * mit Inhalt gefuellt.
 *
 * Der Manifest-Vertrag fixiert:
 * - Versionsfeld [schemaVersion] mit [CURRENT_SCHEMA_VERSION] als aktuellem
 *   Vertragsanker; inkompatible Versionen werfen
 *   [UnsupportedCheckpointVersionException] beim Laden.
 * - stabile [operationId], unter der ein Lauf in Progress-, Result- und
 *   stderr-Pfaden referenzierbar ist (§4.5).
 * - [operationType] trennt Export- und Import-Manifest-Auspraegungen.
 * - Zeitstempel fuer Erstellung ([createdAt]) und letzte Fortschreibung
 *   ([updatedAt]) — wichtig fuer Support, Debugging und GC-Strategien in
 *   Folgereleases.
 * - [chunkSize] und [format] als Laufmetadaten, gegen die die
 *   Wiederaufnahme validieren kann.
 * - [tableSlices] enthaelt den pro Tabelle/Input-Slice serialisierbaren
 *   Resume-Status (Fortschrittszaehler, Marker).
 * - [optionsFingerprint] ist ein kompakter Vergleichswert fuer die
 *   Request-Oberflaeche (Tabellenmenge, Filter, `--since`, Encoding,
 *   on-conflict, ...) und wird operationsspezifisch konkretisiert, um
 *   semantische Mismatches (Exit 3) zu erkennen.
 */
data class CheckpointManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val operationId: String,
    val operationType: CheckpointOperationType,
    val createdAt: Instant,
    val updatedAt: Instant,
    val format: String?,
    val chunkSize: Int,
    val tableSlices: List<CheckpointTableSlice> = emptyList(),
    val optionsFingerprint: String? = null,
    val operationSpecific: CheckpointOperationSpecifics? = null,
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion must be >= 1, got $schemaVersion" }
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must be >= createdAt" }
    }

    companion object {
        /**
         * Aktueller Schema-Vertragsanker. Muss bei jeder nicht abwaerts-
         * kompatiblen Manifest-Aenderung inkrementiert werden; Loader
         * weigern sich dann explizit, aeltere/neuere Versionen zu oeffnen.
         */
        /**
         * v1: 0.9.0 initial manifest format (raw --filter fingerprint)
         * v2: 0.9.3 DSL-based --filter fingerprint (canonical form)
         */
        const val CURRENT_SCHEMA_VERSION: Int = 2
        const val MIN_SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}

/** LF-013: trennt Export- und Import-Manifest-Auspraegungen. */
enum class CheckpointOperationType { EXPORT, IMPORT }

/**
 * LF-013 / LN-012: serialisierbarer Resume-Status pro Tabelle oder
 * Input-Slice. `status` beschreibt den groben Zustand, `rowsProcessed`
 * und `chunksProcessed` erlauben einer Resume-Runtime, den Punkt der
 * Wiederaufnahme zu bestimmen. `lastMarker` ist eine freie, typisierte
 * Referenz fuer Export (`--since`-Marker, letzte PK-Seite) bzw. Import
 * (zuletzt comittete Chunk-Grenze). Das konkrete Marker-Schema legen
 * Export- und Import-Operationen fest.
 *
 * LF-013 / LN-006: [resumePosition] traegt die strukturierte
 * Composite-Marker-Position
 * fuer Mid-Table-Resume. `lastMarker` bleibt als bewusst ungenutztes
 * Legacy-Feld erhalten, damit alte Manifeste weiterhin geladen
 * werden koennen — neue Laeufe schreiben ausschliesslich
 * [resumePosition].
 */
data class CheckpointTableSlice(
    val table: String,
    val status: CheckpointSliceStatus,
    val rowsProcessed: Long = 0L,
    val chunksProcessed: Long = 0L,
    val lastMarker: String? = null,
    val resumePosition: CheckpointResumePosition? = null,
    /**
     * LF-010 / LF-013 / LN-009: stabile `table -> inputFile`-Bindung
     * fuer Directory-Importe.
     * Wert ist der **relative** Dateiname innerhalb des Directory-
     * Roots (z.B. `users.json`), nicht der absolute Pfad — so bleibt
     * das Manifest unabhaengig davon, wo das Directory beim Erst-
     * bzw. Resume-Lauf liegt. `null` fuer Stdin-/SingleFile-Imports
     * und fuer alte Manifeste ohne Directory-Bindung.
     */
    val inputFile: String? = null,
) {
    init {
        require(table.isNotBlank()) { "table must not be blank" }
        require(rowsProcessed >= 0L) { "rowsProcessed must be >= 0" }
        require(chunksProcessed >= 0L) { "chunksProcessed must be >= 0" }
    }
}

/**
 * LF-013 / LN-006 / LN-012: serialisierbare Composite-Marker-Position
 * fuer Mid-Table-Resume.
 *
 * Alle Werte werden als Strings persistiert — der Runner kodiert beim
 * Schreiben ueber `Any?.toString()` und dekodiert beim Laden ueber den
 * vorhandenen `TemporalFormatPolicy.parseSinceLiteral`-Pfad, der die
 * gleiche Typ-Inferenz wie `--since`-CLI-Literale liefert. Damit
 * braucht das Manifest keinen zusaetzlichen Typ-Tag und bleibt
 * format-stabil gegenueber aelteren Manifesten.
 *
 * [markerColumn] und [tieBreakerColumns] sind **nicht** parallel zu
 * [tieBreakerValues] in der Marker-Spalte selbst — [markerValue]
 * gehoert allein zu [markerColumn]. [tieBreakerColumns] und
 * [tieBreakerValues] sind zueinander parallel und bilden den
 * PK-Tie-Breaker ab.
 */
data class CheckpointResumePosition(
    val markerColumn: String,
    val markerValue: String?,
    val tieBreakerColumns: List<String>,
    val tieBreakerValues: List<String?>,
) {
    init {
        require(markerColumn.isNotBlank()) {
            "CheckpointResumePosition.markerColumn must not be blank"
        }
        require(tieBreakerColumns.size == tieBreakerValues.size) {
            "CheckpointResumePosition tieBreakerColumns (${tieBreakerColumns.size}) and " +
                "tieBreakerValues (${tieBreakerValues.size}) must have the same size"
        }
        require(tieBreakerColumns.all { it.isNotBlank() }) {
            "CheckpointResumePosition.tieBreakerColumns must not contain blank entries"
        }
    }
}

/** Grober Resume-Status einer Tabelle/Input-Slice im Manifest. */
enum class CheckpointSliceStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }

/**
 * LF-013: Erweiterungspunkt fuer operationsspezifische Felder.
 * Export- und Import-Resume befuellen konkrete Unterklassen, ohne das Manifest-
 * Kernmodell bei jedem Milestone neu aufzureissen.
 */
sealed interface CheckpointOperationSpecifics
