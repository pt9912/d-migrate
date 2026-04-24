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

    private data class ResolvedPreflight(
        val dialect: DatabaseDialect,
        val options: DdlGenerationOptions,
        val schema: SchemaDefinition,
        val identity: dev.dmigrate.migration.MigrationIdentity,
    )

    private sealed interface PreflightResult {
        data class Ok(val value: ResolvedPreflight) : PreflightResult
        data class Exit(val code: Int) : PreflightResult
    }

    fun execute(request: ToolExportRequest): Int {
        val pre = when (val r = resolvePreflight(request)) {
            is PreflightResult.Ok -> r.value
            is PreflightResult.Exit -> return r.code
        }
        return executeWithPreflight(request, pre)
    }

    private fun resolvePreflight(request: ToolExportRequest): PreflightResult {
        val dialect = try {
            DatabaseDialect.fromString(request.target)
        } catch (e: IllegalArgumentException) {
            stderr("[ERROR] ${e.message}"); return PreflightResult.Exit(2)
        }

        val spatialProfile = when (val profileResult = SpatialProfilePolicy.resolve(dialect, request.spatialProfile)) {
            is SpatialProfilePolicy.Result.Resolved -> profileResult.profile
            is SpatialProfilePolicy.Result.UnknownProfile -> {
                stderr("[ERROR] Unknown spatial profile '${profileResult.raw}'"); return PreflightResult.Exit(2)
            }
            is SpatialProfilePolicy.Result.NotAllowedForDialect -> {
                stderr(
                    "[ERROR] Spatial profile '${profileResult.profile.cliName}' not allowed for " +
                        profileResult.dialect.name.lowercase()
                )
                return PreflightResult.Exit(2)
            }
        }
        val options = DdlGenerationOptions(spatialProfile = spatialProfile)

        val schema = try { schemaReader(request.source) } catch (e: Exception) {
            stderr("[ERROR] Failed to parse schema file: ${e.message}"); return PreflightResult.Exit(7)
        }

        val validationResult = validator(schema)
        if (!validationResult.isValid) {
            stderr("[ERROR] Schema validation failed (${validationResult.errors.size} errors)")
            return PreflightResult.Exit(3)
        }

        val identity = try {
            MigrationIdentityResolver.resolve(
                tool = request.tool, dialect = dialect,
                cliVersion = request.version, schemaVersion = schema.version, schemaName = schema.name,
            )
        } catch (e: MigrationIdentityResolver.ResolutionException) {
            stderr("[ERROR] ${e.message}"); return PreflightResult.Exit(2)
        }

        return PreflightResult.Ok(ResolvedPreflight(dialect, options, schema, identity))
    }

    private fun executeWithPreflight(request: ToolExportRequest, pre: ResolvedPreflight): Int {
        val generator = generatorLookup(pre.dialect)
        val upResult = generator.generate(pre.schema, pre.options)
        val up = DdlNormalizer.normalize(upResult)

        val downResult: DdlResult?
        val rollback = if (request.generateRollback) {
            val dr = generator.generateRollback(pre.schema, pre.options)
            downResult = dr
            MigrationRollback.Requested(DdlNormalizer.normalize(dr))
        } else { downResult = null; MigrationRollback.NotRequested }

        val bundle = MigrationBundle(
            identity = pre.identity, schema = pre.schema, options = pre.options, up = up, rollback = rollback,
        )

        val exportResult = try { exporterLookup(request.tool).render(bundle) } catch (e: Exception) {
            stderr("[ERROR] Render failed: ${e.message}"); return 7
        }

        checkCollisions(request, exportResult)?.let { return it }
        checkReportCollisions(request, exportResult)?.let { return it }

        writeArtifacts(request, exportResult)?.let { return it }
        printGeneratorNotes(upResult, request.verbose)
        if (downResult != null) printGeneratorNotes(downResult, request.verbose)
        printExportNotes(exportResult, request.verbose)
        writeReport(request, pre, upResult, downResult, exportResult)?.let { return it }

        return 0
    }

    private fun checkCollisions(request: ToolExportRequest, exportResult: ToolExportResult): Int? {
        val inRunCollisions = ArtifactCollisionChecker.findInRunCollisions(exportResult.artifacts)
        if (inRunCollisions.isNotEmpty()) {
            for (c in inRunCollisions) stderr("[ERROR] ${c.reason}")
            return 7
        }
        val existing = try { existingPaths(request.output) } catch (e: Exception) {
            stderr("[ERROR] Failed to scan output directory: ${e.message}"); return 7
        }
        val fileCollisions = ArtifactCollisionChecker.findExistingFileCollisions(exportResult.artifacts, existing)
        if (fileCollisions.isNotEmpty()) {
            for (c in fileCollisions) stderr("[ERROR] ${c.reason}")
            return 7
        }
        return null
    }

    private fun checkReportCollisions(request: ToolExportRequest, exportResult: ToolExportResult): Int? {
        if (request.report == null) return null
        val reportCanonical = request.report.normalize().toAbsolutePath()
        for (artifact in exportResult.artifacts) {
            val artifactCanonical = request.output.resolve(artifact.relativePath.path).normalize().toAbsolutePath()
            if (artifactCanonical == reportCanonical) {
                stderr("[ERROR] Report path collides with artifact: ${artifact.relativePath.normalized}")
                return 7
            }
        }
        val existing = try { existingPaths(request.output) } catch (_: Exception) { emptySet() }
        val reportExisting = existing.any { path ->
            request.output.resolve(path).normalize().toAbsolutePath() == reportCanonical
        }
        val reportFileExists = try { java.nio.file.Files.exists(reportCanonical) } catch (_: Exception) { false }
        if (reportExisting || reportFileExists) {
            stderr("[ERROR] Report file already exists: ${request.report}")
            return 7
        }
        return null
    }

    private fun writeArtifacts(request: ToolExportRequest, exportResult: ToolExportResult): Int? {
        mkdirs(request.output)
        for (artifact in exportResult.artifacts) {
            val target = request.output.resolve(artifact.relativePath.path)
            target.parent?.let { mkdirs(it) }
            try { fileWriter(target, artifact.content) } catch (e: Exception) {
                stderr("[ERROR] Failed to write ${artifact.relativePath.normalized}: ${e.message}"); return 7
            }
            if (!request.quiet) stderr("  Written: ${artifact.relativePath.normalized}")
        }
        return null
    }

    private fun writeReport(
        request: ToolExportRequest, pre: ResolvedPreflight,
        upResult: DdlResult, downResult: DdlResult?, exportResult: ToolExportResult,
    ): Int? {
        if (request.report == null) return null
        val reportData = ToolExportReportData(
            source = request.source, tool = request.tool, dialect = pre.dialect,
            identity = pre.identity, upResult = upResult, downResult = downResult, exportResult = exportResult,
        )
        return try {
            reportWriter(request.report, reportData)
            if (!request.quiet) stderr("  Report written to ${request.report}")
            null
        } catch (e: Exception) {
            stderr("[ERROR] Failed to write report: ${e.message}"); 7
        }
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
