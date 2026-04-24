package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.SchemaReadSeverity
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Thrown by dbLoader when the error is a config/URL/alias resolution
 * failure (exit 7) rather than a connection/metadata failure (exit 4).
 */
class CompareConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// ── Request ──────────────────────────────────────────────────────

data class SchemaCompareRequest(
    val source: String,
    val target: String,
    val output: Path?,
    val outputFormat: String,
    val quiet: Boolean,
    val verbose: Boolean = false,
    val cliConfigPath: Path? = null,
)

// ── Runner ────────────────────────────────────────────────────────

class SchemaCompareRunner(
    private val operandParser: (String) -> CompareOperand = CompareOperandParser::parse,
    private val fileLoader: (CompareOperand.File) -> ResolvedSchemaOperand,
    private val dbLoader: ((CompareOperand.Database, Path?) -> ResolvedSchemaOperand)? = null,
    private val normalizer: (ResolvedSchemaOperand) -> ResolvedSchemaOperand = CompareOperandNormalizer::normalize,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
    private val projectDiff: (SchemaDiff) -> DiffView,
    private val urlScrubber: (String) -> String = { it },
    private val ensureParentDirectories: (Path) -> Unit = { it.parent?.toFile()?.mkdirs() },
    private val fileWriter: (Path, String) -> Unit = { path, content -> path.writeText(content) },
    private val renderPlain: (SchemaCompareDocument) -> String,
    private val renderJson: (SchemaCompareDocument) -> String,
    private val renderYaml: (SchemaCompareDocument) -> String,
    private val printError: (message: String, source: String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {
    private val userFacingErrors = UserFacingErrors(urlScrubber)
    private val userFacingPrintError = userFacingErrors.printError(printError)
    private val userFacingStderr = userFacingErrors.stderrSink(stderr)

    fun execute(request: SchemaCompareRequest): Int {
        // 1. Parse operands
        val sourceOp: CompareOperand
        val targetOp: CompareOperand
        try {
            sourceOp = operandParser(request.source)
            targetOp = operandParser(request.target)
        } catch (e: IllegalArgumentException) {
            userFacingPrintError("Invalid operand: ${e.message}", request.source)
            return 2
        }

        // 2. Output collision check (only for file operands)
        if (request.output != null) {
            val normalizedOutput = request.output.toAbsolutePath().normalize()
            if (sourceOp is CompareOperand.File &&
                normalizedOutput == sourceOp.path.toAbsolutePath().normalize()) {
                userFacingPrintError("Output path must not be the same as source or target", request.output.toString())
                return 2
            }
            if (targetOp is CompareOperand.File &&
                normalizedOutput == targetOp.path.toAbsolutePath().normalize()) {
                userFacingPrintError("Output path must not be the same as source or target", request.output.toString())
                return 2
            }
        }

        // 3. Load source operand
        val sourceResolved = loadOperand(sourceOp, request.source, request.cliConfigPath) ?: return lastExitCode

        // 4. Load target operand
        val targetResolved = loadOperand(targetOp, request.target, request.cliConfigPath) ?: return lastExitCode

        // 5. Normalize reverse markers
        val sourceNormalized: ResolvedSchemaOperand
        val targetNormalized: ResolvedSchemaOperand
        try {
            sourceNormalized = normalizer(sourceResolved)
            targetNormalized = normalizer(targetResolved)
        } catch (e: IllegalStateException) {
            userFacingPrintError("Invalid reverse marker: ${e.message}", request.source)
            return 7
        }

        // 6. Validate both
        if (!sourceNormalized.validation.isValid || !targetNormalized.validation.isValid) {
            val doc = SchemaCompareDocument(
                status = "invalid",
                exitCode = 3,
                source = sourceResolved.reference,
                target = targetResolved.reference,
                summary = SchemaCompareSummary(),
                diff = null,
                validation = CompareValidation(
                    source = sourceNormalized.validation,
                    target = targetNormalized.validation,
                ),
                sourceOperand = operandInfo(sourceResolved),
                targetOperand = operandInfo(targetResolved),
            )
            return outputDocument(request, doc) ?: 3
        }

        // 7. Emit warnings on stderr (plain mode)
        if (request.outputFormat == "plain") {
            emitWarnings(sourceNormalized, "source", request)
            emitWarnings(targetNormalized, "target", request)
        }

        // 8. Compare and project
        val diff = comparator(sourceNormalized.schema, targetNormalized.schema)
        val identical = diff.isEmpty()
        val diffView = if (identical) null else projectDiff(diff)

        // 9. Build document
        val summary = buildSummary(diff)
        val hasWarnings = sourceNormalized.validation.warnings.isNotEmpty() ||
            targetNormalized.validation.warnings.isNotEmpty()
        val validation = if (hasWarnings) CompareValidation(
            source = sourceNormalized.validation,
            target = targetNormalized.validation,
        ) else null

        val doc = SchemaCompareDocument(
            status = if (identical) "identical" else "different",
            exitCode = if (identical) 0 else 1,
            source = sourceResolved.reference,
            target = targetResolved.reference,
            summary = summary,
            diff = diffView,
            validation = validation,
            sourceOperand = operandInfo(sourceResolved),
            targetOperand = operandInfo(targetResolved),
        )

        // 10. Render and output
        return outputDocument(request, doc) ?: doc.exitCode
    }

    private var lastExitCode: Int = 7

    private fun loadOperand(
        operand: CompareOperand,
        rawRef: String,
        configPath: Path?,
    ): ResolvedSchemaOperand? {
        return when (operand) {
            is CompareOperand.File -> {
                try {
                    fileLoader(operand)
                } catch (e: Exception) {
                    userFacingPrintError("Failed to read operand: ${e.message}", rawRef)
                    lastExitCode = 7
                    null
                }
            }
            is CompareOperand.Database -> {
                val loader = dbLoader
                if (loader == null) {
                    userFacingPrintError("Database operands require a DB loader", rawRef)
                    lastExitCode = 2
                    return null
                }
                try {
                    loader(operand, configPath)
                } catch (e: CompareConfigException) {
                    userFacingPrintError("Config/URL error: ${e.message}", rawRef)
                    lastExitCode = 7
                    null
                } catch (e: Exception) {
                    userFacingPrintError("Connection/metadata error: ${e.message}", rawRef)
                    lastExitCode = 4
                    null
                }
            }
        }
    }

    private fun emitWarnings(operand: ResolvedSchemaOperand, side: String, request: SchemaCompareRequest) {
        for (w in operand.validation.warnings) {
            userFacingStderr("  Warning [${w.code}] ($side): ${w.message}")
        }
        for (note in operand.notes) {
            if (note.severity == SchemaReadSeverity.INFO && !request.verbose) continue
            userFacingStderr("  ${note.severity.name} [${note.code}] ($side) ${note.objectName}: ${note.message}")
        }
        for (skip in operand.skippedObjects) {
            userFacingStderr("  SKIPPED [${skip.code ?: "-"}] ($side) ${skip.type} ${skip.name}: ${skip.reason}")
        }
    }

    private fun buildSummary(diff: SchemaDiff) = SchemaCompareSummary(
        tablesAdded = diff.tablesAdded.size,
        tablesRemoved = diff.tablesRemoved.size,
        tablesChanged = diff.tablesChanged.size,
        customTypesAdded = diff.customTypesAdded.size,
        customTypesRemoved = diff.customTypesRemoved.size,
        customTypesChanged = diff.customTypesChanged.size,
        viewsAdded = diff.viewsAdded.size,
        viewsRemoved = diff.viewsRemoved.size,
        viewsChanged = diff.viewsChanged.size,
        sequencesAdded = diff.sequencesAdded.size,
        sequencesRemoved = diff.sequencesRemoved.size,
        sequencesChanged = diff.sequencesChanged.size,
        functionsAdded = diff.functionsAdded.size,
        functionsRemoved = diff.functionsRemoved.size,
        functionsChanged = diff.functionsChanged.size,
        proceduresAdded = diff.proceduresAdded.size,
        proceduresRemoved = diff.proceduresRemoved.size,
        proceduresChanged = diff.proceduresChanged.size,
        triggersAdded = diff.triggersAdded.size,
        triggersRemoved = diff.triggersRemoved.size,
        triggersChanged = diff.triggersChanged.size,
    )

    private fun operandInfo(op: ResolvedSchemaOperand) = OperandInfo(
        reference = op.reference,
        validation = op.validation,
        notes = op.notes,
        skippedObjects = op.skippedObjects,
    )

    private fun outputDocument(request: SchemaCompareRequest, doc: SchemaCompareDocument): Int? {
        val rendered = when (request.outputFormat) {
            "json" -> renderJson(doc)
            "yaml" -> renderYaml(doc)
            else -> renderPlain(doc)
        }

        if (request.output != null) {
            try {
                ensureParentDirectories(request.output)
                fileWriter(request.output, rendered)
            } catch (e: Exception) {
                userFacingPrintError("Failed to write output: ${e.message}", request.output.toString())
                return 7
            }
        } else {
            if (!request.quiet || doc.exitCode != 0) {
                stdout(rendered)
            }
        }
        return null
    }
}
