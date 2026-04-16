package dev.dmigrate.streaming.checkpoint

import java.time.Instant

/**
 * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §4.2): versioniertes, persistier-
 * bares Manifest-Grundmodell fuer Export- und Import-Laeufe.
 *
 * Das Manifest ist bewusst **klein und erweiterbar**: Grundfelder hier,
 * operationsspezifische Erweiterungen liegen unter [operationSpecific] als
 * typisiertes [CheckpointOperationSpecifics] und werden in den Phasen C/D
 * mit Inhalt gefuellt.
 *
 * Phase B fixiert:
 * - Versionsfeld [schemaVersion] mit [CURRENT_SCHEMA_VERSION] als aktuellem
 *   Vertragsanker; inkompatible Versionen werfen
 *   [UnsupportedCheckpointVersionException] beim Laden.
 * - stabile [operationId], unter der ein Lauf in Progress-, Result- und
 *   stderr-Pfaden referenzierbar ist (§4.5).
 * - [operationType] trennt Export- und Import-Manifest-Auspraegungen.
 * - Zeitstempel fuer Erstellung ([createdAt]) und letzte Fortschreibung
 *   ([updatedAt]) — wichtig fuer Support, Debugging und GC-Strategien in
 *   Folgereleases.
 * - [chunkSize] und [format] als Laufmetadaten, gegen die Phase C/D die
 *   Wiederaufnahme validieren kann.
 * - [tableSlices] enthaelt den pro Tabelle/Input-Slice serialisierbaren
 *   Resume-Status (Fortschrittszaehler, Marker).
 * - [optionsFingerprint] ist ein kompakter Vergleichswert fuer die
 *   Phase-A-Oberflaeche (Tabellenmenge, Filter, `--since`, Encoding,
 *   on-conflict, ...) und wird in Phase C/D konkretisiert, um semantische
 *   Mismatches (Exit 3 aus Phase A §4.5) zu erkennen.
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
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** 0.9.0 Phase B §4.2: trennt Export- und Import-Manifest-Auspraegungen. */
enum class CheckpointOperationType { EXPORT, IMPORT }

/**
 * 0.9.0 Phase B §4.2: serialisierbarer Resume-Status pro Tabelle oder
 * Input-Slice. `status` beschreibt den groben Zustand, `rowsProcessed`
 * und `chunksProcessed` erlauben einer Resume-Runtime, den Punkt der
 * Wiederaufnahme zu bestimmen. `lastMarker` ist eine freie, typisierte
 * Referenz fuer Export (`--since`-Marker, letzte PK-Seite) bzw. Import
 * (zuletzt comittete Chunk-Grenze). Das konkrete Marker-Schema legen
 * Phasen C/D fest.
 */
data class CheckpointTableSlice(
    val table: String,
    val status: CheckpointSliceStatus,
    val rowsProcessed: Long = 0L,
    val chunksProcessed: Long = 0L,
    val lastMarker: String? = null,
) {
    init {
        require(table.isNotBlank()) { "table must not be blank" }
        require(rowsProcessed >= 0L) { "rowsProcessed must be >= 0" }
        require(chunksProcessed >= 0L) { "chunksProcessed must be >= 0" }
    }
}

/** Grober Resume-Status einer Tabelle/Input-Slice im Manifest. */
enum class CheckpointSliceStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }

/**
 * 0.9.0 Phase B §4.2: Erweiterungspunkt fuer operationsspezifische
 * Felder. Phase B definiert das Grundmodell; Export- und Import-Resume
 * in Phasen C/D befuellen konkrete Unterklassen, ohne das Manifest-
 * Kernmodell bei jedem Milestone neu aufzureissen.
 */
sealed interface CheckpointOperationSpecifics
