package dev.dmigrate.mcp.cursor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import dev.dmigrate.server.core.principal.TenantId
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phase-D §10.3: opaque, HMAC-sealed cursor for the public MCP
 * adapter. Clients treat the wire string as opaque bytes; the
 * codec verifies signature, key id, schema version, expiry and a
 * caller-supplied binding (tenant, family, filters, page size,
 * sort) before returning the inner [McpCursorPayload].
 *
 * Wire format: `<base64url(payload)>.<base64url(hmac)>`.
 * - The payload is a deterministic JSON of [McpCursorPayload].
 * - The HMAC is computed over the **base64url-encoded payload
 *   bytes** (not the raw JSON) so verification needs no JSON
 *   canonicalisation pass.
 * - Algorithm: HMAC-SHA256 — pinned for cross-instance
 *   determinism.
 */
class McpCursorCodec(
    private val keyring: CursorKeyring,
    private val clock: Clock = Clock.systemUTC(),
    private val maxTtl: Duration = DEFAULT_MAX_TTL,
    private val clockSkew: Duration = DEFAULT_CLOCK_SKEW,
) {

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    /**
     * Builds a sealed cursor for [payload]. Signs with the
     * keyring's active key; the resulting cursor carries that
     * key's `kid` so verifiers can pick the matching validation
     * key.
     *
     * @throws IllegalArgumentException if `expiresAt <= issuedAt`,
     * if the TTL exceeds [maxTtl], or if the payload's kid does
     * not match the active signing key (we re-stamp the kid to
     * prevent caller drift).
     */
    fun encode(payload: McpCursorPayload): String {
        require(payload.expiresAt > payload.issuedAt) {
            "cursor expiresAt must be after issuedAt"
        }
        require(Duration.between(payload.issuedAt, payload.expiresAt) <= maxTtl) {
            "cursor TTL exceeds maxTtl=$maxTtl"
        }
        val stamped = payload.copy(kid = keyring.signing.kid)
        val json = gson.toJson(stamped)
        val payloadB64 = URL_ENCODER.encodeToString(json.toByteArray(Charsets.UTF_8))
        val sigB64 = sign(keyring.signing, payloadB64)
        return "$payloadB64.$sigB64"
    }

    /**
     * Decodes [cursor] and validates it against [expected].
     * Returns either the inner payload or a typed [Invalid]
     * carrying a reason — every failure class collapses to one
     * `VALIDATION_ERROR` at the JSON-RPC layer per §10.3.
     */
    @Suppress("ReturnCount")
    fun decode(cursor: String, expected: CursorBinding): McpCursorDecodeResult {
        val (payloadB64, sigB64) = parseTwoParts(cursor)
            ?: return McpCursorDecodeResult.Invalid("cursor must have exactly one '.' separator")

        // 1. Decode payload bytes (signature checked next).
        val json = runCatching {
            String(URL_DECODER.decode(payloadB64), Charsets.UTF_8)
        }.getOrElse {
            return McpCursorDecodeResult.Invalid("payload base64 decode failed")
        }
        val payload = runCatching { gson.fromJson(json, McpCursorPayload::class.java) }
            .getOrElse {
                if (it is JsonSyntaxException) {
                    return McpCursorDecodeResult.Invalid("payload json malformed")
                }
                throw it
            }
            ?: return McpCursorDecodeResult.Invalid("payload was null")

        // 2. Schema version.
        if (payload.version != SUPPORTED_VERSION) {
            return McpCursorDecodeResult.Invalid("unknown cursor version ${payload.version}")
        }

        // 3. Locate the validation key. Unknown kid → invalid
        // even if a clever HMAC happens to match some other key.
        val key = keyring.lookupValidation(payload.kid)
            ?: return McpCursorDecodeResult.Invalid("unknown cursor kid '${payload.kid}'")

        // 4. Constant-time signature check over the wire-form
        // payload bytes (no JSON canonicalisation needed).
        val expectedSig = sign(key, payloadB64)
        if (!constantTimeEquals(expectedSig.toByteArray(), sigB64.toByteArray())) {
            return McpCursorDecodeResult.Invalid("cursor signature mismatch")
        }

        // 5. Expiry. clockSkew tolerates small drift between
        // signer and verifier.
        val now = Instant.now(clock)
        if (payload.expiresAt <= payload.issuedAt) {
            return McpCursorDecodeResult.Invalid("cursor expiresAt <= issuedAt")
        }
        if (now.isAfter(payload.expiresAt.plus(clockSkew))) {
            return McpCursorDecodeResult.Invalid("cursor expired")
        }

        // 6. Caller binding.
        if (payload.cursorType != expected.cursorType) {
            return McpCursorDecodeResult.Invalid("cursor type mismatch")
        }
        if (payload.tenantId != expected.tenantId) {
            return McpCursorDecodeResult.Invalid("cursor tenant mismatch")
        }
        if (payload.family != expected.family) {
            return McpCursorDecodeResult.Invalid("cursor family mismatch")
        }
        if (payload.filters != expected.filters) {
            return McpCursorDecodeResult.Invalid("cursor filter mismatch")
        }
        if (payload.pageSize != expected.pageSize) {
            return McpCursorDecodeResult.Invalid("cursor pageSize mismatch")
        }
        if (payload.sort != expected.sort) {
            return McpCursorDecodeResult.Invalid("cursor sort mismatch")
        }

        return McpCursorDecodeResult.Valid(payload)
    }

    private fun sign(key: CursorKey, payloadB64: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key.secret, HMAC_ALGORITHM))
        val sig = mac.doFinal(payloadB64.toByteArray(Charsets.UTF_8))
        return URL_ENCODER.encodeToString(sig)
    }

    private fun parseTwoParts(cursor: String): Pair<String, String>? {
        val parts = cursor.split('.')
        if (parts.size != 2) return null
        if (parts[0].isEmpty() || parts[1].isEmpty()) return null
        return parts[0] to parts[1]
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    companion object {
        const val SUPPORTED_VERSION: Int = 1
        const val HMAC_ALGORITHM: String = "HmacSHA256"

        private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val URL_DECODER = Base64.getUrlDecoder()

        /** Default 15-minute TTL ceiling per §4.2. */
        val DEFAULT_MAX_TTL: Duration = Duration.ofMinutes(15)

        /** Default tolerance for clock skew between signer and verifier. */
        val DEFAULT_CLOCK_SKEW: Duration = Duration.ofSeconds(30)
    }
}

/**
 * Cursor payload Phase-D pins per §10.3. Pure data; no behaviour.
 *
 * Versioning: [version] starts at [McpCursorCodec.SUPPORTED_VERSION].
 * A future schema change bumps the constant; old cursors decode
 * with the previous codec instance, never silently re-bind.
 */
data class McpCursorPayload(
    val cursorType: String,
    val version: Int,
    val kid: String,
    val tenantId: TenantId,
    val family: String,
    val filters: Map<String, String> = emptyMap(),
    val pageSize: Int,
    val sort: String? = null,
    val resumeToken: String? = null,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

/**
 * Caller-supplied binding the codec validates the decoded cursor
 * against. Must mirror the request that produced the cursor — any
 * drift (different tenant, filters, pageSize, sort) collapses to
 * `VALIDATION_ERROR` at the JSON-RPC layer, never to a successful
 * resume on a different listing.
 */
data class CursorBinding(
    val cursorType: String,
    val tenantId: TenantId,
    val family: String,
    val filters: Map<String, String> = emptyMap(),
    val pageSize: Int,
    val sort: String? = null,
)

/**
 * One signing/validation key. [secret] is the HMAC-SHA256 shared
 * secret; [kid] is the stable label callers see in the cursor's
 * `kid` field for rotation routing.
 *
 * `secret` is kept as a `ByteArray` on purpose — `String` would
 * canonicalise into a String-pool entry that is harder to clear.
 */
data class CursorKey(
    val kid: String,
    val secret: ByteArray,
) {
    init {
        require(kid.isNotBlank()) { "cursor key kid must be non-blank" }
        require(secret.isNotEmpty()) { "cursor key secret must be non-empty" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CursorKey) return false
        return kid == other.kid && secret.contentEquals(other.secret)
    }

    override fun hashCode(): Int = kid.hashCode() * 31 + secret.contentHashCode()
}

/**
 * Phase-D §10.3 keyring: exactly one active signing key + zero or
 * more validation-only keys for rotation. The rollout discipline
 * in §10.3 (validation-first → activate → keep old as validation
 * for `maxTtl + clockSkew`) is enforced by deployment, not by
 * this type — but the data model carries enough to express it.
 */
class CursorKeyring(
    val signing: CursorKey,
    additionalValidation: List<CursorKey> = emptyList(),
) {
    /**
     * Every key the verifier accepts, including the active
     * signing key. Order: signing first, then any rotation keys
     * passed in order.
     */
    val allValidation: List<CursorKey> = buildList {
        add(signing)
        val seen = linkedMapOf(signing.kid to signing)
        for (k in additionalValidation) {
            val existing = seen[k.kid]
            require(existing == null || existing == k) {
                "validation key '${k.kid}' collides with a different secret"
            }
            if (existing == null) {
                seen[k.kid] = k
                add(k)
            }
        }
    }

    fun lookupValidation(kid: String): CursorKey? =
        allValidation.firstOrNull { it.kid == kid }
}

sealed interface McpCursorDecodeResult {
    data class Valid(val payload: McpCursorPayload) : McpCursorDecodeResult
    data class Invalid(val reason: String) : McpCursorDecodeResult
}
