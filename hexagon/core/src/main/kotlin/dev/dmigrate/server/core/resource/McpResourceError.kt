package dev.dmigrate.server.core.resource

import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId

/**
 * Phase-D resource-error contract per `ImpPlan-0.9.6-D.md` §4.2 +
 * §10.2. The four typed failure classes downstream resolvers
 * (`resources/read`, `artifact_chunk_get`, `job_status_get`, the
 * `*_list` tools) must surface uniformly. Each carries the stable
 * `dmigrateCode` string Phase D pins as `error.data.dmigrateCode`
 * on the JSON-RPC envelope (the JSON-RPC renderer lives in the MCP
 * adapter; AP D7 wires it in).
 *
 * The error precedence is golden-tested in [ResourceErrorPrecedence]
 * so individual handlers cannot drift on the order they evaluate
 * URI shape, tenant scope, blocked kinds and record visibility.
 */
sealed interface McpResourceError {

    /** Stable wire code surfaced as `error.data.dmigrateCode`. */
    val dmigrateCode: String

    /**
     * Human-readable diagnostic for operators. MUST NOT carry the
     * raw URI or tenant id of a foreign resource — the no-oracle
     * assertion lives one level up in §5.6 and §6.24's E6 tests.
     */
    val message: String

    /**
     * Stage 1: URI / arguments did not parse, or the request
     * carries a Phase-D-blocked resource kind we never look up.
     * Phase-D collapses both into one `VALIDATION_ERROR` so the
     * caller cannot probe the difference.
     */
    data class ValidationError(override val message: String) : McpResourceError {
        override val dmigrateCode: String = CODE_VALIDATION_ERROR
    }

    /**
     * Stage 2: the URI parsed cleanly but its tenant lies outside
     * [PrincipalContext.allowedTenantIds]. Per §4.2 the request
     * fails BEFORE any cursor is decoded or store is consulted —
     * existence of the addressed resource is never confirmed.
     */
    data class TenantScopeDenied(val requestedTenant: TenantId) : McpResourceError {
        override val dmigrateCode: String = CODE_TENANT_SCOPE_DENIED
        override val message: String = "tenant scope denied for requested resource"
    }

    /**
     * Stage 4: the URI parsed, the tenant is allowed, and the
     * resolver did the lookup — but no record exists, the record
     * is expired, or the principal cannot see it. Per §5.6 these
     * three sub-cases collapse to ONE wire shape so an attacker
     * cannot probe existence.
     */
    object ResourceNotFound : McpResourceError {
        override val dmigrateCode: String = CODE_RESOURCE_NOT_FOUND
        override val message: String = "Resource not found"
    }

    companion object {
        const val CODE_VALIDATION_ERROR: String = "VALIDATION_ERROR"
        const val CODE_TENANT_SCOPE_DENIED: String = "TENANT_SCOPE_DENIED"
        const val CODE_RESOURCE_NOT_FOUND: String = "RESOURCE_NOT_FOUND"
    }
}

/**
 * Outcome of [ResourceErrorPrecedence.classify]: either a parsed
 * [McpResourceUri] the resolver dispatcher can hand off, or a
 * typed [McpResourceError] the JSON-RPC layer must render with
 * `error.data.dmigrateCode`.
 */
sealed interface ResourceClassification {
    data class Resolved(val uri: McpResourceUri) : ResourceClassification
    data class Failed(val error: McpResourceError) : ResourceClassification
}

/**
 * Centralised per-§4.2 precedence chain that every Phase-D
 * dispatcher MUST go through. Order — load-bearing for the
 * no-oracle property:
 *
 *   1. URI grammar parse — `VALIDATION_ERROR` on shape failures.
 *   2. Tenant-scope: URI's tenant must be in
 *      [PrincipalContext.allowedTenantIds]; failure surfaces as
 *      `TENANT_SCOPE_DENIED` BEFORE any blocked-kind / store check
 *      so a foreign-tenant URI never hits a resolver.
 *   3. Phase-D-blocked kind: `UPLOAD_SESSIONS` URIs in an allowed
 *      tenant collapse to `VALIDATION_ERROR` without store lookup
 *      so an attacker cannot probe upload-session ids.
 *   4. Anything else: hand the parsed URI off to the resolver
 *      dispatcher; record-not-found / not-visible flips at THAT
 *      layer to `RESOURCE_NOT_FOUND`. AP D2 stops short of
 *      step 4 — the resolver dispatcher lands with the concrete
 *      handlers in AP D6/D7/D9.
 *
 * The chain is pure; cursor decoding (which is also bound at
 * step 4 per §4.2) plugs in there with the resolver dispatcher.
 */
object ResourceErrorPrecedence {

    /**
     * Phase-D-blocked resource kinds. Currently `UPLOAD_SESSIONS`
     * only — they parse for the legacy upload contract but are
     * not in the Phase-D readable resource universe.
     */
    private val PHASE_D_BLOCKED_KINDS: Set<ResourceKind> = setOf(ResourceKind.UPLOAD_SESSIONS)

    /**
     * Classifies [rawUri] against the [principal] per §4.2.
     * Returns either a [ResourceClassification.Resolved] carrying
     * the parsed URI for downstream resolver dispatch, or a
     * [ResourceClassification.Failed] with the typed error.
     */
    fun classify(rawUri: String, principal: PrincipalContext): ResourceClassification {
        // Stage 1: parse
        val parsed = when (val r = McpResourceUri.parse(rawUri)) {
            is McpResourceUriParseResult.Valid -> r.uri
            is McpResourceUriParseResult.Invalid -> return ResourceClassification.Failed(
                McpResourceError.ValidationError(r.reason),
            )
        }

        // Stage 2: tenant-scope (only for URIs that carry a tenant)
        val tenant = parsed.tenantOrNull()
        if (tenant != null && tenant !in principal.allowedTenantIds) {
            return ResourceClassification.Failed(
                McpResourceError.TenantScopeDenied(tenant),
            )
        }

        // Stage 3: Phase-D-blocked kind
        val blockedKind = parsed.blockedKindOrNull()
        if (blockedKind != null && blockedKind in PHASE_D_BLOCKED_KINDS) {
            return ResourceClassification.Failed(
                McpResourceError.ValidationError(
                    "resource kind '${blockedKind.pathSegment}' is not readable in Phase D",
                ),
            )
        }

        return ResourceClassification.Resolved(parsed)
    }

    private fun McpResourceUri.tenantOrNull(): TenantId? = when (this) {
        is TenantResourceUri -> tenantId
        is ArtifactChunkResourceUri -> tenantId
        GlobalCapabilitiesResourceUri -> null
    }

    private fun McpResourceUri.blockedKindOrNull(): ResourceKind? = when (this) {
        is TenantResourceUri -> kind
        // ArtifactChunk + GlobalCapabilities are not kind-blocked;
        // they have their own dispatch path.
        is ArtifactChunkResourceUri -> null
        GlobalCapabilitiesResourceUri -> null
    }
}
