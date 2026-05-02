package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.core.validation.ValidationWarning
import dev.dmigrate.mcp.registry.JsonArgs.optEnum
import dev.dmigrate.mcp.registry.JsonArgs.optObject
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaFindingSeverity
import dev.dmigrate.mcp.schema.SchemaSourceInput
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.schema.Strictness
import dev.dmigrate.mcp.server.McpLimitsConfig
import java.util.UUID

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
 * inline (AP 6.13 will fold the remainder into an artefact).
 */
internal class SchemaValidateHandler(
    private val resolver: SchemaSourceResolver,
    private val contentLoader: SchemaContentLoader,
    private val validator: SchemaValidator,
    private val limits: McpLimitsConfig,
    private val requestIdProvider: () -> String = ::generateRequestId,
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
                    text = gson.toJson(buildPayload(result, args.strictness)),
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

    private fun buildPayload(result: ValidationResult, strictness: Strictness): Map<String, Any?> {
        val errorFindings = result.errors.map(::projectError)
        val warningFindings = result.warnings.map(::projectWarning)
        val combined = errorFindings + warningFindings
        val cap = limits.maxInlineFindings
        val truncated = combined.size > cap
        val inline = if (truncated) combined.take(cap) else combined
        val effectiveValid = when (strictness) {
            Strictness.STRICT -> result.isValid && warningFindings.isEmpty()
            Strictness.LENIENT -> result.isValid
        }
        return mapOf(
            "valid" to effectiveValid,
            "summary" to summary(errorFindings.size, warningFindings.size, effectiveValid),
            "findings" to inline,
            "truncated" to truncated,
            "executionMeta" to mapOf("requestId" to requestIdProvider()),
        )
    }

    private fun projectError(error: ValidationError): Map<String, String> = mapOf(
        "severity" to SchemaFindingSeverity.ERROR,
        "code" to error.code,
        "path" to error.objectPath,
        "message" to error.message,
    )

    private fun projectWarning(warning: ValidationWarning): Map<String, String> = mapOf(
        "severity" to SchemaFindingSeverity.WARNING,
        "code" to warning.code,
        "path" to warning.objectPath,
        "message" to warning.message,
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

    private companion object {
        private fun generateRequestId(): String = "req-${UUID.randomUUID().toString().take(8)}"
    }
}
