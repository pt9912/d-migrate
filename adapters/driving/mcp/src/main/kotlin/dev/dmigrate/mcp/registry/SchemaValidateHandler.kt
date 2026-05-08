package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.mcp.registry.JsonArgs.optEnum
import dev.dmigrate.mcp.registry.JsonArgs.optObject
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaFindingSeverity
import dev.dmigrate.mcp.schema.SchemaSourceInput
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.schema.Strictness
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalContext

/**
 * AP 6.4: `schema_validate` per `ImpPlan-0.9.6-C.md` §6.4.
 *
 * Strictness modes:
 * - `lenient` (default): warnings stay warnings; tool succeeds as
 *   long as `errors.isEmpty()`.
 * - `strict`: warnings count as errors for the `valid` boolean. The
 *   findings list still distinguishes severities so clients can
 *   triage.
 *
 * Findings are capped at [McpLimitsConfig.maxInlineFindings]; the
 * `truncated` flag is `true` when more findings exist than fit
 * inline. AP 6.19 wires the [artifactSink] so the full findings set
 * is persisted as an artefact in that case and the inline payload
 * carries the corresponding `artifactRef`. Phase-B unit tests can
 * leave [artifactSink] null — the handler then falls back to the
 * pre-AP-6.19 truncate-only behaviour.
 */
internal class SchemaValidateHandler(
    private val resolver: SchemaSourceResolver,
    private val contentLoader: SchemaContentLoader,
    private val validator: SchemaValidator,
    private val limits: McpLimitsConfig,
    /**
     * AP 6.23: required (non-null). The output schema's
     * `truncated → artifactRef` coupling is unconditional; production
     * wiring always provides a sink, and tests get a stub via the
     * test-only `handler()` helper. A nullable parameter would be
     * incompatible with the schema's `if/then` constraint.
     */
    private val artifactSink: ArtifactSink,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = parseArguments(context.arguments)
        val source = resolver.resolve(
            SchemaSourceInput(schema = args.schema, schemaRef = args.schemaRef),
            context.principal,
        )
        val schema = contentLoader.load(source, format = args.format)
        val result = validator.validate(schema)
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(
                        buildPayload(result, args.strictness, context.principal, context.requestId),
                    ),
                    mimeType = "application/json",
                ),
            ),
        )
    }

    private fun parseArguments(raw: JsonElement?): SchemaValidateArgs {
        val obj = JsonArgs.requireObject(raw)
        return SchemaValidateArgs(
            schema = obj.optObject("schema"),
            schemaRef = obj.optString("schemaRef"),
            format = obj.optString("format"),
            strictness = obj.optEnum("strictness", Strictness.ALLOWED)
                ?.let(Strictness::fromWire)
                ?: Strictness.LENIENT,
        )
    }

    private fun buildPayload(
        result: ValidationResult,
        strictness: Strictness,
        principal: PrincipalContext,
        requestId: String,
    ): Map<String, Any?> {
        val errorFindings = result.errors.map {
            projectFinding(SchemaFindingSeverity.ERROR, it.code, it.objectPath, it.message)
        }
        val warningFindings = result.warnings.map {
            projectFinding(SchemaFindingSeverity.WARNING, it.code, it.objectPath, it.message)
        }
        val combined = errorFindings + warningFindings
        val cap = limits.maxInlineFindings
        val truncated = combined.size > cap
        val inline = if (truncated) combined.take(cap) else combined
        // AP 6.19: when the inline cap kicks in, persist the FULL
        // findings list as an artefact so clients can fetch the
        // remainder via `artifact_chunk_get`. The inline payload
        // still carries summary + capped findings + `truncated=true`
        // for backward compatibility with Phase-A consumers.
        val artifactRef = if (truncated) {
            persistFullFindings(artifactSink, combined, principal)
        } else {
            null
        }
        val effectiveValid = when (strictness) {
            Strictness.STRICT -> result.isValid && warningFindings.isEmpty()
            Strictness.LENIENT -> result.isValid
        }
        return buildMap {
            put("valid", effectiveValid)
            put("summary", summary(errorFindings.size, warningFindings.size, effectiveValid))
            put("findings", inline)
            put("truncated", truncated)
            if (artifactRef != null) put("artifactRef", artifactRef)
            put("executionMeta", mapOf("requestId" to requestId))
        }
    }

    private fun persistFullFindings(
        sink: ArtifactSink,
        allFindings: List<Map<String, String>>,
        principal: PrincipalContext,
    ): String {
        val bytes = gson.toJson(allFindings).toByteArray(Charsets.UTF_8)
        val uri = sink.writeReadOnly(
            principal = principal,
            kind = ArtifactKind.OTHER,
            contentType = "application/json",
            filename = "schema-validate-findings.json",
            content = bytes,
            maxArtifactBytes = limits.maxArtifactUploadBytes,
        )
        return uri.render()
    }

    // AP 6.17: dynamic strings (path, message) come from validator
    // rules that read user-supplied schema bytes; scrubbing keeps
    // accidental Bearer/approval-token/connection-URL leakage out
    // of the wire response. `code` is a curated constant alphabet
    // and not scrubbed.
    private fun projectFinding(
        severity: String,
        code: String,
        path: String,
        message: String,
    ): Map<String, String> = mapOf(
        "severity" to severity,
        "code" to code,
        "path" to SecretScrubber.scrub(path),
        "message" to SecretScrubber.scrub(message),
    )

    private fun summary(errorCount: Int, warningCount: Int, valid: Boolean): String = when {
        valid && warningCount == 0 -> "Schema is valid."
        valid -> "Schema is valid (with $warningCount warning(s))."
        else -> "Schema invalid: $errorCount error(s), $warningCount warning(s)."
    }

    private data class SchemaValidateArgs(
        val schema: JsonElement?,
        val schemaRef: String?,
        val format: String?,
        val strictness: Strictness,
    )
}
