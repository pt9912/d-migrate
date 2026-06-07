package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

internal sealed interface ImportStep<out T> {
    data class Ok<T>(val value: T) : ImportStep<T>
    data class Exit(val code: Int) : ImportStep<Nothing>
}

internal data class ImportTargetContext(
    val resolvedUrl: String,
    val connectionConfig: ConnectionConfig,
)

/**
 * Pure or near-pure helper logic for [DataImportRunner].
 *
 * The runner keeps orchestration and collaborator wiring, while these helpers
 * cover request/input resolution and import result evaluation.
 */
internal object DataImportHelpers {
    private val EXTENSION_FORMAT_MAP = mapOf(
        "json" to "json",
        "yaml" to "yaml",
        "yml" to "yaml",
        "csv" to "csv",
        "parquet" to "parquet",
    )

    /** AP12 §4.1 / AP9: Marker-Datei eines Parquet-Bundle-Verzeichnisses. */
    private const val BUNDLE_MANIFEST_FILE = "manifest.yaml"

    fun inferFormatFromExtension(path: Path): String? {
        val fileName = path.fileName?.toString() ?: return null
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXTENSION_FORMAT_MAP[ext]
    }

    /**
     * AP12 §4.1: Verzeichnis mit `manifest.yaml` ist die einzige
     * Parquet-Bundle-Auspraegung. Ohne explizites `--format` und ohne
     * Extension-Inferenz erkennt der Resolver Bundles am Marker.
     */
    private fun inferFormatFromDirectoryManifest(path: Path): String? {
        if (!Files.isDirectory(path)) return null
        return if (Files.isRegularFile(path.resolve(BUNDLE_MANIFEST_FILE))) "parquet" else null
    }

    fun resolveFormat(
        request: DataImportRequest,
        isStdin: Boolean,
        sourcePath: Path?,
        stderr: (String) -> Unit,
    ): DataExportFormat? {
        val formatName = request.format
            ?: sourcePath?.let(::inferFormatFromExtension)
            ?: sourcePath?.let(::inferFormatFromDirectoryManifest)

        if (formatName == null) {
            if (isStdin) {
                stderr("Error: --format is required when reading from stdin (--source -).")
            } else {
                stderr(
                    "Error: Cannot detect format from '${request.source}'. " +
                        "Use --format to specify json, yaml, csv, or parquet."
                )
            }
            return null
        }

        return try {
            DataExportFormat.fromCli(formatName)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            null
        }
    }

    /**
     * AP12 §4.1: Pfad-only-Vertrag fuer seekable Formate (heute nur Parquet).
     * Parquet braucht zufaelligen Footer-Zugriff und unterstuetzt damit weder
     * Stdin-Quellen noch das implizite `--source -`. Wird vom CLI-Preflight
     * direkt nach [resolveFormat] aufgerufen.
     */
    fun validateFormatPathRequirements(
        format: DataExportFormat,
        isStdin: Boolean,
        stderr: (String) -> Unit,
    ): Int? {
        if (format == DataExportFormat.PARQUET && isStdin) {
            stderr(
                "Error: --format parquet requires a file or directory source; " +
                    "stdin (--source -) is not supported for Parquet."
            )
            return 2
        }
        return null
    }

    fun validateCliFlags(
        request: DataImportRequest,
        stderr: (String) -> Unit,
    ): Int? {
        if (request.table != null && !request.tables.isNullOrEmpty()) {
            stderr("Error: --table and --tables are mutually exclusive.")
            return 2
        }
        if (request.table != null) {
            val invalid = DataExportHelpers.firstInvalidQualifiedIdentifier(request.table)
            if (invalid != null) {
                stderr(
                    "Error: --table value '$invalid' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
        }
        if (!request.tables.isNullOrEmpty()) {
            val invalid = DataExportHelpers.firstInvalidTableIdentifier(request.tables)
            if (invalid != null) {
                stderr(
                    "Error: --tables value '$invalid' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
        }
        if (request.truncate && request.onConflict == "abort") {
            stderr("Error: --truncate with explicit --on-conflict abort is contradictory.")
            return 2
        }
        if (!request.resume.isNullOrBlank() && request.source == "-") {
            stderr(
                "Error: --resume is not supported for stdin import; " +
                    "provide a file or directory source or drop --resume."
            )
            return 2
        }
        return null
    }

    fun resolveImportInput(
        request: DataImportRequest,
        isStdin: Boolean,
        sourcePath: Path?,
        stdinProvider: () -> InputStream,
    ): ImportInput {
        if (isStdin) {
            val table = request.table
                ?: throw IllegalArgumentException("--table is required when reading from stdin (--source -).")
            return ImportInput.Stdin(table, stdinProvider())
        }

        requireNotNull(sourcePath)

        if (Files.isDirectory(sourcePath)) {
            require(request.table == null) {
                "--table is only supported for stdin or single-file imports. Use --tables for directory sources."
            }
            return ImportInput.Directory(
                path = sourcePath,
                tableFilter = request.tables,
            )
        }

        val table = request.table
            ?: throw IllegalArgumentException("--table is required when importing from a single file.")
        return ImportInput.SingleFile(table, sourcePath)
    }

    fun resolveSchemaPreflight(
        request: DataImportRequest,
        importInput: ImportInput,
        format: DataExportFormat,
        schemaPreflight: (Path, ImportInput, DataExportFormat) -> SchemaPreflightResult,
        stderr: (String) -> Unit,
    ): ImportStep<SchemaPreflightResult> {
        val schemaPath = request.schema ?: return ImportStep.Ok(SchemaPreflightResult(importInput))

        return try {
            ImportStep.Ok(schemaPreflight(schemaPath, importInput, format))
        } catch (e: ImportPreflightException) {
            stderr("Error: ${e.message}")
            ImportStep.Exit(3)
        }
    }

    fun resolveCharset(
        encoding: String?,
        stderr: (String) -> Unit,
    ): ImportStep<Charset?> {
        if (encoding == null) return ImportStep.Ok(null)

        return try {
            ImportStep.Ok(Charset.forName(encoding))
        } catch (e: Exception) {
            stderr("Error: Unknown encoding '$encoding': ${e.message}")
            ImportStep.Exit(2)
        }
    }

    fun resolveTargetContext(
        request: DataImportRequest,
        targetResolver: (target: String?, configPath: Path?) -> String,
        urlParser: (String) -> ConnectionConfig,
        stderr: (String) -> Unit,
    ): ImportStep<ImportTargetContext> {
        val resolvedUrl = try {
            targetResolver(request.target, request.cliConfigPath)
        } catch (e: CliUsageException) {
            stderr("Error: ${e.message}")
            return ImportStep.Exit(2)
        } catch (e: Exception) {
            stderr("Error: ${e.message}")
            return ImportStep.Exit(7)
        }

        val connectionConfig = try {
            urlParser(resolvedUrl)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return ImportStep.Exit(7)
        }

        return ImportStep.Ok(ImportTargetContext(resolvedUrl, connectionConfig))
    }

    fun validateDialectCapabilities(
        request: DataImportRequest,
        dialect: DatabaseDialect,
        stderr: (String) -> Unit,
    ): Int? {
        val caps = DialectCapabilities.forDialect(dialect)

        if (request.disableFkChecks && !caps.supportsDisableFkChecks) {
            val dialectName = dialect.name.lowercase()
                .replaceFirstChar { it.uppercase() }
            stderr(
                "Error: --disable-fk-checks is not supported for $dialectName. " +
                    "Use DEFERRABLE constraints or --schema-based ordering instead."
            )
            return 2
        }

        if (request.triggerMode == "disable" && !caps.supportsTriggerDisable) {
            stderr("Error: --trigger-mode disable is not supported for dialect $dialect.")
            return 2
        }

        if (request.triggerMode == "strict" && !caps.supportsTriggerStrict) {
            stderr("Error: --trigger-mode strict is not supported for dialect $dialect.")
            return 2
        }

        return null
    }
}
