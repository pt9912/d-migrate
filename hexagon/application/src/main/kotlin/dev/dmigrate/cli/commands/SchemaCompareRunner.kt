package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.SkippedObject
import java.nio.file.Path
import kotlin.io.path.writeText

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

// ── Operand metadata for structured output ───────────────────────

data class OperandInfo(
    val reference: String,
    val validation: ValidationResult? = null,
    val notes: List<SchemaReadNote> = emptyList(),
    val skippedObjects: List<SkippedObject> = emptyList(),
)

// ── Stable CLI projection (primitive-first) ──────────────────────

data class SchemaCompareDocument(
    val status: String,
    val exitCode: Int,
    val source: String,
    val target: String,
    val summary: SchemaCompareSummary,
    val diff: DiffView?,
    val validation: CompareValidation? = null,
    val sourceOperand: OperandInfo? = null,
    val targetOperand: OperandInfo? = null,
)

data class SchemaCompareSummary(
    val tablesAdded: Int = 0,
    val tablesRemoved: Int = 0,
    val tablesChanged: Int = 0,
    val customTypesAdded: Int = 0,
    val customTypesRemoved: Int = 0,
    val customTypesChanged: Int = 0,
    val viewsAdded: Int = 0,
    val viewsRemoved: Int = 0,
    val viewsChanged: Int = 0,
    val sequencesAdded: Int = 0,
    val sequencesRemoved: Int = 0,
    val sequencesChanged: Int = 0,
    val functionsAdded: Int = 0,
    val functionsRemoved: Int = 0,
    val functionsChanged: Int = 0,
    val proceduresAdded: Int = 0,
    val proceduresRemoved: Int = 0,
    val proceduresChanged: Int = 0,
    val triggersAdded: Int = 0,
    val triggersRemoved: Int = 0,
    val triggersChanged: Int = 0,
) {
    val totalChanges: Int get() = tablesAdded + tablesRemoved + tablesChanged +
        customTypesAdded + customTypesRemoved + customTypesChanged +
        viewsAdded + viewsRemoved + viewsChanged +
        sequencesAdded + sequencesRemoved + sequencesChanged +
        functionsAdded + functionsRemoved + functionsChanged +
        proceduresAdded + proceduresRemoved + proceduresChanged +
        triggersAdded + triggersRemoved + triggersChanged
}

data class CompareValidation(
    val source: ValidationResult? = null,
    val target: ValidationResult? = null,
)

// ── DiffView: stable, primitive-only projection of SchemaDiff ────

data class DiffView(
    val schemaMetadata: MetadataChangeView? = null,
    val customTypesAdded: List<CustomTypeSummaryView> = emptyList(),
    val customTypesRemoved: List<CustomTypeSummaryView> = emptyList(),
    val customTypesChanged: List<CustomTypeChangeView> = emptyList(),
    val tablesAdded: List<TableSummaryView> = emptyList(),
    val tablesRemoved: List<TableSummaryView> = emptyList(),
    val tablesChanged: List<TableChangeView> = emptyList(),
    val viewsAdded: List<ViewSummaryView> = emptyList(),
    val viewsRemoved: List<ViewSummaryView> = emptyList(),
    val viewsChanged: List<ViewChangeView> = emptyList(),
    val sequencesAdded: List<String> = emptyList(),
    val sequencesRemoved: List<String> = emptyList(),
    val sequencesChanged: List<String> = emptyList(),
    val functionsAdded: List<String> = emptyList(),
    val functionsRemoved: List<String> = emptyList(),
    val functionsChanged: List<String> = emptyList(),
    val proceduresAdded: List<String> = emptyList(),
    val proceduresRemoved: List<String> = emptyList(),
    val proceduresChanged: List<String> = emptyList(),
    val triggersAdded: List<String> = emptyList(),
    val triggersRemoved: List<String> = emptyList(),
    val triggersChanged: List<String> = emptyList(),
)

data class MetadataChangeView(
    val name: StringChange? = null,
    val version: StringChange? = null,
)

data class StringChange(val before: String, val after: String)
data class NullableStringChange(val before: String?, val after: String?)
data class StringListChange(val before: List<String>, val after: List<String>)

data class CustomTypeSummaryView(val name: String, val kind: String, val detail: String)
data class CustomTypeChangeView(val name: String, val kind: String, val changes: List<String>)

data class TableSummaryView(val name: String, val columnCount: Int)
data class TableChangeView(
    val name: String,
    val columnsAdded: List<ColumnSummaryView> = emptyList(),
    val columnsRemoved: List<String> = emptyList(),
    val columnsChanged: List<ColumnChangeView> = emptyList(),
    val primaryKey: StringListChange? = null,
    val indicesAdded: List<String> = emptyList(),
    val indicesRemoved: List<String> = emptyList(),
    val indicesChanged: List<StringChange> = emptyList(),
    val constraintsAdded: List<String> = emptyList(),
    val constraintsRemoved: List<String> = emptyList(),
    val constraintsChanged: List<StringChange> = emptyList(),
)

data class ColumnSummaryView(val name: String, val type: String)
data class ColumnChangeView(
    val name: String,
    val type: StringChange? = null,
    val required: StringChange? = null,
    val default: NullableStringChange? = null,
    val unique: StringChange? = null,
    val references: NullableStringChange? = null,
)

data class ViewSummaryView(val name: String, val materialized: Boolean)
data class ViewChangeView(
    val name: String,
    val materialized: StringChange? = null,
    val refresh: NullableStringChange? = null,
    val queryChanged: Boolean = false,
    val sourceDialect: NullableStringChange? = null,
)

// ── Runner ────────────────────────────────────────────────────────

class SchemaCompareRunner(
    private val operandParser: (String) -> CompareOperand = CompareOperandParser::parse,
    private val fileLoader: (CompareOperand.File) -> ResolvedSchemaOperand,
    private val dbLoader: ((CompareOperand.Database, Path?) -> ResolvedSchemaOperand)? = null,
    private val normalizer: (ResolvedSchemaOperand) -> ResolvedSchemaOperand = CompareOperandNormalizer::normalize,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
    private val projectDiff: (SchemaDiff) -> DiffView,
    private val ensureParentDirectories: (Path) -> Unit = { it.parent?.toFile()?.mkdirs() },
    private val fileWriter: (Path, String) -> Unit = { path, content -> path.writeText(content) },
    private val renderPlain: (SchemaCompareDocument) -> String,
    private val renderJson: (SchemaCompareDocument) -> String,
    private val renderYaml: (SchemaCompareDocument) -> String,
    private val printError: (message: String, source: String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    fun execute(request: SchemaCompareRequest): Int {
        // 1. Parse operands
        val sourceOp: CompareOperand
        val targetOp: CompareOperand
        try {
            sourceOp = operandParser(request.source)
            targetOp = operandParser(request.target)
        } catch (e: IllegalArgumentException) {
            printError("Invalid operand: ${e.message}", request.source)
            return 2
        }

        // 2. Output collision check (only for file operands)
        if (request.output != null) {
            val normalizedOutput = request.output.toAbsolutePath().normalize()
            if (sourceOp is CompareOperand.File &&
                normalizedOutput == sourceOp.path.toAbsolutePath().normalize()) {
                printError("Output path must not be the same as source or target", request.output.toString())
                return 2
            }
            if (targetOp is CompareOperand.File &&
                normalizedOutput == targetOp.path.toAbsolutePath().normalize()) {
                printError("Output path must not be the same as source or target", request.output.toString())
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
            printError("Invalid reverse marker: ${e.message}", request.source)
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
                    printError("Failed to read operand: ${e.message}", rawRef)
                    lastExitCode = 7
                    null
                }
            }
            is CompareOperand.Database -> {
                val loader = dbLoader
                if (loader == null) {
                    printError("Database operands require a DB loader", rawRef)
                    lastExitCode = 2
                    return null
                }
                try {
                    loader(operand, configPath)
                } catch (e: Exception) {
                    printError("Failed to read DB operand: ${e.message}", rawRef)
                    lastExitCode = 4
                    null
                }
            }
        }
    }

    private fun emitWarnings(operand: ResolvedSchemaOperand, side: String, request: SchemaCompareRequest) {
        for (w in operand.validation.warnings) {
            stderr("  Warning [${w.code}] ($side): ${w.message}")
        }
        // Operand-side reverse notes (same visibility as validation warnings)
        for (note in operand.notes) {
            if (note.severity == SchemaReadSeverity.INFO && !request.verbose) continue
            stderr("  ${note.severity.name} [${note.code}] ($side) ${note.objectName}: ${note.message}")
        }
        for (skip in operand.skippedObjects) {
            stderr("  SKIPPED [${skip.code ?: "-"}] ($side) ${skip.type} ${skip.name}: ${skip.reason}")
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
                printError("Failed to write output: ${e.message}", request.output.toString())
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
