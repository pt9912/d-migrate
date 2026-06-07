package dev.dmigrate.mcp.registry

import dev.dmigrate.cli.commands.ImportDirectoryResolver
import dev.dmigrate.cli.commands.ImportPreflightException
import dev.dmigrate.cli.commands.ImportTableValidator
import dev.dmigrate.cli.commands.SchemaPreflightResult
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import java.nio.file.Files
import java.nio.file.Path

/**
 * MCP-side schemaRef import adapter.
 *
 * The schema bytes are resolved from the tenant-scoped SchemaStore before this
 * adapter runs; this class performs the same preflight semantics as the CLI
 * path without accepting local schema paths from the MCP payload.
 */
internal object SchemaRefImportPreflightAdapter {

    fun prepare(
        schemaPath: Path,
        schemaFormat: String,
        input: ImportInput,
        format: DataExportFormat,
    ): SchemaPreflightResult {
        val schema = readSchema(schemaPath, schemaFormat)
        validateSchema(schemaPath, schema)
        // Review-Finding A1: ResolvedBundle bekommt jetzt ebenfalls eine
        // Schema-FK-Topo-Sortierung, symmetrisch zum CLI-Pfad
        // (DataImportSchemaPreflight).
        val preparedInput = when (input) {
            is ImportInput.Directory -> input.copy(
                tableOrder = ImportDirectoryResolver.resolveTableOrder(schemaPath, schema, input, format),
            )
            is ImportInput.ResolvedBundle -> {
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
        return SchemaPreflightResult(input = preparedInput, schema = schema)
    }

    fun validateTargetTable(
        schema: SchemaDefinition,
        table: String,
        targetColumns: List<TargetColumn>,
    ) = ImportTableValidator.validateTargetTable(schema, table, targetColumns)

    private fun readSchema(schemaPath: Path, schemaFormat: String): SchemaDefinition {
        if (!Files.exists(schemaPath)) {
            throw ImportPreflightException("Schema path does not exist: $schemaPath")
        }
        if (!Files.isRegularFile(schemaPath)) {
            throw ImportPreflightException("Schema path is not a file: $schemaPath")
        }
        return try {
            Files.newInputStream(schemaPath).use { input ->
                SchemaFileResolver.codecForFormat(schemaFormat).read(input)
            }
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to parse schemaRef payload '$schemaPath': ${t.message ?: t::class.simpleName}",
                t,
            )
        }
    }

    private fun validateSchema(schemaPath: Path, schema: SchemaDefinition) {
        val result = try {
            SchemaValidator().validate(schema)
        } catch (t: Throwable) {
            throw ImportPreflightException(
                "Failed to validate schemaRef payload '$schemaPath': ${t.message ?: t::class.simpleName}",
                t,
            )
        }
        if (!result.isValid) {
            val preview = result.errors.take(3).joinToString("; ") {
                "${it.code} ${it.objectPath}: ${it.message}"
            }
            val suffix = if (result.errors.size > 3) "; ..." else ""
            throw ImportPreflightException("SchemaRef validation failed for '$schemaPath': $preview$suffix")
        }
    }
}
