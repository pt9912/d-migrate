package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.cursor.CursorBinding
import dev.dmigrate.mcp.cursor.McpCursorCodec
import dev.dmigrate.mcp.cursor.McpCursorDecodeResult
import dev.dmigrate.mcp.cursor.McpCursorPayload
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.principal.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Plan-D §4.2 + §10.6 + §10.8 wrapper that HMAC-seals every
 * `*_list` discovery tool's cursor. Distinct from
 * [dev.dmigrate.mcp.resources.SealedResourcesListCursor] only in
 * the binding shape: discovery cursors carry a per-tool
 * `cursorType` (`"job_list"`, `"artifact_list"`, …), the resource
 * `family`, and the request's filter map — so cursor reuse
 * across tools, families or filter sets fails verification.
 *
 * Phase-D §6.2 default sort (`createdAt DESC, id ASC`) is the
 * only sort the discovery handlers issue, so [sort] is `null`
 * here. Future per-tool sort modes plug in by setting the field
 * — the codec already binds it.
 *
 * AP D6 returned `nextCursor=null` from every list handler; AP
 * D8 sub-commit 2 wires this codec so multi-page navigation
 * actually round-trips, with each cursor scoped to (tenant,
 * family, filters, pageSize, sort) per Plan-D §6.2 / §6.4.
 */
internal class SealedListToolCursor(
    private val codec: McpCursorCodec,
    private val ttl: Duration = McpCursorCodec.DEFAULT_MAX_TTL,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Wraps the store's [resumeToken] (the underlying
     * `PageRequest.pageToken` for the next page) in an HMAC-sealed
     * outer envelope bound to the request shape.
     */
    fun seal(
        cursorType: String,
        tenantId: TenantId,
        family: String,
        filters: Map<String, String>,
        pageSize: Int,
        resumeToken: String,
    ): String {
        val now = Instant.now(clock)
        return codec.encode(
            McpCursorPayload(
                cursorType = cursorType,
                version = McpCursorCodec.SUPPORTED_VERSION,
                kid = "outer-list-tool",
                tenantId = tenantId,
                family = family,
                filters = filters,
                pageSize = pageSize,
                sort = null,
                resumeToken = resumeToken,
                issuedAt = now,
                expiresAt = now.plus(ttl),
            ),
        )
    }

    /**
     * Verifies + unwraps a sealed cursor. Throws
     * [ValidationErrorException] (which the dispatcher renders as
     * a `VALIDATION_ERROR` envelope per Plan-D §6.4) on tamper /
     * binding mismatch / expiry / forgery so every per-tool path
     * funnels the same wire shape on cursor failures.
     */
    fun unseal(
        sealed: String,
        cursorType: String,
        tenantId: TenantId,
        family: String,
        filters: Map<String, String>,
        pageSize: Int,
    ): String {
        val expected = CursorBinding(
            cursorType = cursorType,
            tenantId = tenantId,
            family = family,
            filters = filters,
            pageSize = pageSize,
            sort = null,
        )
        val outcome = codec.decode(sealed, expected)
        val payload = when (outcome) {
            is McpCursorDecodeResult.Valid -> outcome.payload
            is McpCursorDecodeResult.Invalid -> throw ValidationErrorException(
                listOf(ValidationViolation("cursor", outcome.reason)),
            )
        }
        return payload.resumeToken
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("cursor", "cursor missing resumeToken")),
            )
    }
}
