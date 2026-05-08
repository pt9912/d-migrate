package dev.dmigrate.mcp.registry

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.server.ports.JobWorkerOutcome
import java.nio.file.Files
import java.nio.file.Path

/**
 * Follow-up AP 2 Part 2 — testbare Pipeline für den Bundle-Import.
 *
 * Aus [McpDataImportJobWorker] herausgelöst, damit die Bundle-Logik
 * (Extract → Manifest-Tabellen-Konsistenz → per-Tabelle-Import-Loop →
 * Cleanup) ohne echte JDBC-Pools, ohne `ConnectionPool` und ohne
 * Streaming-Stack getestet werden kann.
 *
 * Vertrag:
 *
 * - Caller spult Bundle-ZIP und Schema-Bytes selbst (außerhalb dieser
 *   Pipeline), weil das Spool-Material aus `ArtifactContentStore`
 *   stammt und Tenant-/Schema-Auflösung benötigt.
 * - Caller liefert `bundleRoot` (Job-lokales Temp-Verzeichnis); die
 *   Pipeline löscht es im `finally`-Pfad rekursiv.
 * - Caller injiziert die `importTable`-Lambda — die Pipeline ruft sie
 *   pro Manifest-Eintrag mit dem extrahierten Datei-Pfad, dem
 *   normalisierten Tabellennamen und dem Manifest-Format. Eine
 *   fehlgeschlagene Tabelle bricht die Iteration ab; nachfolgende
 *   Tabellen werden nicht angefasst (Plan §4 "fail-fast").
 *
 * Pure-genug zum Mocken: kein `RuntimeBootstrap`, keine
 * `ArtifactContentStore`-Abfrage, keine `ConnectionReferenceStore`-
 * Auflösung. Tests liefern echte ZIP-Bytes für `BundleExtractor` und
 * eine Test-Lambda für `importTable`.
 */
internal class BundleImportPipeline {

    fun execute(
        bundleZip: Path,
        bundleRoot: Path,
        callerTables: List<String>,
        cancellationToken: CancellationToken,
        importTable: (sourcePath: Path, table: String, format: String) -> JobWorkerOutcome,
    ): JobWorkerOutcome {
        return try {
            val extraction = extractBundleArchive(
                bundleZip = bundleZip,
                bundleRoot = bundleRoot,
                callerTables = callerTables,
            ) ?: return JobWorkerOutcome.Failed(
                ERROR_BUNDLE_INVALID,
                "bundle extraction failed or table drift",
            )
            runImportLoop(extraction, cancellationToken, importTable)
        } finally {
            recursivelyDeleteBundleDir(bundleRoot)
        }
    }

    private fun runImportLoop(
        extraction: BundleExtractionOk,
        cancellationToken: CancellationToken,
        importTable: (sourcePath: Path, table: String, format: String) -> JobWorkerOutcome,
    ): JobWorkerOutcome {
        for (entry in extraction.manifest.tables) {
            cancellationToken.throwIfCancellationRequested()
            val sourcePath = extraction.extractedFiles[entry.path]
                ?: return JobWorkerOutcome.Failed(
                    ERROR_BUNDLE_INVALID,
                    "manifest entry '${entry.name}' has no extracted bytes",
                )
            val outcome = importTable(sourcePath, entry.name, extraction.manifest.format)
            if (outcome !is JobWorkerOutcome.Succeeded) {
                return outcome
            }
        }
        return JobWorkerOutcome.Succeeded()
    }

    companion object {
        const val ERROR_BUNDLE_INVALID: String = "MCP_BUNDLE_INVALID"
    }
}

