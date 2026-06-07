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
    private val phase1Hook: ImportInputPhase1Hook = ImportInputPhase1Hook.IDENTITY,
) {

    fun resolve(request: DataImportRequest): ImportPreflightResolution {
        DataImportHelpers.validateCliFlags(request, stderr)?.let {
            return ImportPreflightResolution.Exit(it)
        }

        val isStdin = request.source == "-"
        val sourcePath = if (isStdin) null else Path.of(request.source)

        val format = DataImportHelpers.resolveFormat(request, isStdin, sourcePath, stderr)
            ?: return ImportPreflightResolution.Exit(2)

        DataImportHelpers.validateFormatPathRequirements(format, isStdin, stderr)
            ?.let { return ImportPreflightResolution.Exit(it) }

        if (sourcePath != null && !Files.exists(sourcePath)) {
            stderr("Error: Source path does not exist: $sourcePath")
            return ImportPreflightResolution.Exit(2)
        }

        val rawInput = try {
            DataImportHelpers.resolveImportInput(request, isStdin, sourcePath, stdinProvider)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return ImportPreflightResolution.Exit(2)
        }

        // Parquet Cut A S6: parquet-freier Phase-1-Hook. Die Identity-Default-
        // Variante laesst nicht-Parquet-Pfade unveraendert; CLI verdrahtet
        // den Parquet-Hook, der Directory→ResolvedBundle und
        // SingleFile→ResolvedSingleFile transformiert.
        // computeContentSha256 spiegelt `!request.noCheckpoint`: ohne
        // Checkpoint-Persistenz braucht der Phase-1-Pfad den Inhalts-
        // Hash nicht zu berechnen (AP12 §4.2). Die echte Resume-Verifikation
        // landet erst mit S8 (SingleFileCheckpointSpecifics).
        val importInput = try {
            phase1Hook.maybeFinalize(
                rawInput = rawInput,
                format = format,
                computeContentSha256 = !request.noCheckpoint,
            )
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return ImportPreflightResolution.Exit(3)
        } catch (e: RuntimeException) {
            stderr("Error: ${e.message}")
            return ImportPreflightResolution.Exit(3)
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
