package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import java.nio.file.Files
import java.nio.file.Path

/**
 * 0.9.0 Phase D.4 (`docs/ImpPlan-0.9.0-D.md` §4.5 / §5.4):
 * scannt ein Directory und erzeugt eine stabile, reihenfolge-
 * deterministische `table -> inputFile`-Zuordnung fuer Directory-
 * Importe.
 *
 * Das Ergebnis deckt exakt die gleiche Auswahl-Semantik ab, die der
 * [dev.dmigrate.streaming.StreamingImporter] intern verwendet — mit
 * identischen `require(...)`-Fehlermeldungen. Damit kann der
 * [DataImportRunner] den Preflight gegen ein vorhandenes Manifest
 * schon vor dem Oeffnen der ersten Datei auswerten (Fingerprint +
 * `inputFile`-Vergleich).
 *
 * Die Auswahl in drei Schritten:
 *
 * 1. Alle regulaeren Dateien im Root werden gegen
 *    `format.fileExtensions` geprueft; matched → Kandidat mit
 *    `tableName = fileName - ".<ext>"`.
 * 2. Mehrdeutigkeiten (mehrere Dateien mit demselben tableName in
 *    unterschiedlichen unterstuetzten Extensions) werden als Fehler
 *    gemeldet — der Importer bricht den Lauf spaeter identisch ab,
 *    aber Phase D.4 faengt es schon im Preflight.
 * 3. `tableFilter` / `tableOrder` werden angewendet wie in
 *    [ImportInput.Directory].
 *
 * Das Ergebnis enthaelt die **relativen** Dateinamen (nicht den
 * absoluten Pfad), damit sie im Manifest unabhaengig davon persistiert
 * werden koennen, wo das Directory beim Resume liegt.
 */
internal object DirectoryImportScanner {

    data class ScannedTable(
        /** Tabellenname (Dateiname ohne Format-Extension). */
        val table: String,
        /** Relativer Dateiname innerhalb des Directory-Roots. */
        val fileName: String,
    )

    /**
     * Scannt [directory] gegen [format] und liefert die geordnete
     * Tabellenliste samt Manifest-tauglicher relativer Dateinamen.
     */
    fun scan(
        directory: Path,
        format: DataExportFormat,
        tableFilter: List<String>? = null,
        tableOrder: List<String>? = null,
    ): List<ScannedTable> {
        require(Files.isDirectory(directory)) {
            "ImportInput.Directory path '$directory' is not a directory"
        }
        val suffixes = format.fileExtensions.map { ".$it" }
        val candidates = linkedMapOf<String, String>()
        val candidateFiles = linkedMapOf<String, MutableList<String>>()
        Files.list(directory).use { entries ->
            entries
                .filter(Files::isRegularFile)
                .forEach { path ->
                    val fileName = path.fileName.toString()
                    val matchedSuffix = suffixes.firstOrNull { fileName.endsWith(it) }
                    if (matchedSuffix != null) {
                        val tableName = fileName.removeSuffix(matchedSuffix)
                        candidateFiles
                            .getOrPut(tableName) { mutableListOf() }
                            .add(fileName)
                        candidates.putIfAbsent(tableName, fileName)
                    }
                }
        }
        val selectedTables = tableFilter ?: candidates.keys
        val duplicateDetails = duplicateCandidateDetails(candidateFiles, selectedTables)
        require(duplicateDetails.isEmpty()) {
            "ImportInput.Directory contains multiple files for the same table: " +
                duplicateDetails.joinToString("; ")
        }

        if (tableFilter != null) {
            val missing = tableFilter.filterNot(candidates::containsKey)
            require(missing.isEmpty()) {
                "ImportInput.Directory.tableFilter references tables without " +
                    "matching files: ${missing.joinToString()}"
            }
            candidates.keys.retainAll(tableFilter.toSet())
        }

        val orderedTables = if (tableOrder != null) {
            val duplicates = tableOrder.groupBy { it }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) {
                "ImportInput.Directory.tableOrder contains duplicate tables: " +
                    duplicates.joinToString()
            }
            val missing = tableOrder.filterNot(candidates::containsKey)
            require(missing.isEmpty()) {
                "ImportInput.Directory.tableOrder references tables without " +
                    "matching files: ${missing.joinToString()}"
            }
            val extras = candidates.keys - tableOrder.toSet()
            require(extras.isEmpty()) {
                "ImportInput.Directory.tableOrder must cover all candidate tables, " +
                    "missing order for: ${extras.joinToString()}"
            }
            tableOrder
        } else {
            candidates.keys.sorted()
        }

        return orderedTables.map { ScannedTable(table = it, fileName = candidates.getValue(it)) }
    }

    private fun duplicateCandidateDetails(
        candidateFiles: Map<String, List<String>>,
        selectedTables: Iterable<String>,
    ): List<String> =
        selectedTables.asSequence()
            .distinct()
            .mapNotNull { table ->
                candidateFiles[table]
                    ?.takeIf { it.size > 1 }
                    ?.let { "$table <- ${it.sorted().joinToString()}" }
            }
            .sorted()
            .toList()
}
