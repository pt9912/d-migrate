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
 * Plan-D §4.2 + §10.9 wrapper that HMAC-seals the
 * `artifact_chunk_get` continuation cursor. Phase-C used a naked
 * integer (`chunkId`) as both input and output — clients could
 * forge any chunk index to probe an artefact's content.
 *
 * Phase-D binds the cursor to (tenant, artifactId, chunkSize) so
 * a cursor minted for artefact A in tenant X cannot be replayed
 * against artefact B or tenant Y, and a `chunkSize` change
 * invalidates the cursor — protects clients that paginate
 * with a deployment-tuned `maxArtifactChunkBytes` from a
 * surreptitious server-side bump that would silently re-align
 * offsets mid-walk.
 *
 * Output rules from Plan-D §10.9:
 * - the response always carries `nextChunkCursor` (HMAC-sealed)
 *   when more chunks remain, alongside `nextChunkUri` (resource-
 *   URI form for the `resources/read` follow-up). On the last
 *   chunk both surface as `null`.
 * - the legacy naked `chunkId` input is still accepted (Phase-C
 *   compatibility) but is normalised internally; the handler
 *   does NOT emit a `nextChunkId` field on responses.
 */
internal class SealedChunkCursor(
    private val codec: McpCursorCodec,
    private val ttl: Duration = McpCursorCodec.DEFAULT_MAX_TTL,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Wraps the next chunk's index inside an HMAC-sealed envelope
     * bound to (tenant, artifactId, chunkSize). The
     * [nextChunkIndex] is the integer index the *next* call should
     * fetch — matches the index `nextChunkUri` points at.
     */
    fun seal(
        tenantId: TenantId,
        artifactId: String,
        chunkSize: Int,
        nextChunkIndex: Int,
    ): String {
        val now = Instant.now(clock)
        return codec.encode(
            McpCursorPayload(
                cursorType = CURSOR_TYPE,
                version = McpCursorCodec.SUPPORTED_VERSION,
                kid = "outer-chunk",
                tenantId = tenantId,
                family = FAMILY,
                filters = mapOf(
                    "artifactId" to artifactId,
                    "chunkSize" to chunkSize.toString(),
                ),
                pageSize = chunkSize,
                sort = null,
                resumeToken = nextChunkIndex.toString(),
                issuedAt = now,
                expiresAt = now.plus(ttl),
            ),
        )
    }

    /**
     * Verifies + unwraps a sealed cursor. Throws
     * [ValidationErrorException] (which the dispatcher renders as
     * the standard tool-error envelope) on tamper / binding
     * mismatch / expiry / forgery. Returns the chunk index the
     * caller should fetch.
     */
    fun unseal(
        sealed: String,
        tenantId: TenantId,
        artifactId: String,
        chunkSize: Int,
    ): Int {
        val expected = CursorBinding(
            cursorType = CURSOR_TYPE,
            tenantId = tenantId,
            family = FAMILY,
            filters = mapOf(
                "artifactId" to artifactId,
                "chunkSize" to chunkSize.toString(),
            ),
            pageSize = chunkSize,
            sort = null,
        )
        val outcome = codec.decode(sealed, expected)
        val payload = when (outcome) {
            is McpCursorDecodeResult.Valid -> outcome.payload
            is McpCursorDecodeResult.Invalid -> throw ValidationErrorException(
                listOf(ValidationViolation("nextChunkCursor", outcome.reason)),
            )
        }
        val resumeToken = payload.resumeToken
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("nextChunkCursor", "cursor missing resumeToken")),
            )
        return resumeToken.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw ValidationErrorException(
                listOf(ValidationViolation("nextChunkCursor", "cursor resumeToken not a non-negative integer")),
            )
    }

    companion object {
        const val CURSOR_TYPE: String = "artifact_chunk_get"

        /**
         * Family is constant; the per-artefact identity lives in
         * the binding's `filters.artifactId` so the codec rejects
         * cursor reuse across artefacts.
         */
        const val FAMILY: String = "artifact-chunks"
    }
}
