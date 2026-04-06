package dev.dmigrate.streaming

import dev.dmigrate.format.data.DataExportFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * Sealed class für die Output-Sinks des [StreamingExporter].
 *
 * Plan §6.9 — Auflösung der CLI-Flags `--output × --split-files × --tables`
 * in eine konkrete ExportOutput-Variante:
 *
 * ```
 * --output | --split-files | --tables   | Resultat
 * ─────────┼───────────────┼────────────┼──────────────────────────────
 * (none)   | (off)         | leer / 1   | Stdout
 * (none)   | (off)         | ≥2         | Exit 2 (CLI-Fehler)
 * file     | (off)         | leer / 1   | SingleFile
 * file     | (off)         | ≥2         | Exit 2
 * dir      | (on)          | beliebig   | FilePerTable
 * dir      | (off)         | beliebig   | Exit 2
 * ```
 *
 * Die CLI baut diese Auflösung mit [resolve] und wirft bei Konflikt eine
 * [IllegalArgumentException], die der CLI als Exit-Code 2 maps.
 */
sealed class ExportOutput {

    /** Schreibt nach `System.out`. Nur erlaubt bei einer einzelnen Tabelle. */
    object Stdout : ExportOutput()

    /**
     * Schreibt alle Tabellen in eine einzelne Datei. Nur erlaubt bei einer
     * einzelnen Tabelle.
     */
    data class SingleFile(val path: Path) : ExportOutput()

    /**
     * Schreibt jede Tabelle in eine eigene Datei
     * `<directory>/<schema>.<table>.<format>` (siehe Plan §6.9). Schema-
     * qualifizierte Tabellen werden segment-by-segment in den Dateinamen
     * übernommen, was Kollisionen zwischen z.B. `public.orders` und
     * `reporting.orders` ausschließt.
     */
    data class FilePerTable(val directory: Path) : ExportOutput()

    companion object {
        /**
         * Löst die CLI-Flag-Kombination zu einer [ExportOutput] auf.
         *
         * @throws IllegalArgumentException bei nicht-unterstützten Kombinationen
         *   (CLI mappt das auf Exit-Code 2).
         */
        fun resolve(
            outputPath: Path?,
            splitFiles: Boolean,
            tableCount: Int,
        ): ExportOutput {
            require(tableCount > 0) { "tableCount must be > 0, got $tableCount" }

            return when {
                // --output not set
                outputPath == null && !splitFiles && tableCount == 1 -> Stdout
                outputPath == null && !splitFiles && tableCount > 1 -> throw IllegalArgumentException(
                    "Cannot export $tableCount tables to stdout. " +
                        "Use --output <directory> --split-files, or specify exactly one --tables entry."
                )
                outputPath == null && splitFiles -> throw IllegalArgumentException(
                    "--split-files requires --output <directory>."
                )

                // --output is set
                outputPath != null && !splitFiles && tableCount == 1 -> SingleFile(outputPath)
                outputPath != null && !splitFiles && tableCount > 1 -> throw IllegalArgumentException(
                    "Cannot export $tableCount tables to a single file '$outputPath'. " +
                        "Use --split-files with a directory."
                )
                outputPath != null && splitFiles -> {
                    require(Files.isDirectory(outputPath) || !Files.exists(outputPath)) {
                        "--split-files: --output must be a directory, got '$outputPath'"
                    }
                    FilePerTable(outputPath)
                }

                else -> error("unreachable: outputPath=$outputPath splitFiles=$splitFiles tableCount=$tableCount")
            }
        }

        /**
         * Baut den Dateinamen für eine Tabelle in der [FilePerTable]-Variante.
         * Schema-qualifizierte Tabellen werden 1:1 übernommen, sodass z.B.
         * `public.orders` zu `public.orders.json` wird (Plan §6.9).
         */
        fun fileNameFor(table: String, format: DataExportFormat): String =
            "$table.${format.cliName}"
    }
}
