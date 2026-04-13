package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import java.nio.file.Path
import kotlin.io.path.writeText

data class SchemaCompareRequest(
    val source: Path,
    val target: Path,
    val output: Path?,
    val outputFormat: String,
    val quiet: Boolean,
)

data class SchemaCompareDocument(
    val status: String,
    val exitCode: Int,
    val source: String,
    val target: String,
    val summary: SchemaCompareSummary,
    val diff: SchemaDiff?,
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

class SchemaCompareRunner(
    private val schemaReader: (Path) -> SchemaDefinition,
    private val validator: (SchemaDefinition) -> ValidationResult,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
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
            outputDocument(request, doc)
            return 3
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

        // 6. Compare
        val diff = comparator(sourceSchema, targetSchema)

        // 7. Build document
        val identical = diff.isEmpty()
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
            diff = if (identical) null else diff,
            validation = validation,
        )

        // 8. Render and output
        outputDocument(request, doc)
        return doc.exitCode
    }

    private fun outputDocument(request: SchemaCompareRequest, doc: SchemaCompareDocument) {
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
            }
        } else {
            if (!request.quiet || doc.exitCode != 0) {
                stdout(rendered)
            }
        }
    }
}
