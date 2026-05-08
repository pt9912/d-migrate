package dev.dmigrate.server.core.resource

import dev.dmigrate.server.core.principal.TenantId

/**
 * Sealed ADT for every URI shape Phase D's `resources/read` and the
 * Discovery dispatchers must accept per `ImpPlan-0.9.6-D.md` §10.1.
 *
 * Three variants:
 *  - [TenantResourceUri] — the existing tenant-scoped record URI:
 *    `dmigrate://tenants/<tenantId>/<kind>/<id>`. Used for jobs,
 *    artefacts, schemas, profiles, diffs, connections, and the
 *    upload-sessions kind (the upload-sessions kind stays parseable
 *    here but is classified as a Phase-D-blocked resource at the
 *    resolver layer in AP D2; not listed by `resources/list` /
 *    `resources/templates/list`).
 *  - [ArtifactChunkResourceUri] — the four-segment chunked-read URI
 *    `dmigrate://tenants/<tenantId>/artifacts/<artifactId>/chunks/<chunkId>`.
 *    Phase D's `artifact_chunk_get` resolves these directly so a
 *    `nextChunkUri` from a previous response can round-trip without
 *    string-handling at the call site.
 *  - [GlobalCapabilitiesResourceUri] — the tenantless
 *    `dmigrate://capabilities` URI. Singleton because there is
 *    exactly one capabilities document per server instance.
 *
 * The legacy [ServerResourceUri] data class continues to exist as
 * the carrier shape for `TenantResourceUri`; AP D2 migrates the
 * Phase-B/-C call sites onto this ADT through the resolver
 * dispatcher. AP D1 is the additive type-only delivery.
 *
 * Parsing returns a [McpResourceUriParseResult]; reasons are
 * enumerated so the resolver can map every grammar failure into a
 * single `VALIDATION_ERROR` code without leaking which segment
 * tripped the parser.
 */
sealed interface McpResourceUri {

    /** Renders the URI back to its canonical wire form. */
    fun render(): String

    companion object {
        const val SCHEME: String = "dmigrate"

        /** `dmigrate://capabilities` — the only tenantless URI Phase D supports. */
        const val CAPABILITIES_URI: String = "dmigrate://capabilities"

        private const val TENANT_PREFIX: String = "dmigrate://tenants/"
        private const val CHUNKS_SEGMENT: String = "chunks"

        /**
         * Allowed segment alphabet — letters, digits, underscore,
         * hyphen. Empty / dotted / whitespace segments are rejected.
         * Matches the regex used by [ServerResourceUri] so the
         * legacy parser and the new ADT agree on what a valid id
         * looks like.
         */
        private val SEGMENT_PATTERN: Regex = Regex("^[A-Za-z0-9_\\-]+$")

        fun parse(input: String): McpResourceUriParseResult {
            // Tenantless: only `dmigrate://capabilities` is allowed
            // in Phase D. Every other tenantless URI is invalid.
            if (input == CAPABILITIES_URI) {
                return McpResourceUriParseResult.Valid(GlobalCapabilitiesResourceUri)
            }
            if (!input.startsWith(TENANT_PREFIX)) {
                return McpResourceUriParseResult.Invalid("missing scheme prefix")
            }
            val rest = input.removePrefix(TENANT_PREFIX)
            val parts = rest.split('/')
            return when (parts.size) {
                TENANT_RESOURCE_SEGMENTS -> parseTenantResource(parts)
                ARTIFACT_CHUNK_SEGMENTS -> parseArtifactChunk(parts)
                else -> McpResourceUriParseResult.Invalid(
                    "expected tenants/{tenantId}/{kind}/{id} or " +
                        "tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}",
                )
            }
        }

        private fun parseTenantResource(parts: List<String>): McpResourceUriParseResult {
            val (tenantSegment, kindSegment, idSegment) = parts
            if (!SEGMENT_PATTERN.matches(tenantSegment)) {
                return McpResourceUriParseResult.Invalid("invalid tenantId segment")
            }
            if (!SEGMENT_PATTERN.matches(idSegment)) {
                return McpResourceUriParseResult.Invalid("invalid id segment")
            }
            val kind = ResourceKind.fromPathSegment(kindSegment)
                ?: return McpResourceUriParseResult.Invalid("unknown resource kind: $kindSegment")
            return McpResourceUriParseResult.Valid(
                TenantResourceUri(TenantId(tenantSegment), kind, idSegment),
            )
        }

        private fun parseArtifactChunk(parts: List<String>): McpResourceUriParseResult {
            // parts: [tenantId, "artifacts", artifactId, "chunks", chunkId]
            val tenantSegment = parts[0]
            val kindSegment = parts[1]
            val artifactSegment = parts[2]
            val chunksSegment = parts[3]
            val chunkSegment = parts[4]
            if (kindSegment != ResourceKind.ARTIFACTS.pathSegment) {
                return McpResourceUriParseResult.Invalid(
                    "five-segment URIs only allowed under the artifacts/.../chunks/... shape",
                )
            }
            if (chunksSegment != CHUNKS_SEGMENT) {
                return McpResourceUriParseResult.Invalid("expected `chunks` segment after artifactId")
            }
            if (!SEGMENT_PATTERN.matches(tenantSegment)) {
                return McpResourceUriParseResult.Invalid("invalid tenantId segment")
            }
            if (!SEGMENT_PATTERN.matches(artifactSegment)) {
                return McpResourceUriParseResult.Invalid("invalid artifactId segment")
            }
            if (!SEGMENT_PATTERN.matches(chunkSegment)) {
                return McpResourceUriParseResult.Invalid("invalid chunkId segment")
            }
            return McpResourceUriParseResult.Valid(
                ArtifactChunkResourceUri(
                    tenantId = TenantId(tenantSegment),
                    artifactId = artifactSegment,
                    chunkId = chunkSegment,
                ),
            )
        }

        private const val TENANT_RESOURCE_SEGMENTS: Int = 3
        private const val ARTIFACT_CHUNK_SEGMENTS: Int = 5
    }
}

/**
 * Tenant-scoped resource URI for jobs, artefacts, schemas, profiles,
 * diffs, connections and (parseable but not listed in Phase D)
 * upload-sessions.
 *
 * Mirrors the existing [ServerResourceUri] data class on purpose —
 * AP D2 will migrate Phase-B/-C call sites onto this ADT. Until
 * then both types coexist; conversion goes through [toLegacy] /
 * [fromLegacy].
 */
data class TenantResourceUri(
    val tenantId: TenantId,
    val kind: ResourceKind,
    val id: String,
) : McpResourceUri {

    override fun render(): String =
        "${McpResourceUri.SCHEME}://tenants/${tenantId.value}/${kind.pathSegment}/$id"

    fun toLegacy(): ServerResourceUri = ServerResourceUri(tenantId, kind, id)

    companion object {
        fun fromLegacy(legacy: ServerResourceUri): TenantResourceUri =
            TenantResourceUri(legacy.tenantId, legacy.kind, legacy.id)
    }
}

/**
 * Chunked-read URI: `dmigrate://tenants/<tenantId>/artifacts/<artifactId>/chunks/<chunkId>`.
 * Emitted as `nextChunkUri` from `artifact_chunk_get` and accepted
 * by `resources/read` for the chunk-resolver path.
 */
data class ArtifactChunkResourceUri(
    val tenantId: TenantId,
    val artifactId: String,
    val chunkId: String,
) : McpResourceUri {

    override fun render(): String =
        "${McpResourceUri.SCHEME}://tenants/${tenantId.value}/" +
            "${ResourceKind.ARTIFACTS.pathSegment}/$artifactId/chunks/$chunkId"
}

/**
 * `dmigrate://capabilities` — the singular, tenantless capabilities
 * resource. Singleton because the capabilities document is unique
 * per running server instance.
 */
object GlobalCapabilitiesResourceUri : McpResourceUri {
    override fun render(): String = McpResourceUri.CAPABILITIES_URI
}

sealed interface McpResourceUriParseResult {
    data class Valid(val uri: McpResourceUri) : McpResourceUriParseResult
    data class Invalid(val reason: String) : McpResourceUriParseResult
}
