package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.OperationCancelledException
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
            DataImportHelpers.resolveImportInput(
                request = request,
                isStdin = isStdin,
                sourcePath = sourcePath,
                stdinProvider = stdinProvider,
                format = format,
            )
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return ImportPreflightResolution.Exit(2)
        }

        // Parquet Cut A S6: parquet-freier Phase-1-Hook. Die Identity-Default-
        // Variante laesst nicht-Parquet-Pfade unveraendert; CLI verdrahtet
        // den Parquet-Hook, der Directory→ResolvedBundle und
        // SingleFile→ResolvedSingleFile transformiert.
        //
        // Review-Finding E1: SHA-256 nur berechnen, wenn der Lauf einen
        // konkreten Resume-Anker hat (`--resume` gesetzt) UND der Checkpoint-
        // Store nicht abgeschaltet ist (`--no-checkpoint` aus). Fresh imports
        // sparen damit den vollen Bytestream-Read; die spaeter (S8) hinzu-
        // kommende Resume-Hash-Verifikation bekommt den Wert nur, wenn er
        // ueberhaupt verglichen wird.
        //
        // Exit-Code-Mapping (symmetrisch zu resolveImportInput):
        //  - IllegalArgumentException → Exit 2 (CLI-Validierung des Hook-Inputs).
        //  - OperationCancelledException wird REthrowed (Cancel-Pipeline → 130).
        //  - Andere RuntimeException → Exit 3 (Preflight-Failure, z.B.
        //    PARQUET_BUNDLE_MANIFEST_PARSE_ERROR).
        val computeContentSha256 = !request.noCheckpoint && !request.resume.isNullOrBlank()
        val importInput = try {
            phase1Hook.maybeFinalize(
                rawInput = rawInput,
                format = format,
                computeContentSha256 = computeContentSha256,
            )
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return ImportPreflightResolution.Exit(2)
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
