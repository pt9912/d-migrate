package dev.dmigrate.mcp.registry

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.dmigrate.server.application.error.TenantScopeDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Phase-D §10.6 shared helpers for the five `*_list` discovery
 * handlers. Pulled into a dedicated object so the per-tool
 * handlers stay thin — they parse their resource-specific
 * filters, hand them off to the store, and project the results.
 *
 * Tenant + page-size resolution must produce the **same** wire
 * outcome regardless of which list tool is in scope: §6.1 forbids
 * a `job_list` carrying a tenant-scope error from differing in
 * shape from an `artifact_list` carrying the same one.
 */
internal object ListToolHelpers {

    /**
     * Default page size when `pageSize` is omitted. Modest on
     * purpose — multi-page navigation lands with the cursor
     * wiring in AP D8.
     */
    const val DEFAULT_PAGE_SIZE: Int = 50

    /**
     * Hard upper bound on `pageSize`. The wire schema enforces
     * `minimum=1`; this constant is the matching maximum.
     */
    const val MAX_PAGE_SIZE: Int = 200

    /**
     * Resolves the addressed tenant per §6.1:
     *   - missing `tenantId` → [PrincipalContext.effectiveTenantId]
     *   - explicit `tenantId` → must be in
     *     [PrincipalContext.allowedTenantIds]; otherwise throws
     *     [TenantScopeDeniedException]
     *
     * Cross-tenant fanout is NOT supported — exactly one tenant
     * per request, even for principals with multiple allowed
     * tenants.
     */
    fun resolveTenant(args: JsonObject?, principal: PrincipalContext): TenantId {
        val explicit = args?.optStringField(FIELD_TENANT_ID)
        if (explicit == null || explicit.isBlank()) {
            return principal.effectiveTenantId
        }
        val candidate = TenantId(explicit)
        if (candidate !in principal.allowedTenantIds) {
            throw TenantScopeDeniedException(candidate)
        }
        return candidate
    }

    /**
     * Resolves the requested `pageSize` against
     * [DEFAULT_PAGE_SIZE] / [MAX_PAGE_SIZE]. The wire schema
     * already pins `minimum=1`, but this helper rejects values
     * over the cap with a typed `VALIDATION_ERROR` so a client
     * cannot ask for arbitrary-sized pages.
     */
    fun resolvePageSize(args: JsonObject?): Int {
        val raw = args?.optIntField(FIELD_PAGE_SIZE) ?: return DEFAULT_PAGE_SIZE
        if (raw < 1) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        FIELD_PAGE_SIZE,
                        "pageSize must be >= 1 (got $raw)",
                    ),
                ),
            )
        }
        if (raw > MAX_PAGE_SIZE) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        FIELD_PAGE_SIZE,
                        "pageSize must be <= $MAX_PAGE_SIZE (got $raw)",
                    ),
                ),
            )
        }
        return raw
    }

    /**
     * Parses an optional `Instant` argument under [field]. Returns
     * `null` when absent. Surfaces unparseable strings as a typed
     * `VALIDATION_ERROR`.
     */
    fun resolveInstant(args: JsonObject?, field: String): Instant? {
        val raw = args?.optStringField(field) ?: return null
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        field,
                        "expected ISO-8601 instant (e.g. 2026-05-04T10:00:00Z), got '$raw'",
                    ),
                ),
            )
        }
    }

    /**
     * Reads an optional string filter argument. Returns `null`
     * when absent or blank.
     */
    fun resolveString(args: JsonObject?, field: String): String? =
        args?.optStringField(field)?.takeIf { it.isNotBlank() }

    private const val FIELD_TENANT_ID: String = "tenantId"
    private const val FIELD_PAGE_SIZE: String = "pageSize"

    private fun JsonObject.optStringField(name: String): String? =
        if (has(name) && get(name).isJsonPrimitive && get(name).asJsonPrimitive.isString) {
            get(name).asString
        } else {
            null
        }

    private fun JsonObject.optIntField(name: String): Int? =
        if (has(name) && get(name).isJsonPrimitive && get(name).asJsonPrimitive.isNumber) {
            get(name).asInt
        } else {
            null
        }
}

/**
 * AP D6: typed helper that reads `tools/call` arguments as a
 * JsonObject (or `null` when the client omitted them). Centralises
 * the cast so individual handlers don't repeat the `isJsonObject`
 * defensive code.
 */
internal fun JsonElement?.asListToolArgs(): JsonObject? =
    if (this != null && this.isJsonObject) this.asJsonObject else null
