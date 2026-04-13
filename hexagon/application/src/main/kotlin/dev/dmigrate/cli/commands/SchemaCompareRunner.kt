package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import java.nio.file.Path
import kotlin.io.path.writeText

// ── Request ──────────────────────────────────────────────────────

data class SchemaCompareRequest(
    val source: Path,
    val target: Path,
    val output: Path?,
    val outputFormat: String,
    val quiet: Boolean,
)

// ── Stable CLI projection (primitive-first, §4.2/§4.3) ──────────

data class SchemaCompareDocument(
    val status: String,
    val exitCode: Int,
    val source: String,
    val target: String,
    val summary: SchemaCompareSummary,
    val diff: DiffView?,
    val validation: CompareValidation? = null,
)

data class SchemaCompareSummary(
    val tablesAdded: Int = 0,
    val tablesRemoved: Int = 0,
    val tablesChanged: Int = 0,
    val enumTypesAdded: Int = 0,
    val enumTypesRemoved: Int = 0,
    val enumTypesChanged: Int = 0,
    val viewsAdded: Int = 0,
    val viewsRemoved: Int = 0,
    val viewsChanged: Int = 0,
) {
    val totalChanges: Int get() = tablesAdded + tablesRemoved + tablesChanged +
        enumTypesAdded + enumTypesRemoved + enumTypesChanged +
        viewsAdded + viewsRemoved + viewsChanged
}

data class CompareValidation(
    val source: ValidationResult? = null,
    val target: ValidationResult? = null,
)

// ── DiffView: stable, primitive-only projection of SchemaDiff ────

data class DiffView(
    val schemaMetadata: MetadataChangeView? = null,
    val enumTypesAdded: List<EnumSummaryView> = emptyList(),
    val enumTypesRemoved: List<EnumSummaryView> = emptyList(),
    val enumTypesChanged: List<EnumChangeView> = emptyList(),
    val tablesAdded: List<TableSummaryView> = emptyList(),
    val tablesRemoved: List<TableSummaryView> = emptyList(),
    val tablesChanged: List<TableChangeView> = emptyList(),
    val viewsAdded: List<ViewSummaryView> = emptyList(),
    val viewsRemoved: List<ViewSummaryView> = emptyList(),
    val viewsChanged: List<ViewChangeView> = emptyList(),
)

data class MetadataChangeView(
    val name: StringChange? = null,
    val version: StringChange? = null,
)

data class StringChange(val before: String, val after: String)
data class NullableStringChange(val before: String?, val after: String?)
data class StringListChange(val before: List<String>, val after: List<String>)

data class EnumSummaryView(val name: String, val values: List<String>)
data class EnumChangeView(val name: String, val before: List<String>, val after: List<String>)

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
    private val schemaReader: (Path) -> SchemaDefinition,
    private val validator: (SchemaDefinition) -> ValidationResult,
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
        // 1. Output collision check
        if (request.output != null) {
            val normalizedOutput = request.output.toAbsolutePath().normalize()
            val normalizedSource = request.source.toAbsolutePath().normalize()
            val normalizedTarget = request.target.toAbsolutePath().normalize()
            if (normalizedOutput == normalizedSource || normalizedOutput == normalizedTarget) {
                printError("Output path must not be the same as source or target", request.output.toString())
                return 2
            }
        }

        // 2. Read source
        val sourceSchema = try {
            schemaReader(request.source)
        } catch (e: Exception) {
            printError("Failed to parse schema file: ${e.message}", request.source.toString())
            return 7
        }

        // 3. Read target
        val targetSchema = try {
            schemaReader(request.target)
        } catch (e: Exception) {
            printError("Failed to parse schema file: ${e.message}", request.target.toString())
            return 7
        }

        // 4. Validate both
        val sourceValidation = validator(sourceSchema)
        val targetValidation = validator(targetSchema)

        if (!sourceValidation.isValid || !targetValidation.isValid) {
            val doc = SchemaCompareDocument(
                status = "invalid",
                exitCode = 3,
                source = request.source.toString(),
                target = request.target.toString(),
                summary = SchemaCompareSummary(),
                diff = null,
                validation = CompareValidation(
                    source = sourceValidation,
                    target = targetValidation,
                ),
            )
            return outputDocument(request, doc) ?: 3
        }

        // 5. Emit validation warnings on stderr (plain mode)
        val hasWarnings = sourceValidation.warnings.isNotEmpty() || targetValidation.warnings.isNotEmpty()
        if (hasWarnings && request.outputFormat == "plain") {
            for (w in sourceValidation.warnings) {
                stderr("  Warning [${w.code}] (source): ${w.message}")
            }
            for (w in targetValidation.warnings) {
                stderr("  Warning [${w.code}] (target): ${w.message}")
            }
        }

        // 6. Compare and project
        val diff = comparator(sourceSchema, targetSchema)
        val identical = diff.isEmpty()
        val diffView = if (identical) null else projectDiff(diff)

        // 7. Build document
        val summary = SchemaCompareSummary(
            tablesAdded = diff.tablesAdded.size,
            tablesRemoved = diff.tablesRemoved.size,
            tablesChanged = diff.tablesChanged.size,
            enumTypesAdded = diff.enumTypesAdded.size,
            enumTypesRemoved = diff.enumTypesRemoved.size,
            enumTypesChanged = diff.enumTypesChanged.size,
            viewsAdded = diff.viewsAdded.size,
            viewsRemoved = diff.viewsRemoved.size,
            viewsChanged = diff.viewsChanged.size,
        )
        val validation = if (hasWarnings) CompareValidation(
            source = sourceValidation,
            target = targetValidation,
        ) else null

        val doc = SchemaCompareDocument(
            status = if (identical) "identical" else "different",
            exitCode = if (identical) 0 else 1,
            source = request.source.toString(),
            target = request.target.toString(),
            summary = summary,
            diff = diffView,
            validation = validation,
        )

        // 8. Render and output
        return outputDocument(request, doc) ?: doc.exitCode
    }

    /**
     * Renders and outputs the document. Returns null on success, or 7 on
     * write failure.
     */
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
