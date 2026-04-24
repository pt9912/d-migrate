package dev.dmigrate.cli.commands

import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves CLI-facing import inputs into a validated preflight context before
 * any database connection is created.
 */
internal class ImportPreflightResolver(
    private val targetResolver: (target: String?, configPath: Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val schemaPreflight: (schemaPath: Path, input: ImportInput, format: DataExportFormat) -> SchemaPreflightResult,
    private val stdinProvider: () -> InputStream,
    private val stderr: (String) -> Unit,
) {

    fun resolve(request: DataImportRequest): ImportPreflightResolution {
        DataImportHelpers.validateCliFlags(request, stderr)?.let {
            return ImportPreflightResolution.Exit(it)
        }

        val isStdin = request.source == "-"
        val sourcePath = if (isStdin) null else Path.of(request.source)

        val format = DataImportHelpers.resolveFormat(request, isStdin, sourcePath, stderr)
            ?: return ImportPreflightResolution.Exit(2)

        if (sourcePath != null && !Files.exists(sourcePath)) {
            stderr("Error: Source path does not exist: $sourcePath")
            return ImportPreflightResolution.Exit(2)
        }

        val importInput = try {
            DataImportHelpers.resolveImportInput(request, isStdin, sourcePath, stdinProvider)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return ImportPreflightResolution.Exit(2)
        }

        val preparedImport = when (
            val result = DataImportHelpers.resolveSchemaPreflight(
                request = request,
                importInput = importInput,
                format = format,
                schemaPreflight = schemaPreflight,
                stderr = stderr,
            )
        ) {
            is ImportStep.Ok -> result.value
            is ImportStep.Exit -> return ImportPreflightResolution.Exit(result.code)
        }

        val charset = when (val result = DataImportHelpers.resolveCharset(request.encoding, stderr)) {
            is ImportStep.Ok -> result.value
            is ImportStep.Exit -> return ImportPreflightResolution.Exit(result.code)
        }

        val targetContext = when (
            val result = DataImportHelpers.resolveTargetContext(
                request = request,
                targetResolver = targetResolver,
                urlParser = urlParser,
                stderr = stderr,
            )
        ) {
            is ImportStep.Ok -> result.value
            is ImportStep.Exit -> return ImportPreflightResolution.Exit(result.code)
        }

        DataImportHelpers.validateDialectCapabilities(request, targetContext.connectionConfig.dialect, stderr)
            ?.let { return ImportPreflightResolution.Exit(it) }

        return ImportPreflightResolution.Ok(
            ImportPreflightContext(
                format = format,
                preparedImport = preparedImport,
                charset = charset,
                resolvedUrl = targetContext.resolvedUrl,
                connectionConfig = targetContext.connectionConfig,
            )
        )
    }
}
