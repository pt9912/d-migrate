package dev.dmigrate.cli.commands

import dev.dmigrate.cli.migration.ArtifactCollisionChecker
import dev.dmigrate.cli.migration.DdlNormalizer
import dev.dmigrate.cli.migration.MigrationIdentityResolver
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SpatialProfilePolicy
import dev.dmigrate.migration.MigrationBundle
import dev.dmigrate.migration.MigrationRollback
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.ToolExportResult
import dev.dmigrate.migration.ToolExportSeverity
import dev.dmigrate.migration.ToolMigrationExporter
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Immutable DTO with all inputs for `d-migrate export <tool>`.
 */
data class ToolExportRequest(
    val tool: MigrationTool,
    val source: Path,
    val output: Path,
    val target: String,
    val version: String? = null,
    val spatialProfile: String? = null,
    val generateRollback: Boolean,
    val report: Path? = null,
    val verbose: Boolean,
    val quiet: Boolean,
)

/**
 * Core logic for `d-migrate export <tool>`. All external collaborators
 * are constructor-injected so every branch is unit-testable without a
 * CLI framework, filesystem, or real DDL generator.
 *
 * Exit codes:
 * - 0 success
 * - 2 invalid CLI flags (missing --target, --version, bad dialect/profile)
 * - 3 schema validation failure
 * - 7 parse/I/O/render/collision error
 */
class ToolExportRunner(
    private val schemaReader: (Path) -> SchemaDefinition,
    private val validator: (SchemaDefinition) -> ValidationResult =
        { SchemaValidator().validate(it) },
    private val generatorLookup: (DatabaseDialect) -> DdlGenerator,
    private val exporterLookup: (MigrationTool) -> ToolMigrationExporter,
    private val fileWriter: (Path, String) -> Unit =
        { path, content -> path.writeText(content) },
    private val reportWriter: (Path, ToolExportReportData) -> Unit =
        { path, data -> path.writeText(ToolExportReportRenderer.render(data)) },
    private val existingPaths: (Path) -> Set<String> = { emptySet() },
    private val mkdirs: (Path) -> Unit = { it.toFile().mkdirs() },
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    fun execute(request: ToolExportRequest): Int {
        // ─── 1. Parse dialect ───────────────────────────────────
        val dialect = try {
            DatabaseDialect.fromString(request.target)
        } catch (e: IllegalArgumentException) {
            stderr("[ERROR] ${e.message}")
            return 2
        }

        // ─── 2. Resolve spatial profile ─────────────────────────
        val profileResult = SpatialProfilePolicy.resolve(dialect, request.spatialProfile)
        val spatialProfile = when (profileResult) {
            is SpatialProfilePolicy.Result.Resolved -> profileResult.profile
            is SpatialProfilePolicy.Result.UnknownProfile -> {
                stderr("[ERROR] Unknown spatial profile '${profileResult.raw}'")
                return 2
            }
            is SpatialProfilePolicy.Result.NotAllowedForDialect -> {
                stderr("[ERROR] Spatial profile '${profileResult.profile.cliName}' not allowed for ${profileResult.dialect.name.lowercase()}")
                return 2
            }
        }
        val options = DdlGenerationOptions(spatialProfile = spatialProfile)

        // ─── 3. Read schema ─────────────────────────────────────
        val schema = try {
            schemaReader(request.source)
        } catch (e: Exception) {
            stderr("[ERROR] Failed to parse schema file: ${e.message}")
            return 7
        }

        // ─── 4. Validate ────────────────────────────────────────
        val validationResult = validator(schema)
        if (!validationResult.isValid) {
            stderr("[ERROR] Schema validation failed (${validationResult.errors.size} errors)")
            return 3
        }

        // ─── 5. Resolve identity ────────────────────────────────
        val identity = try {
            MigrationIdentityResolver.resolve(
                tool = request.tool,
                dialect = dialect,
                cliVersion = request.version,
                schemaVersion = schema.version,
                schemaName = schema.name,
            )
        } catch (e: MigrationIdentityResolver.ResolutionException) {
            stderr("[ERROR] ${e.message}")
            return 2
        }

        // ─── 6. Generate DDL ────────────────────────────────────
        val generator = generatorLookup(dialect)
        val upResult = generator.generate(schema, options)
        val up = DdlNormalizer.normalize(upResult)

        val downResult: DdlResult?
        val rollback = if (request.generateRollback) {
            val dr = generator.generateRollback(schema, options)
            downResult = dr
            MigrationRollback.Requested(DdlNormalizer.normalize(dr))
        } else {
            downResult = null
            MigrationRollback.NotRequested
        }

        // ─── 7. Build bundle ────────────────────────────────────
        val bundle = MigrationBundle(
            identity = identity,
            schema = schema,
            options = options,
            up = up,
            rollback = rollback,
        )

        // ─── 8. Render artifacts ────────────────────────────────
        val exporter = exporterLookup(request.tool)
        val exportResult = try {
            exporter.render(bundle)
        } catch (e: Exception) {
            stderr("[ERROR] Render failed: ${e.message}")
            return 7
        }

        // ─── 9. Check collisions ────────────────────────────────
        val inRunCollisions = ArtifactCollisionChecker.findInRunCollisions(exportResult.artifacts)
        if (inRunCollisions.isNotEmpty()) {
            for (c in inRunCollisions) stderr("[ERROR] ${c.reason}")
            return 7
        }

        val existing = try {
            existingPaths(request.output)
        } catch (e: Exception) {
            stderr("[ERROR] Failed to scan output directory: ${e.message}")
            return 7
        }
        val fileCollisions = ArtifactCollisionChecker.findExistingFileCollisions(
            exportResult.artifacts, existing
        )
        if (fileCollisions.isNotEmpty()) {
            for (c in fileCollisions) stderr("[ERROR] ${c.reason}")
            return 7
        }

        // ─── 9b. Check report path collisions ──────────────────────
        if (request.report != null) {
            val reportCanonical = request.report.normalize().toAbsolutePath()

            // Report must not collide with any artifact
            for (artifact in exportResult.artifacts) {
                val artifactCanonical = request.output.resolve(artifact.relativePath.path)
                    .normalize().toAbsolutePath()
                if (artifactCanonical == reportCanonical) {
                    stderr("[ERROR] Report path collides with artifact: ${artifact.relativePath.normalized}")
                    return 7
                }
            }

            // Report must not silently overwrite an existing file
            val reportExisting = existing.any { path ->
                request.output.resolve(path).normalize().toAbsolutePath() == reportCanonical
            }
            val reportFileExists = try {
                java.nio.file.Files.exists(reportCanonical)
            } catch (_: Exception) { false }
            if (reportExisting || reportFileExists) {
                stderr("[ERROR] Report file already exists: ${request.report}")
                return 7
            }
        }

        // ─── 10. Write artifacts ────────────────────────────────
        mkdirs(request.output)
        for (artifact in exportResult.artifacts) {
            val target = request.output.resolve(artifact.relativePath.path)
            target.parent?.let { mkdirs(it) }
            try {
                fileWriter(target, artifact.content)
            } catch (e: Exception) {
                stderr("[ERROR] Failed to write ${artifact.relativePath.normalized}: ${e.message}")
                return 7
            }
            if (!request.quiet) stderr("  Written: ${artifact.relativePath.normalized}")
        }

        // ─── 11. Print diagnostics (up + down + export) ────────
        printGeneratorNotes(upResult, request.verbose)
        if (downResult != null) printGeneratorNotes(downResult, request.verbose)
        printExportNotes(exportResult, request.verbose)

        // ─── 12. Write report ───────────────────────────────────
        if (request.report != null) {
            val reportData = ToolExportReportData(
                source = request.source,
                tool = request.tool,
                dialect = dialect,
                identity = identity,
                upResult = upResult,
                downResult = downResult,
                exportResult = exportResult,
            )
            try {
                reportWriter(request.report, reportData)
                if (!request.quiet) stderr("  Report written to ${request.report}")
            } catch (e: Exception) {
                stderr("[ERROR] Failed to write report: ${e.message}")
                return 7
            }
        }

        return 0
    }

    private fun printGeneratorNotes(result: DdlResult, verbose: Boolean) {
        for (note in result.notes) {
            when (note.type) {
                NoteType.WARNING ->
                    stderr("  Warning [${note.code}]: ${note.message}")
                NoteType.ACTION_REQUIRED -> {
                    stderr("  Action required [${note.code}]: ${note.message}")
                    if (note.hint != null) stderr("    Hint: ${note.hint}")
                }
                NoteType.INFO ->
                    if (verbose) stderr("  Info [${note.code}]: ${note.message}")
            }
        }
        for (skip in result.skippedObjects) {
            val codePrefix = if (skip.code != null) " [${skip.code}]" else ""
            stderr("  Skipped$codePrefix ${skip.type} '${skip.name}': ${skip.reason}")
            if (skip.hint != null) stderr("    Hint: ${skip.hint}")
        }
    }

    private fun printExportNotes(result: ToolExportResult, verbose: Boolean) {
        for (note in result.exportNotes) {
            when (note.severity) {
                ToolExportSeverity.WARNING ->
                    stderr("  Warning [${note.code}]: ${note.message}")
                ToolExportSeverity.ACTION_REQUIRED -> {
                    stderr("  Action required [${note.code}]: ${note.message}")
                    if (note.hint != null) stderr("    Hint: ${note.hint}")
                }
                ToolExportSeverity.INFO ->
                    if (verbose) stderr("  Info [${note.code}]: ${note.message}")
            }
        }
    }
}

/**
 * Data collected during export for the optional report sidecar.
 */
data class ToolExportReportData(
    val source: Path,
    val tool: MigrationTool,
    val dialect: DatabaseDialect,
    val identity: dev.dmigrate.migration.MigrationIdentity,
    val upResult: DdlResult,
    val downResult: DdlResult?,
    val exportResult: ToolExportResult,
)

/**
 * Renders a minimal YAML report sidecar for tool export.
 */
internal object ToolExportReportRenderer {

    fun render(data: ToolExportReportData): String = buildString {
        appendLine("source: \"${escapeYaml(data.source.toString())}\"")
        appendLine("tool: ${data.tool.name.lowercase()}")
        appendLine("dialect: ${data.dialect.name.lowercase()}")
        appendLine("version: \"${escapeYaml(data.identity.version)}\"")
        appendLine("versionSource: ${data.identity.versionSource.name}")
        appendLine("slug: \"${escapeYaml(data.identity.slug)}\"")

        if (data.upResult.notes.isNotEmpty()) {
            appendLine("notes:")
            for (note in data.upResult.notes) {
                appendLine("  - code: \"${note.code}\"")
                appendLine("    type: ${note.type.name}")
                appendLine("    object: \"${escapeYaml(note.objectName)}\"")
                appendLine("    message: \"${escapeYaml(note.message)}\"")
            }
        }

        if (data.downResult != null && data.downResult.notes.isNotEmpty()) {
            appendLine("rollbackNotes:")
            for (note in data.downResult.notes) {
                appendLine("  - code: \"${note.code}\"")
                appendLine("    type: ${note.type.name}")
                appendLine("    object: \"${escapeYaml(note.objectName)}\"")
                appendLine("    message: \"${escapeYaml(note.message)}\"")
            }
        }

        if (data.upResult.skippedObjects.isNotEmpty()) {
            appendLine("skippedObjects:")
            for (skip in data.upResult.skippedObjects) {
                appendLine("  - type: \"${escapeYaml(skip.type)}\"")
                appendLine("    name: \"${escapeYaml(skip.name)}\"")
                appendLine("    reason: \"${escapeYaml(skip.reason)}\"")
            }
        }

        if (data.downResult != null && data.downResult.skippedObjects.isNotEmpty()) {
            appendLine("rollbackSkippedObjects:")
            for (skip in data.downResult.skippedObjects) {
                appendLine("  - type: \"${escapeYaml(skip.type)}\"")
                appendLine("    name: \"${escapeYaml(skip.name)}\"")
                appendLine("    reason: \"${escapeYaml(skip.reason)}\"")
            }
        }

        if (data.exportResult.exportNotes.isNotEmpty()) {
            appendLine("exportNotes:")
            for (note in data.exportResult.exportNotes) {
                appendLine("  - code: \"${note.code}\"")
                appendLine("    severity: ${note.severity.name}")
                appendLine("    message: \"${escapeYaml(note.message)}\"")
            }
        }

        appendLine("artifacts:")
        for (artifact in data.exportResult.artifacts) {
            appendLine("  - path: \"${escapeYaml(artifact.relativePath.normalized)}\"")
            appendLine("    kind: \"${artifact.kind}\"")
        }
    }

    private fun escapeYaml(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
