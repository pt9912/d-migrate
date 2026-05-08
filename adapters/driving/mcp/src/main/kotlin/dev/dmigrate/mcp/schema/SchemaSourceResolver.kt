package dev.dmigrate.mcp.schema

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.TenantScopeDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantScopeChecker
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaStore

/**
 * Resolves a `tools/call` payload's schema source per
 * `ImpPlan-0.9.6-C.md` §5.2 + §6.3. Shared by `schema_validate`
 * (AP 6.4), `schema_generate` (AP 6.5), and `schema_compare` (AP 6.6,
 * once per `left`/`right`).
 *
 * Two valid sources, never both:
 * - inline `schema` (small JSON object, max [McpLimitsConfig.maxInlineSchemaBytes])
 * - `schemaRef` (tenant-scoped `dmigrate://.../schemas/{id}` URI)
 *
 * No connection-backed source is accepted in Phase C — `connectionRef`
 * stays out of every schema-using tool until `schema_compare_start`
 * lands in Phase E.
 */
data class SchemaSourceInput(
    val schema: JsonElement? = null,
    val schemaRef: String? = null,
)

sealed interface SchemaSource {

    /**
     * Inline schema embedded directly in the `tools/call` payload.
     * [serialisedJson] is the UTF-8 string the resolver already
     * computed for the size check; consumers (`SchemaContentLoader`)
     * feed it back into the codec without re-serialising the
     * `JsonObject` tree. [byteSize] is its UTF-8 byte length.
     */
    data class Inline(
        val schema: JsonObject,
        val serialisedJson: String,
        val byteSize: Int,
    ) : SchemaSource

    /**
     * `schemaRef` resolved against the tenant-scoped [SchemaStore].
     * The entry's `artifactRef` is the pointer to the schema bytes;
     * loading the actual `SchemaDefinition` from those bytes belongs
     * to the consuming tool (schema_validate / generate / compare).
     */
    data class Reference(val entry: SchemaIndexEntry) : SchemaSource
}

class SchemaSourceResolver(
    private val schemaStore: SchemaStore,
    private val limits: McpLimitsConfig,
) {

    fun resolve(input: SchemaSourceInput, principal: PrincipalContext): SchemaSource {
        val haveInline = input.schema != null && !input.schema.isJsonNull
        val haveRef = !input.schemaRef.isNullOrBlank()
        if (haveInline == haveRef) {
            throw ValidationErrorException(
                listOf(ValidationViolation(SOURCE_FIELD, EXACTLY_ONE_REASON)),
            )
        }
        return if (haveInline) resolveInline(input.schema!!) else resolveReference(input.schemaRef!!, principal)
    }

    private fun resolveInline(element: JsonElement): SchemaSource.Inline {
        if (!element.isJsonObject) {
            throw ValidationErrorException(
                listOf(ValidationViolation(SCHEMA_FIELD, "must be a JSON object")),
            )
        }
        val serialised = element.toString()
        val byteSize = serialised.toByteArray(Charsets.UTF_8).size
        if (byteSize > limits.maxInlineSchemaBytes) {
            throw PayloadTooLargeException(
                actualBytes = byteSize.toLong(),
                maxBytes = limits.maxInlineSchemaBytes.toLong(),
            )
        }
        return SchemaSource.Inline(
            schema = element.asJsonObject,
            serialisedJson = serialised,
            byteSize = byteSize,
        )
    }

    private fun resolveReference(raw: String, principal: PrincipalContext): SchemaSource.Reference {
        val uri = parseSchemaRef(raw)
        // §5.6 no-oracle: tenant scope must be enforced BEFORE the
        // store lookup. Otherwise a differential timing channel could
        // distinguish "foreign tenant, schema exists" from "foreign
        // tenant, schema absent" — both must be indistinguishable
        // from the client's view. Same predicate as JobRecord and
        // ArtifactRecord: tenant readability is `effectiveTenantId`-
        // only across read-only Phase B/C handlers.
        if (!TenantScopeChecker.isInScope(principal, uri.tenantId)) {
            throw TenantScopeDeniedException(uri.tenantId)
        }
        val entry = schemaStore.findById(uri.tenantId, uri.id)
            ?: throw ResourceNotFoundException(uri)
        // AP 6.16 defense-in-depth: a misbehaving SchemaStore impl
        // (sharded driver, cache layer, future JDBC adapter) could
        // theoretically return an entry whose tenantId, schemaId or
        // resourceUri does not match the lookup key. Surface that
        // as RESOURCE_NOT_FOUND (no-oracle) rather than trusting the
        // store contract — the caller already saw "this tenant +
        // this id existed in scope", so revealing nothing more is
        // safe.
        if (!entryMatches(entry, uri)) {
            throw ResourceNotFoundException(uri)
        }
        return SchemaSource.Reference(entry)
    }

    private fun entryMatches(entry: SchemaIndexEntry, uri: ServerResourceUri): Boolean {
        if (entry.tenantId != uri.tenantId) return false
        if (entry.schemaId != uri.id) return false
        val ref = entry.resourceUri
        return ref.tenantId == uri.tenantId && ref.id == uri.id && ref.kind == uri.kind
    }

    private fun parseSchemaRef(raw: String): ServerResourceUri {
        val uri = when (val parsed = ServerResourceUri.parse(raw)) {
            is ResourceUriParseResult.Valid -> parsed.uri
            is ResourceUriParseResult.Invalid -> throw ValidationErrorException(
                listOf(ValidationViolation(SCHEMA_REF_FIELD, "invalid URI: ${parsed.reason}")),
            )
        }
        if (uri.kind != ResourceKind.SCHEMAS) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        SCHEMA_REF_FIELD,
                        "expected schemas resource, got ${uri.kind.pathSegment}",
                    ),
                ),
            )
        }
        return uri
    }

    private companion object {
        const val SOURCE_FIELD = "source"
        const val SCHEMA_FIELD = "schema"
        const val SCHEMA_REF_FIELD = "schemaRef"
        const val EXACTLY_ONE_REASON = "exactly one of 'schema' or 'schemaRef' is required"
    }
}
