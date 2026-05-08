package dev.dmigrate.mcp.resources

import dev.dmigrate.mcp.cursor.CursorBinding
import dev.dmigrate.mcp.cursor.McpCursorCodec
import dev.dmigrate.mcp.cursor.McpCursorDecodeResult
import dev.dmigrate.mcp.cursor.McpCursorPayload
import dev.dmigrate.server.core.principal.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Plan-D §4.2 + §10.8 wrapper that HMAC-seals the
 * `resources/list` cursor. Phase-B kept the cursor as
 * Base64-of-JSON `(kind, innerToken)` — no signature, no tenant
 * binding, no expiry. Phase-D wraps that inner state inside an
 * [McpCursorPayload] so a client cannot replay a cursor minted
 * for tenant A against a request scoped to tenant B, alter the
 * `pageSize`, or use an expired cursor to keep paging.
 *
 * Wire format: the outer [McpCursorCodec] sealed envelope; the
 * inner [ResourcesListCursor] travels through `resumeToken` as
 * the existing Base64-JSON string. `family` is a Phase-D constant
 * (`"resources/list-walk"`) because the resources/list walker
 * crosses kinds within a single response — the per-kind family
 * already lives inside `resumeToken` and the codec's family field
 * pins the operation, not the current walk position.
 *
 * The `null` codec path (Phase-B / tests / single-instance default
 * without keyring wiring) keeps the unsigned legacy cursor so the
 * existing test suite stays green; production wiring per
 * `McpServerBootstrap` always supplies a codec.
 */
internal class SealedResourcesListCursor(
    private val codec: McpCursorCodec,
    private val ttl: Duration = DEFAULT_TTL,
    private val clock: Clock = Clock.systemUTC(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {

    /**
     * Wraps the unsigned [inner] cursor into the HMAC-sealed
     * outer envelope, binding to the requesting tenant.
     */
    fun seal(inner: ResourcesListCursor, tenantId: TenantId): String {
        val now = Instant.now(clock)
        return codec.encode(
            McpCursorPayload(
                cursorType = CURSOR_TYPE,
                version = McpCursorCodec.SUPPORTED_VERSION,
                // The codec re-stamps kid from the keyring's active
                // signing key, but we still pass it explicitly so
                // the deterministic `data class.copy(kid = ...)`
                // semantics inside [McpCursorCodec.encode] don't
                // surprise a future maintainer.
                kid = "outer-resources-list",
                tenantId = tenantId,
                family = FAMILY_WALK,
                filters = emptyMap(),
                pageSize = pageSize,
                sort = null,
                resumeToken = inner.encode(),
                issuedAt = now,
                expiresAt = now.plus(ttl),
            ),
        )
    }

    /**
     * Verifies + unwraps a sealed outer cursor. Returns the inner
     * cursor on success, or a [Failure] describing the rejection
     * class so the dispatcher renders the typed JSON-RPC error.
     */
    fun unseal(sealed: String, tenantId: TenantId): Result {
        val expected = CursorBinding(
            cursorType = CURSOR_TYPE,
            tenantId = tenantId,
            family = FAMILY_WALK,
            filters = emptyMap(),
            pageSize = pageSize,
            sort = null,
        )
        val outcome = codec.decode(sealed, expected)
        val payload = when (outcome) {
            is McpCursorDecodeResult.Valid -> outcome.payload
            is McpCursorDecodeResult.Invalid -> return Result.Failure(outcome.reason)
        }
        val resumeToken = payload.resumeToken
            ?: return Result.Failure("cursor missing resumeToken")
        val inner = try {
            ResourcesListCursor.decode(resumeToken)
        } catch (_: IllegalArgumentException) {
            // Inner-token tampering bypasses the outer HMAC (the
            // attacker would have to re-sign), so this branch is
            // mostly a defensive net for downgrade attempts where
            // a legitimate cursor was minted with a since-changed
            // inner-cursor format.
            return Result.Failure("cursor resumeToken malformed")
        } ?: return Result.Failure("cursor resumeToken empty")
        return Result.Success(inner)
    }

    sealed interface Result {
        data class Success(val cursor: ResourcesListCursor) : Result
        data class Failure(val reason: String) : Result
    }

    companion object {
        const val CURSOR_TYPE: String = "resources/list"

        /**
         * Outer family is a constant rather than the current
         * resource kind: resources/list crosses kinds within a
         * single response, so the per-kind state belongs in
         * `resumeToken`, not in the codec's family binding.
         */
        const val FAMILY_WALK: String = "resources/list-walk"

        /** Plan-D §4.2 cursor TTL ceiling. */
        val DEFAULT_TTL: Duration = McpCursorCodec.DEFAULT_MAX_TTL

        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}
