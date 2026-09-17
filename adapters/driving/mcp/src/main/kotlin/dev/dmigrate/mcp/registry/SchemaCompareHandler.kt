package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.dmigrate.cli.commands.CompareSide
import dev.dmigrate.cli.commands.ComputedExpressionDecidability
import dev.dmigrate.cli.commands.SchemaCompareSemantics
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSource
import dev.dmigrate.mcp.schema.SchemaSourceInput
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalContext

/**
 * LF-012 / LN-027 / LN-028 / LN-038: `schema_compare` per LF-012 / LN-027 / LN-028 / LN-038.
 *
 * LF-012 / LN-038 is **schemaRef-only** — inline `schema` and `connectionRef`
 * are explicitly rejected:
 * - inline schema: comparing two large inline schemas would blow past
 *   `maxNonUploadToolRequestBytes`; clients stage them via the read-
 *   only upload flow first.
 * - `connectionRef`: connection-backed compares are async and live in
 *   `schema_compare_start` (LF-012 / LN-011 / LN-017 / LN-027). The error message points there.
 *
 * The handler delegates to the existing `SchemaComparator`; no
 * fachliche logic is duplicated. The `SchemaDiff` is projected into a
 * unified `findings` list by [SchemaCompareFindings] (severity policy and
 * path schema live there) — the same [SchemaCompareOutcome] the job
 * `schema_compare_start` publishes. Both sides lose their reverse marker
 * first ([SchemaCompareSemantics.side]), as in the CLI: two reverses from
 * different dialects carry different markers, and that is no `name` change.
 *
 * A computed column's expression whose change the comparator itself could
 * not decide (see [ComputedExpressionDecidability]) is projected as its own
 * `W137`/`warning` finding, independent of `status` — the comparator folds
 * the undecidable case to equal (no false alarm), so `status` can read
 * `identical` while a `W137` finding still says the question was open.
 *
 * When findings exceed `maxInlineFindings` or the rendered envelope
 * would breach `maxToolResponseBytes`, the full diff JSON is written
 * via [ArtifactSink] and surfaced as `diffArtifactRef`; `truncated`
 * is `true` and the inline `findings` list is capped.
 */
internal class SchemaCompareHandler(
    private val resolver: SchemaSourceResolver,
    private val contentLoader: SchemaContentLoader,
    /**
     * Vergleicht links (Ist) und rechts (Soll); jede Seite traegt den Dialekt
     * aus ihrer Reverse-Markierung. Gebaut in `McpRuntimeRegistries`.
     */
    private val comparator: (CompareSide, CompareSide) -> SchemaDiff,
    private val artifactSink: ArtifactSink,
    private val limits: McpLimitsConfig,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = parseArguments(context.arguments)
        val source = side(loadSide(args.leftRef, args.format, context.principal), "left")
        val target = side(loadSide(args.rightRef, args.format, context.principal), "right")
        // Ein unentscheidbarer Berechnungsausdruck faltet der Comparator auf
        // Gleichheit (kein Fehlalarm) — dieselbe Frage, die `schema migrate`
        // mit W137 beantwortet; das Ergebnis traegt sie als eigenen Fund.
        val outcome = SchemaCompareOutcome.of(source, target, comparator)

        val allFindings = outcome.findings
        val cap = limits.maxInlineFindings
        val findingsTruncated = allFindings.size > cap
        val inlineFindings = if (findingsTruncated) allFindings.take(cap) else allFindings
        val identical = outcome.identical
        // The artefact carries the full UNCAPPED finding list (same
        // per-finding shape as inline) so an agent reading
        // `diffArtifactRef` sees every change beyond `maxInlineFindings`.
        // Richer per-column structure stays in `SchemaDiff` and would
        // require a stable wire projection — out of scope for LF-012 / LN-027 / LN-028 / LN-038.
        val inlineDiffThreshold = limits.maxToolResponseBytes / 2
        val diffArtifactRef = if (!identical) {
            val diffBytes = gson.toJson(allFindings).toByteArray(Charsets.UTF_8)
            if (diffBytes.size > inlineDiffThreshold) {
                artifactSink.writeReadOnly(
                    principal = context.principal,
                    kind = ArtifactKind.DIFF,
                    contentType = "application/json",
                    filename = "schema-diff.json",
                    content = diffBytes,
                    maxArtifactBytes = limits.maxArtifactUploadBytes,
                ).render()
            } else {
                null
            }
        } else {
            null
        }
        val sizeTruncated = diffArtifactRef != null

        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(
                        buildPayload(
                            outcome = outcome,
                            inlineFindings = inlineFindings,
                            truncated = findingsTruncated || sizeTruncated,
                            diffArtifactRef = diffArtifactRef,
                            requestId = context.requestId,
                        ),
                    ),
                    mimeType = "application/json",
                ),
            ),
        )
    }

    private fun parseArguments(raw: JsonElement?): SchemaCompareArgs {
        val obj = JsonArgs.requireObject(raw)
        val left = sideRef(obj, "left")
        val right = sideRef(obj, "right")
        val format = obj.optString("format")
        return SchemaCompareArgs(leftRef = left, rightRef = right, format = format)
    }

    private fun sideRef(args: JsonObject, side: String): String {
        val element = args.get(side)
            ?: throw ValidationErrorException(
                listOf(ValidationViolation(side, "is required")),
            )
        if (!element.isJsonObject) {
            throw ValidationErrorException(
                listOf(ValidationViolation(side, "must be an object with a schemaRef")),
            )
        }
        val sideObj = element.asJsonObject
        if (sideObj.has("connectionRef")) {
            // §5.3 / §6.6: connection-backed compares are explicitly
            // out of scope for the synchronous tool; redirect the
            // client at start of the future async path.
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "$side.connectionRef",
                        "connection-backed compare is not supported here — use schema_compare_start",
                    ),
                ),
            )
        }
        if (sideObj.has("schema")) {
            // §3.1: inline schemas are not accepted by schema_compare.
            // Stage them via read-only schema upload first.
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "$side.schema",
                        "inline schemas are not supported — provide a schemaRef instead",
                    ),
                ),
            )
        }
        return sideObj.optString("schemaRef")
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("$side.schemaRef", "is required")),
            )
    }

    /**
     * Die Seite ohne Reverse-Markierung. Traegt der Name das reservierte
     * Praefix bei unvollstaendiger Markierung, ist der Verweis ungueltig —
     * die CLI endet dort mit Exit 7.
     */
    private fun side(schema: SchemaDefinition, field: String): CompareSide = try {
        SchemaCompareSemantics.side(schema)
    } catch (failure: IllegalStateException) {
        // Die Ursache bleibt am Stack; ueber die Leitung geht nur die Verletzung.
        throw ValidationErrorException(
            listOf(
                ValidationViolation(
                    "$field.schemaRef",
                    "the schema name uses the reserved reverse prefix, but its marker set is invalid",
                ),
            ),
        ).apply { addSuppressed(failure) }
    }

    private fun loadSide(
        schemaRef: String,
        format: String?,
        principal: PrincipalContext,
    ): SchemaDefinition {
        val source = resolver.resolve(SchemaSourceInput(schemaRef = schemaRef), principal)
        // The resolver only emits Reference for schemaRef-only input;
        // a future variant slipping through would be an internal
        // invariant break, not a client error. Throw the typed
        // sentinel so the dispatch path renders a sane envelope
        // without leaking `IllegalArgumentException` across the wire.
        if (source !is SchemaSource.Reference) {
            throw InternalAgentErrorException()
        }
        return contentLoader.load(source, format = format)
    }

    private fun buildPayload(
        outcome: SchemaCompareOutcome,
        inlineFindings: List<Map<String, Any?>>,
        truncated: Boolean,
        diffArtifactRef: String?,
        requestId: String,
    ): Map<String, Any?> = buildMap {
        put("status", outcome.status)
        put("summary", outcome.summary(asArtifact = diffArtifactRef != null))
        put("findings", inlineFindings)
        put("truncated", truncated)
        if (diffArtifactRef != null) put("diffArtifactRef", diffArtifactRef)
        put("executionMeta", mapOf("requestId" to requestId))
    }

    private data class SchemaCompareArgs(
        val leftRef: String,
        val rightRef: String,
        val format: String?,
    )
}
