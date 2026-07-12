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

/**
 * AP11 §5.5 / AP12 §4.1: Sentinel-Tabellenname, den `DataImportHelpers.resolveImportInput`
 * fuer einen Parquet-Single-File-Import OHNE `--table` setzt. Der
 * Parquet-Phase-1-Hook (CLI-Modul) erkennt den Sentinel und ueberlaesst
 * die Tabellen-Aufloesung dem Parquet-Footer-KV `d-migrate.manifest`.
 *
 * Top-Level-`const` mit `public` Sichtbarkeit, damit Adapter (CLI/MCP)
 * den Wert lesen koennen, ohne `DataImportHelpers` selbst zu oeffnen.
 *
 * Sichtbar bleibt der Sentinel nur zwischen `resolveImportInput` und
 * dem Phase-1-Hook; danach ersetzt der Hook `ImportInput.SingleFile`
 * durch `ImportInput.ResolvedSingleFile` mit dem aufgeloesten Namen.
 * Validator-/Streaming-Pfade sehen den Sentinel nie.
 */
const val UNRESOLVED_PARQUET_TABLE_SENTINEL: String = "__d_migrate_parquet_table_unresolved__"

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

    /**
     * AP12 §4.1 / AP9: Marker-Datei eines Bundle-Verzeichnisses, referenziert
     * vom Port (`ImportInput.ResolvedBundle.MANIFEST_FILE_NAME`). Lokaler
     * Alias, weil `private const val` Kotlin-seitig nicht direkt aus einem
     * `companion object` initialisiert werden kann, ohne die Inline-Konstante
     * zu opfern.
     */
    private const val BUNDLE_MANIFEST_FILE: String =
        dev.dmigrate.streaming.ImportInput.ResolvedBundle.MANIFEST_FILE_NAME

    fun inferFormatFromExtension(path: Path): String? {
        val fileName = path.fileName?.toString() ?: return null
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXTENSION_FORMAT_MAP[ext]
    }

    /**
     * AP12 §4.1: Verzeichnis mit Bundle-`manifest.yaml` ist die einzige
     * Parquet-Bundle-Auspraegung. Ohne explizites `--format` und ohne
     * Extension-Inferenz erkennt der Resolver Bundles am Marker.
     *
     * Reines Existieren von `manifest.yaml` reicht nicht — sonst werden
     * Helm-Charts, Kustomize-Trees und alle anderen YAML-Tools als
     * Parquet-Bundle klassifiziert (Review-Finding A3). Wir lesen die
     * ersten Bytes und pruefen auf zwei Pflicht-Felder der Bundle-Form
     * (`formatVersion:` und `tables:` aus `ParquetBundleManifest`); sind
     * sie nicht beide vorhanden, faellt die Format-Inferenz zurueck auf
     * den historischen "Cannot detect format"-Pfad.
     */
    private fun inferFormatFromDirectoryManifest(path: Path): String? {
        if (!Files.isDirectory(path)) return null
        val manifestPath = path.resolve(BUNDLE_MANIFEST_FILE)
        if (!Files.isRegularFile(manifestPath)) return null
        return if (looksLikeParquetBundleManifest(manifestPath)) "parquet" else null
    }

    private const val MANIFEST_SNIFF_BYTES = 4 * 1024

    private fun looksLikeParquetBundleManifest(manifestPath: Path): Boolean {
        val head = try {
            Files.newInputStream(manifestPath).use { stream ->
                val buf = ByteArray(MANIFEST_SNIFF_BYTES)
                val read = stream.read(buf)
                if (read <= 0) "" else String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8)
            }
        } catch (_: java.io.IOException) {
            return false
        }
        // Pflichtfelder aus ParquetBundleManifest (siehe
        // adapters/driven/formats-parquet/.../ParquetManifestWriter.kt). Wir
        // pruefen das Vorkommen als YAML-Mapping-Key (`<name>:` am Zeilenanfang
        // oder direkt nach Newline) statt YAML zu parsen.
        return MANIFEST_REQUIRED_KEY_REGEX.containsMatchIn(head) &&
            MANIFEST_TABLES_KEY_REGEX.containsMatchIn(head)
    }

    private val MANIFEST_REQUIRED_KEY_REGEX = Regex("(?m)^formatVersion:")
    private val MANIFEST_TABLES_KEY_REGEX = Regex("(?m)^tables:")

    fun resolveFormat(
        request: DataImportRequest,
        isStdin: Boolean,
        sourcePath: Path?,
        stderr: (String) -> Unit,
    ): DataExportFormat? {
        // Review-Finding H1: leerstring-Override (z.B. `--format ""` aus
        // MCP/Skript-Pfaden) wie "kein Override" behandeln. Sonst landet
        // `""` in DataExportFormat.fromCli und wirft IAE statt in die
        // Extension-/Manifest-Inferenz zu fallen.
        // Review-Finding D6: ein einziger `sourcePath?.let`-Block mit
        // interner `?:`-Kette, statt zwei separate `let`-Aufrufe.
        val formatName = request.format?.takeIf { it.isNotBlank() }
            ?: sourcePath?.let { inferFormatFromExtension(it) ?: inferFormatFromDirectoryManifest(it) }

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
        // Review-Finding F1: Capability-Check auf dem Enum, nicht
        // PARQUET-hartkodierte Bedingung. Arrow IPC / ORC kommen damit
        // ohne neuen Branch hier.
        if (format.requiresSeekableInput && isStdin) {
            stderr(
                "Error: --format ${format.cliName} requires a file or directory source; " +
                    "stdin (--source -) is not supported for ${format.cliName}."
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
        // --table-order: nur Flag-Usage + Syntax pruefen (Exit 2). Semantische
        // Order-Fehler (Duplikate/unbekannt/incomplete) sind Sache der
        // Format-Resolver → BUNDLE_ORDER_* / Exit 5 (AP12 §9).
        if (request.table != null && !request.tableOrder.isNullOrEmpty()) {
            stderr("Error: --table and --table-order are mutually exclusive (--table-order is for directory sources).")
            return 2
        }
        if (!request.tableOrder.isNullOrEmpty() && request.source == "-") {
            stderr("Error: --table-order is not supported for stdin import; it applies to directory sources only.")
            return 2
        }
        if (!request.tableOrder.isNullOrEmpty()) {
            val invalid = DataExportHelpers.firstInvalidTableIdentifier(request.tableOrder)
            if (invalid != null) {
                stderr(
                    "Error: --table-order value '$invalid' is not a valid identifier. " +
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
        // AP12 §4.2: --no-checkpoint koppelt drei Verhaltensaenderungen
        // (kein Store-Read, kein Store-Write, keine Sha256-Berechnung) und
        // konfliktet mit JEDEM Flag, das Checkpoint-Semantik annimmt.
        // `isNullOrBlank()` behandelt --resume "" wie "kein Resume"; das ist
        // Absicht und symmetrisch zum bestehenden Stdin-Resume-Check.
        if (request.noCheckpoint && !request.resume.isNullOrBlank()) {
            stderr("Error: --no-checkpoint and --resume are mutually exclusive.")
            return 2
        }
        if (request.noCheckpoint && request.checkpointDir != null) {
            stderr(
                "Error: --no-checkpoint and --checkpoint-dir are mutually exclusive; " +
                    "--no-checkpoint disables the checkpoint store entirely."
            )
            return 2
        }
        // LN-013: --atomic-Preflight ausgelagert (hält validateCliFlags-Complexity unter der Grenze).
        validateAtomicFlags(request, stderr)?.let { return it }
        return null
    }

    /**
     * LN-013: `--atomic` ist destruktiv (die Fehler-Kompensation truncatet alle
     * Operations-Tabellen) → `--truncate` muss explizit gesetzt sein, damit die
     * Zerstörung am Call-Site sichtbar ist. Und atomar heißt all-or-nothing → es
     * gibt keinen Teilzustand zum Wiederaufnehmen (inkompatibel mit `--resume`).
     */
    fun validateAtomicFlags(
        request: DataImportRequest,
        stderr: (String) -> Unit,
    ): Int? {
        if (request.atomic && !request.truncate) {
            stderr("Error: --atomic requires --truncate.")
            return 2
        }
        if (request.atomic && !request.resume.isNullOrBlank()) {
            stderr("Error: --atomic and --resume are mutually exclusive (atomic runs start clean).")
            return 2
        }
        return null
    }

    fun resolveImportInput(
        request: DataImportRequest,
        isStdin: Boolean,
        sourcePath: Path?,
        stdinProvider: () -> InputStream,
        format: DataExportFormat? = null,
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
                tableOrder = request.tableOrder,
            )
        }

        val table = request.table
            ?: if (format == DataExportFormat.PARQUET) {
                // AP11 §5.5: Parquet-Single-File darf --table aus dem
                // Footer-KV ableiten. resolveImportInput setzt den
                // Top-Level-Sentinel, den der Phase-1-Hook (CLI) durch
                // den Footer-Namen ersetzt.
                dev.dmigrate.cli.commands.UNRESOLVED_PARQUET_TABLE_SENTINEL
            } else {
                throw IllegalArgumentException(
                    "--table is required when importing from a single file " +
                        "(not required for --format parquet — the table name is read from the footer KV)."
                )
            }
        return ImportInput.SingleFile(table, sourcePath)
    }

    fun resolveSchemaPreflight(
        request: DataImportRequest,
        importInput: ImportInput,
        format: DataExportFormat,
        schemaPreflight: (Path, ImportInput, DataExportFormat, List<String>?) -> SchemaPreflightResult,
        stderr: (String) -> Unit,
    ): ImportStep<SchemaPreflightResult> {
        val schemaPath = request.schema ?: return ImportStep.Ok(SchemaPreflightResult(importInput))

        return try {
            // `--table-order` (request.tableOrder) ist beim Ordering authoritative:
            // der Schema-Preflight ueberspringt dann den FK-Topo-Sort, validiert
            // aber weiter (siehe DataImportSchemaPreflight.prepare).
            ImportStep.Ok(schemaPreflight(schemaPath, importInput, format, request.tableOrder))
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
