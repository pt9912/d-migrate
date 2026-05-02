package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.SpatialProfilePolicy
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.NoteType
import dev.dmigrate.mcp.registry.JsonArgs.optEnum
import dev.dmigrate.mcp.registry.JsonArgs.optObject
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaFindingSeverity
import dev.dmigrate.mcp.schema.SchemaSourceInput
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.artifact.ArtifactKind
import java.util.UUID

/**
 * AP 6.5: `schema_generate` per `ImpPlan-0.9.6-C.md` §6.5.
 *
 * Resolves the source via [SchemaSourceResolver], materialises it via
 * [SchemaContentLoader], looks up the dialect generator via
 * [generatorLookup] (defaults to [DatabaseDriverRegistry]) and
 * delegates the heavy lifting to the existing generator pipeline.
 *
 * Output handling:
 * - if the rendered DDL fits inline (<= half of
 *   [McpLimitsConfig.maxToolResponseBytes]), return it as `ddl`
 * - otherwise persist to an artefact via [ArtifactSink] and return
 *   only `artifactRef` + `truncated=true`
 *
 * Generator notes are projected into the `findings` array uniformly
 * across severities:
 * - `NoteType.ACTION_REQUIRED` → severity `error`
 * - `NoteType.WARNING`         → severity `warning`
 * - `NoteType.INFO`            → severity `info`
 * - `SkippedObject`            → severity `error` (the object did not
 *   make it into the DDL — clients should treat it as blocking)
 */
internal class SchemaGenerateHandler(
    private val resolver: SchemaSourceResolver,
    private val contentLoader: SchemaContentLoader,
    private val artifactSink: ArtifactSink,
    private val limits: McpLimitsConfig,
    private val generatorLookup: (DatabaseDialect) -> DdlGenerator = ::lookupViaRegistry,
    private val requestIdProvider: () -> String = ::generateRequestId,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = parseArguments(context.arguments)
        val source = resolver.resolve(
            SchemaSourceInput(schema = args.schema, schemaRef = args.schemaRef),
            context.principal,
        )
        val schemaDef = contentLoader.load(source, format = args.format)
        val options = buildOptions(args)
        val generator = lookupGenerator(args.targetDialect)
        val result = generator.generate(schemaDef, options)
        val ddl = result.render()
        val ddlBytes = ddl.toByteArray(Charsets.UTF_8)
        val inlineThreshold = limits.maxToolResponseBytes / 2
        val payload = if (ddlBytes.size <= inlineThreshold) {
            buildPayload(args.targetDialect, result, ddl = ddl, artifactRef = null, truncated = false)
        } else {
            val uri = artifactSink.writeReadOnly(
                principal = context.principal,
                kind = ArtifactKind.SCHEMA,
                contentType = "text/plain; charset=utf-8",
                filename = "ddl-${args.targetDialect.name.lowercase()}.sql",
                content = ddlBytes,
                maxArtifactBytes = limits.maxArtifactUploadBytes,
            )
            buildPayload(args.targetDialect, result, ddl = null, artifactRef = uri.render(), truncated = true)
        }
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(payload),
                    mimeType = "application/json",
                ),
            ),
        )
    }

    private fun parseArguments(raw: JsonElement?): SchemaGenerateArgs {
        val obj = JsonArgs.requireObject(raw)
        val targetRaw = obj.optString("targetDialect")
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("targetDialect", "is required")),
            )
        val target = parseDialect(targetRaw)
        return SchemaGenerateArgs(
            schema = obj.optObject("schema"),
            schemaRef = obj.optString("schemaRef"),
            format = obj.optString("format"),
            targetDialect = target,
            spatialProfile = obj.optString("spatialProfile"),
            mysqlMode = obj.optEnum("mysqlNamedSequenceMode", MYSQL_MODE_VALUES),
        )
    }

    private fun buildOptions(args: SchemaGenerateArgs): DdlGenerationOptions {
        val profile = when (val resolved = SpatialProfilePolicy.resolve(args.targetDialect, args.spatialProfile)) {
            is SpatialProfilePolicy.Result.Resolved -> resolved.profile
            is SpatialProfilePolicy.Result.UnknownProfile -> throw ValidationErrorException(
                listOf(ValidationViolation("spatialProfile", "unknown profile '${resolved.raw}'")),
            )
            is SpatialProfilePolicy.Result.NotAllowedForDialect -> throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "spatialProfile",
                        "${resolved.profile.cliName} is not valid for ${resolved.dialect.name}",
                    ),
                ),
            )
        }
        val mysqlMode = args.mysqlMode?.let(MysqlNamedSequenceMode::fromCliName)
        return DdlGenerationOptions(spatialProfile = profile, mysqlNamedSequenceMode = mysqlMode)
    }

    @Suppress("SwallowedException")
    private fun lookupGenerator(dialect: DatabaseDialect): DdlGenerator = try {
        generatorLookup(dialect)
    } catch (e: IllegalArgumentException) {
        // No driver registered for the dialect — surface as
        // VALIDATION_ERROR so the client retries with one of the
        // dialects advertised by capabilities_list. Original cause
        // is dropped on purpose: it would expose registry internals
        // (loaded driver names, JVM stack frames) across the trust
        // boundary.
        throw ValidationErrorException(
            listOf(ValidationViolation("targetDialect", e.message ?: "no generator registered for $dialect")),
        )
    }

    private fun buildPayload(
        dialect: DatabaseDialect,
        result: DdlResult,
        ddl: String?,
        artifactRef: String?,
        truncated: Boolean,
    ): Map<String, Any?> {
        val noteFindings = result.notes.map(::projectNote)
        val skippedFindings = result.skippedObjects.map(::projectSkipped)
        val combined = noteFindings + skippedFindings
        val cap = limits.maxInlineFindings
        val findingsTruncated = combined.size > cap
        val findings = if (findingsTruncated) combined.take(cap) else combined
        return buildMap {
            put("dialect", dialect.name)
            put("statementCount", result.statements.size)
            put("summary", summary(result, dialect, truncated))
            put("findings", findings)
            put("truncated", truncated || findingsTruncated)
            if (ddl != null) put("ddl", ddl)
            if (artifactRef != null) put("artifactRef", artifactRef)
            put("executionMeta", mapOf("requestId" to requestIdProvider()))
        }
    }

    // AP 6.17: dynamic strings (objectName, message, hint, skipped
    // reason) flow from generator transformations that read user-
    // supplied schema bytes; scrubbing keeps accidental token /
    // connection-URL leakage out of the wire response.
    private fun projectNote(note: TransformationNote): Map<String, String?> = buildMap {
        put("severity", noteSeverity(note.type))
        put("code", note.code)
        put("path", SecretScrubber.scrub(note.objectName))
        put("message", SecretScrubber.scrub(note.message))
        note.hint?.let { put("hint", SecretScrubber.scrub(it)) }
    }

    private fun projectSkipped(skipped: SkippedObject): Map<String, String?> = buildMap {
        put("severity", SchemaFindingSeverity.ERROR)
        put("code", skipped.code ?: "SKIPPED")
        put("path", SecretScrubber.scrub("${skipped.type}.${skipped.name}"))
        put("message", SecretScrubber.scrub("skipped: ${skipped.reason}"))
        skipped.hint?.let { put("hint", SecretScrubber.scrub(it)) }
    }

    private fun summary(result: DdlResult, dialect: DatabaseDialect, ddlMovedToArtefact: Boolean): String {
        val warnings = result.notes.count { it.type != NoteType.INFO }
        val skipped = result.skippedObjects.size
        val locator = if (ddlMovedToArtefact) "artefact" else "inline"
        return "Generated ${result.statements.size} ${dialect.name} statement(s) " +
            "($warnings warning(s), $skipped skipped); DDL returned $locator."
    }

    private data class SchemaGenerateArgs(
        val schema: JsonElement?,
        val schemaRef: String?,
        val format: String?,
        val targetDialect: DatabaseDialect,
        val spatialProfile: String?,
        val mysqlMode: String?,
    )

    private companion object {
        val MYSQL_MODE_VALUES: Set<String> =
            MysqlNamedSequenceMode.entries.map { it.cliName }.toSet()

        val DIALECT_WIRE_NAMES: Set<String> =
            DatabaseDialect.entries.map { it.name }.toSet()

        /**
         * Wire-strict dialect parser: only the canonical enum names
         * (`POSTGRESQL`/`MYSQL`/`SQLITE`) are accepted. The CLI-style
         * aliases (`pg`, `postgres`, `maria`, `sqlite3`) that
         * `DatabaseDialect.fromString` accepts are rejected here so
         * the handler runtime matches the JSON-Schema enum exactly.
         */
        fun parseDialect(raw: String): DatabaseDialect {
            if (raw !in DIALECT_WIRE_NAMES) {
                throw ValidationErrorException(
                    listOf(
                        ValidationViolation(
                            "targetDialect",
                            "must be one of ${DIALECT_WIRE_NAMES.sorted()}",
                        ),
                    ),
                )
            }
            return DatabaseDialect.valueOf(raw)
        }

        fun noteSeverity(type: NoteType): String = when (type) {
            NoteType.ACTION_REQUIRED -> SchemaFindingSeverity.ERROR
            NoteType.WARNING -> SchemaFindingSeverity.WARNING
            NoteType.INFO -> "info"
        }

        private fun lookupViaRegistry(dialect: DatabaseDialect): DdlGenerator =
            DatabaseDriverRegistry.get(dialect).ddlGenerator()

        fun generateRequestId(): String = "req-${UUID.randomUUID().toString().take(8)}"
    }
}
