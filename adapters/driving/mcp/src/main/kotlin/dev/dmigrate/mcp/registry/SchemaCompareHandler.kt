package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaFindingSeverity
import dev.dmigrate.mcp.schema.SchemaSource
import dev.dmigrate.mcp.schema.SchemaSourceInput
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalContext
import java.util.UUID

/**
 * AP 6.6: `schema_compare` per `ImpPlan-0.9.6-C.md` §5.3 + §6.6.
 *
 * Phase C is **schemaRef-only** — inline `schema` and `connectionRef`
 * are explicitly rejected:
 * - inline schema: comparing two large inline schemas would blow past
 *   `maxNonUploadToolRequestBytes`; clients stage them via the read-
 *   only upload flow first.
 * - `connectionRef`: connection-backed compares are async and live in
 *   `schema_compare_start` (Phase E). The error message points there.
 *
 * The handler delegates to the existing [SchemaComparator]; no
 * fachliche logic is duplicated. The `SchemaDiff` is projected into a
 * unified `findings` list:
 * - added objects → severity `info`  (additive, generally compatible)
 * - removed objects → severity `warning` (potentially breaking)
 * - changed objects → severity `warning`
 * - metadata change → severity `info`
 *
 * When findings exceed `maxInlineFindings` or the rendered envelope
 * would breach `maxToolResponseBytes`, the full diff JSON is written
 * via [ArtifactSink] and surfaced as `diffArtifactRef`; `truncated`
 * is `true` and the inline `findings` list is capped.
 */
internal class SchemaCompareHandler(
    private val resolver: SchemaSourceResolver,
    private val contentLoader: SchemaContentLoader,
    private val comparator: SchemaComparator,
    private val artifactSink: ArtifactSink,
    private val limits: McpLimitsConfig,
    private val requestIdProvider: () -> String = ::generateRequestId,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = parseArguments(context.arguments)
        val leftSchema = loadSide(args.leftRef, args.format, context.principal)
        val rightSchema = loadSide(args.rightRef, args.format, context.principal)
        val diff = comparator.compare(leftSchema, rightSchema)

        val allFindings = projectFindings(diff)
        val cap = limits.maxInlineFindings
        val findingsTruncated = allFindings.size > cap
        val inlineFindings = if (findingsTruncated) allFindings.take(cap) else allFindings
        val identical = diff.isEmpty()
        // The artefact carries the full UNCAPPED finding list (same
        // per-finding shape as inline) so an agent reading
        // `diffArtifactRef` sees every change beyond `maxInlineFindings`.
        // Richer per-column structure stays in `SchemaDiff` and would
        // require a stable wire projection — out of scope for AP 6.6.
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
                            identical = identical,
                            inlineFindings = inlineFindings,
                            totalFindings = allFindings.size,
                            truncated = findingsTruncated || sizeTruncated,
                            diffArtifactRef = diffArtifactRef,
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

    private fun loadSide(
        schemaRef: String,
        format: String?,
        principal: PrincipalContext,
    ): dev.dmigrate.core.model.SchemaDefinition {
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
        identical: Boolean,
        inlineFindings: List<Map<String, Any?>>,
        totalFindings: Int,
        truncated: Boolean,
        diffArtifactRef: String?,
    ): Map<String, Any?> = buildMap {
        put("status", if (identical) "identical" else "different")
        put("summary", summary(identical, totalFindings, diffArtifactRef != null))
        put("findings", inlineFindings)
        put("truncated", truncated)
        if (diffArtifactRef != null) put("diffArtifactRef", diffArtifactRef)
        put("executionMeta", mapOf("requestId" to requestIdProvider()))
    }

    private fun summary(identical: Boolean, totalFindings: Int, asArtifact: Boolean): String = when {
        identical -> "Schemas are identical."
        asArtifact -> "Schemas differ ($totalFindings change(s)); full diff returned as artefact."
        else -> "Schemas differ ($totalFindings change(s))."
    }

    private fun projectFindings(diff: SchemaDiff): List<Map<String, Any?>> = buildList {
        diff.schemaMetadata?.let { meta ->
            meta.name?.let {
                add(
                    finding(
                        SchemaFindingSeverity.WARNING, "SCHEMA_NAME_CHANGED", "name",
                        "schema name changed from '${it.before}' to '${it.after}'",
                        beforeAfter(it.before, it.after),
                    ),
                )
            }
            meta.version?.let {
                add(
                    finding(
                        SchemaFindingSeverity.INFO, "SCHEMA_VERSION_CHANGED", "version",
                        "schema version changed from '${it.before}' to '${it.after}'",
                        beforeAfter(it.before, it.after),
                    ),
                )
            }
        }
        addAll(diff.tablesAdded.map { added("TABLE_ADDED", "tables.${it.name}") })
        addAll(diff.tablesRemoved.map { removed("TABLE_REMOVED", "tables.${it.name}") })
        diff.tablesChanged.forEach { addAll(projectTableDiff(it)) }
        addAll(diff.viewsAdded.map { added("VIEW_ADDED", "views.${it.name}") })
        addAll(diff.viewsRemoved.map { removed("VIEW_REMOVED", "views.${it.name}") })
        addAll(diff.viewsChanged.map { changed("VIEW_CHANGED", "views.${it.name}") })
        addAll(diff.sequencesAdded.map { added("SEQUENCE_ADDED", "sequences.${it.name}") })
        addAll(diff.sequencesRemoved.map { removed("SEQUENCE_REMOVED", "sequences.${it.name}") })
        addAll(diff.sequencesChanged.map { changed("SEQUENCE_CHANGED", "sequences.${it.name}") })
        addAll(diff.customTypesAdded.map { added("CUSTOM_TYPE_ADDED", "custom_types.${it.name}") })
        addAll(diff.customTypesRemoved.map { removed("CUSTOM_TYPE_REMOVED", "custom_types.${it.name}") })
        addAll(diff.customTypesChanged.map { changed("CUSTOM_TYPE_CHANGED", "custom_types.${it.name}") })
        addAll(diff.functionsAdded.map { added("FUNCTION_ADDED", "functions.${it.name}") })
        addAll(diff.functionsRemoved.map { removed("FUNCTION_REMOVED", "functions.${it.name}") })
        addAll(diff.functionsChanged.map { changed("FUNCTION_CHANGED", "functions.${it.name}") })
        addAll(diff.proceduresAdded.map { added("PROCEDURE_ADDED", "procedures.${it.name}") })
        addAll(diff.proceduresRemoved.map { removed("PROCEDURE_REMOVED", "procedures.${it.name}") })
        addAll(diff.proceduresChanged.map { changed("PROCEDURE_CHANGED", "procedures.${it.name}") })
        addAll(diff.triggersAdded.map { added("TRIGGER_ADDED", "triggers.${it.name}") })
        addAll(diff.triggersRemoved.map { removed("TRIGGER_REMOVED", "triggers.${it.name}") })
        addAll(diff.triggersChanged.map { changed("TRIGGER_CHANGED", "triggers.${it.name}") })
    }

    /**
     * Builds a finding record. `details` is the AP-6.13 machine-
     * readable supplement to `message`; both pass through
     * [SecretScrubber] before serialisation so connection URLs,
     * bearer tokens, and approval-token literals can't leak via the
     * wire. `message` stays human-readable per the §6.13
     * backward-compat rule.
     */
    private fun finding(
        severity: String,
        code: String,
        path: String,
        message: String,
        details: Map<String, String>? = null,
    ): Map<String, Any?> = buildMap {
        put("severity", severity)
        put("code", code)
        put("path", path)
        put("message", SecretScrubber.scrub(message))
        if (!details.isNullOrEmpty()) {
            put("details", details.mapValues { (_, v) -> SecretScrubber.scrub(v) })
        }
    }

    /** Standard `{ before, after }` shape for property-level changes. */
    private fun beforeAfter(before: Any?, after: Any?): Map<String, String> = mapOf(
        "before" to before.toString(),
        "after" to after.toString(),
    )

    // Additive changes are non-breaking by default — surface as info
    // so clients can filter the noise out of the warning channel.
    private fun added(code: String, path: String): Map<String, Any?> =
        finding(SchemaFindingSeverity.INFO, code, path, "$path was added")

    // Removed/changed objects are potentially breaking; clients
    // typically gate deploys on the warning bucket.
    private fun removed(code: String, path: String): Map<String, Any?> =
        finding(SchemaFindingSeverity.WARNING, code, path, "$path was removed")

    private fun changed(code: String, path: String): Map<String, Any?> =
        finding(SchemaFindingSeverity.WARNING, code, path, "$path changed")

    /**
     * Walks a [TableDiff] into one finding per change instead of a
     * single coarse `TABLE_CHANGED` entry. Severity policy:
     * - column added → info; column removed → error.
     * - column type change → error (incompatible read).
     * - `required` flipped to true → error (existing rows may
     *   violate); flipped to false → info (relaxation).
     * - `unique` tightened → warning (existing dupes block); relaxed
     *   → info.
     * - `default` / `references` change → warning (semantic drift).
     * - primary key change → error (identity contract change).
     * - index added → info; removed/changed → warning.
     * - constraint added/changed → warning; removed → info.
     * - metadata change → info.
     *
     * Codes share the `TABLE_…` prefix and a dotted path
     * (`tables.t1.columns.c1`) so clients can filter or roll-up
     * findings without parsing free-form messages.
     */
    private fun projectTableDiff(diff: TableDiff): List<Map<String, Any?>> = buildList {
        val tablePath = "tables.${diff.name}"
        diff.columnsAdded.forEach { (col, _) ->
            add(added("TABLE_COLUMN_ADDED", "$tablePath.columns.$col"))
        }
        diff.columnsRemoved.forEach { (col, _) ->
            add(
                finding(
                    SchemaFindingSeverity.ERROR,
                    "TABLE_COLUMN_REMOVED",
                    "$tablePath.columns.$col",
                    "$tablePath.columns.$col was removed",
                ),
            )
        }
        diff.columnsChanged.forEach { addAll(projectColumnDiff(tablePath, it)) }
        diff.primaryKey?.let {
            add(
                finding(
                    SchemaFindingSeverity.ERROR,
                    "TABLE_PRIMARY_KEY_CHANGED",
                    "$tablePath.primary_key",
                    "primary key changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
        diff.indicesAdded.forEach {
            add(added("TABLE_INDEX_ADDED", "$tablePath.indices.${it.name ?: it.columns.joinToString(",")}"))
        }
        diff.indicesRemoved.forEach {
            add(removed("TABLE_INDEX_REMOVED", "$tablePath.indices.${it.name ?: it.columns.joinToString(",")}"))
        }
        diff.indicesChanged.forEach {
            add(
                changed(
                    "TABLE_INDEX_CHANGED",
                    "$tablePath.indices.${it.before.name ?: it.before.columns.joinToString(",")}",
                ),
            )
        }
        diff.constraintsAdded.forEach {
            // Tightening: a new constraint may reject pre-existing
            // rows; surface as warning, not info.
            add(changed("TABLE_CONSTRAINT_ADDED", "$tablePath.constraints.${it.name}"))
        }
        diff.constraintsRemoved.forEach {
            add(
                finding(
                    SchemaFindingSeverity.INFO,
                    "TABLE_CONSTRAINT_REMOVED",
                    "$tablePath.constraints.${it.name}",
                    "constraint ${it.name} was removed",
                ),
            )
        }
        diff.constraintsChanged.forEach {
            add(changed("TABLE_CONSTRAINT_CHANGED", "$tablePath.constraints.${it.before.name}"))
        }
        diff.metadata?.let {
            add(
                finding(
                    SchemaFindingSeverity.INFO,
                    "TABLE_METADATA_CHANGED",
                    "$tablePath.metadata",
                    "table metadata changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
    }

    private fun projectColumnDiff(tablePath: String, diff: ColumnDiff): List<Map<String, Any?>> = buildList {
        val colPath = "$tablePath.columns.${diff.name}"
        diff.type?.let {
            add(
                finding(
                    SchemaFindingSeverity.ERROR,
                    "TABLE_COLUMN_TYPE_CHANGED",
                    "$colPath.type",
                    "type changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
        diff.required?.let { add(projectRequired(colPath, it)) }
        diff.unique?.let { add(projectUnique(colPath, it)) }
        diff.default?.let {
            add(
                finding(
                    SchemaFindingSeverity.WARNING,
                    "TABLE_COLUMN_DEFAULT_CHANGED",
                    "$colPath.default",
                    "default changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
        diff.references?.let {
            add(
                finding(
                    SchemaFindingSeverity.WARNING,
                    "TABLE_COLUMN_REFERENCES_CHANGED",
                    "$colPath.references",
                    "foreign-key references changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
    }

    private fun projectRequired(colPath: String, change: ValueChange<Boolean>): Map<String, Any?> =
        if (!change.before && change.after) {
            // false → true: pre-existing rows may carry NULL; tightening is breaking.
            finding(
                SchemaFindingSeverity.ERROR,
                "TABLE_COLUMN_REQUIRED_TIGHTENED",
                "$colPath.required",
                "column became required",
                beforeAfter(false, true),
            )
        } else {
            finding(
                SchemaFindingSeverity.INFO,
                "TABLE_COLUMN_REQUIRED_RELAXED",
                "$colPath.required",
                "column is no longer required",
                beforeAfter(true, false),
            )
        }

    private fun projectUnique(colPath: String, change: ValueChange<Boolean>): Map<String, Any?> =
        if (!change.before && change.after) {
            // false → true: pre-existing duplicates would block the
            // unique index — warning rather than error so clients can
            // still ship after a data audit.
            finding(
                SchemaFindingSeverity.WARNING,
                "TABLE_COLUMN_UNIQUE_TIGHTENED",
                "$colPath.unique",
                "column became unique",
                beforeAfter(false, true),
            )
        } else {
            finding(
                SchemaFindingSeverity.INFO,
                "TABLE_COLUMN_UNIQUE_RELAXED",
                "$colPath.unique",
                "column is no longer unique",
                beforeAfter(true, false),
            )
        }

    private data class SchemaCompareArgs(
        val leftRef: String,
        val rightRef: String,
        val format: String?,
    )

    private companion object {
        fun generateRequestId(): String = "req-${UUID.randomUUID().toString().take(8)}"
    }
}
