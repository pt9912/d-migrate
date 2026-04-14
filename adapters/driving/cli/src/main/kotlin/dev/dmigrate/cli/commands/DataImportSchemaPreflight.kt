package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.yaml.YamlSchemaCodec
import dev.dmigrate.streaming.ImportInput
import java.nio.file.Files
import java.nio.file.Path

/**
 * Orchestrates the schema preflight for `data import`:
 * reads and validates the schema file, then delegates to
 * [ImportDirectoryResolver] for table ordering and
 * [ImportTableValidator] for per-table compatibility checks.
 */
object DataImportSchemaPreflight {

    fun prepare(
        schemaPath: Path,
        input: ImportInput,
        format: DataExportFormat,
    ): SchemaPreflightResult {
        val schema = readSchema(schemaPath)
        validateSchema(schemaPath, schema)

        val preparedInput = when (input) {
            is ImportInput.Directory -> input.copy(
                tableOrder = ImportDirectoryResolver.resolveTableOrder(schemaPath, schema, input, format)
            )
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
                YamlSchemaCodec().read(input)
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
