package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.SchemaCodec
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import java.nio.file.Files
import java.nio.file.Path

/**
 * Orchestrates the schema preflight for `data import`:
 * reads and validates the schema file, then delegates to
 * [ImportDirectoryResolver] for table ordering and
 * [ImportTableValidator] for per-table compatibility checks.
 *
 * The concrete [SchemaCodec] is injected by the composition root (CLI / MCP),
 * keeping this class free of the `adapters/driven/formats` dependency.
 */
class DataImportSchemaPreflight(private val schemaCodec: SchemaCodec) {

    /**
     * @param explicitTableOrder `--table-order` (falls gesetzt). Ist die
     *   Reihenfolge explizit vorgegeben, ist sie **authoritative**: der
     *   FK-Topo-Sort wird uebersprungen, das Schema aber weiter validiert
     *   (Praezedenz `--table-order` > Schema-Topo-Sort). Fuer `Directory`
     *   traegt die explizite Reihenfolge bereits `input.tableOrder`; fuer
     *   `ResolvedBundle` hat der Parquet-Hook sie schon in `input.tables`
     *   eingebacken — darum hier das separate Signal.
     */
    fun prepare(
        schemaPath: Path,
        input: ImportInput,
        format: DataExportFormat,
        explicitTableOrder: List<String>? = null,
    ): SchemaPreflightResult {
        val schema = readSchema(schemaPath)
        validateSchema(schemaPath, schema)

        // Review-Finding A1: Bundle-Tabellen ebenfalls per Schema-FK-Topology
        // sortieren. Bisher fielen ResolvedBundle/ResolvedSingleFile in
        // `else -> input` und behielten die Manifest-Reihenfolge, was zu
        // FK-Verletzungen fuehrte, wenn der Bundle-Producer alphabetisch
        // statt topologisch geschrieben hatte.
        val preparedInput = when (input) {
            is ImportInput.Directory ->
                // --table-order authoritative → Topo-Sort ueberspringen
                // (input.tableOrder traegt die explizite Reihenfolge).
                if (input.tableOrder != null) input
                else input.copy(
                    tableOrder = ImportDirectoryResolver.resolveTableOrder(schemaPath, schema, input, format)
                )
            is ImportInput.ResolvedBundle ->
                // Hook hat --table-order bereits in input.tables eingebacken
                // (+ via applyFilterAndOrder validiert) → kein Re-Sort.
                if (explicitTableOrder != null) input
                else {
                    val orderedTableNames = ImportDirectoryResolver.resolveTopologicalOrder(
                        schemaPath = schemaPath,
                        schema = schema,
                        candidateTables = input.tables.map { it.table },
                    )
                    val bindingsByTable = input.tables.associateBy { it.table }
                    input.copy(
                        tables = orderedTableNames.map { bindingsByTable.getValue(it) },
                    )
                }
            else -> input
        }

        return SchemaPreflightResult(
            input = preparedInput,
            schema = schema,
        )
    }

    fun validateTargetTable(
        schema: SchemaDefinition,
        table: String,
        targetColumns: List<TargetColumn>,
    ) = ImportTableValidator.validateTargetTable(schema, table, targetColumns)

    private fun readSchema(schemaPath: Path): SchemaDefinition {
        if (!Files.exists(schemaPath)) {
            throw ImportPreflightException("Schema path does not exist: $schemaPath")
        }
        if (!Files.isRegularFile(schemaPath)) {
            throw ImportPreflightException("Schema path is not a file: $schemaPath")
        }

        return try {
            Files.newInputStream(schemaPath).use { input ->
                schemaCodec.read(input)
            }
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to parse schema file '$schemaPath': ${t.message ?: t::class.simpleName}",
                t,
            )
        }
    }

    private fun validateSchema(schemaPath: Path, schema: SchemaDefinition) {
        val result = try {
            SchemaValidator().validate(schema)
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to validate schema file '$schemaPath': ${t.message ?: t::class.simpleName}",
                t,
            )
        }

        if (!result.isValid) {
            val preview = result.errors.take(3).joinToString("; ") {
                "${it.code} ${it.objectPath}: ${it.message}"
            }
            val suffix = if (result.errors.size > 3) "; ..." else ""
            throw ImportPreflightException(
                "Schema validation failed for '$schemaPath': $preview$suffix"
            )
        }
    }
}
