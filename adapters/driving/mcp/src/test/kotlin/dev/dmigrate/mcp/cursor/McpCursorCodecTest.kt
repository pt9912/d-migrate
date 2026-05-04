package dev.dmigrate.mcp.cursor

import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * AP D3 (`ImpPlan-0.9.6-D.md` §10.3) golden tests:
 *  - roundtrip per cursor type
 *  - HMAC tampering, tenant/family/filter/sort/pageSize mismatch
 *  - expiry, missing-/inverted-TTL → VALIDATION_ERROR
 *  - unknown kid, unknown version → VALIDATION_ERROR
 *  - key rotation: active kid signs, old validation kid validates
 *    until removed; once removed, signatures with that kid fail
 */
class McpCursorCodecTest : FunSpec({

    val activeKey = CursorKey("kid-active", ByteArray(SECRET_LEN) { 1 })
    val rotatedKey = CursorKey("kid-rotated", ByteArray(SECRET_LEN) { 2 })

    fun fixedClock(t: Instant): Clock = Clock.fixed(t, ZoneOffset.UTC)

    fun samplePayload(
        kid: String = activeKey.kid,
        issuedAt: Instant = REF,
        expiresAt: Instant = REF.plus(Duration.ofMinutes(10)),
        tenantId: TenantId = TenantId("acme"),
        family: String = "jobs",
        filters: Map<String, String> = mapOf("status" to "SUCCEEDED"),
        pageSize: Int = 50,
        sort: String? = "createdAt:desc",
        resumeToken: String? = "tok-1",
        version: Int = McpCursorCodec.SUPPORTED_VERSION,
    ): McpCursorPayload = McpCursorPayload(
        cursorType = "job_list",
        version = version,
        kid = kid,
        tenantId = tenantId,
        family = family,
        filters = filters,
        pageSize = pageSize,
        sort = sort,
        resumeToken = resumeToken,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
    )

    fun sampleBinding(
        cursorType: String = "job_list",
        tenantId: TenantId = TenantId("acme"),
        family: String = "jobs",
        filters: Map<String, String> = mapOf("status" to "SUCCEEDED"),
        pageSize: Int = 50,
        sort: String? = "createdAt:desc",
    ): CursorBinding = CursorBinding(cursorType, tenantId, family, filters, pageSize, sort)

    test("roundtrip: encode + decode returns the same payload (with active kid stamped)") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload())
        val decoded = codec.decode(cursor, sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Valid>()
        decoded.payload.kid shouldBe activeKey.kid
        decoded.payload.tenantId shouldBe TenantId("acme")
        decoded.payload.family shouldBe "jobs"
        decoded.payload.resumeToken shouldBe "tok-1"
    }

    test("HMAC tampering: any byte change in payload section fails the signature check") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload())
        // Flip the first byte of the payload base64 — guaranteed to
        // change the JSON content but not the signature.
        val (payloadB64, sigB64) = cursor.split('.', limit = 2)
        val flipped = payloadB64.first().let {
            (if (it == 'A') 'B' else 'A').toString()
        } + payloadB64.drop(1)
        val tampered = "$flipped.$sigB64"
        val decoded = codec.decode(tampered, sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
    }

    test("HMAC tampering: any byte change in signature section fails the check") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload())
        val (payloadB64, sigB64) = cursor.split('.', limit = 2)
        val flippedSig = sigB64.first().let {
            (if (it == 'A') 'B' else 'A').toString()
        } + sigB64.drop(1)
        val tampered = "$payloadB64.$flippedSig"
        val decoded = codec.decode(tampered, sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "signature"
    }

    test("decode rejects malformed wire (no dot)") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val decoded = codec.decode("notacursor", sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
    }

    test("decode rejects malformed wire (empty halves)") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        codec.decode(".", sampleBinding()).shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        codec.decode("a.", sampleBinding()).shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        codec.decode(".b", sampleBinding()).shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
    }

    test("decode rejects bad base64 in payload section") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val (_, sig) = codec.encode(samplePayload()).split('.', limit = 2)
        val decoded = codec.decode("###not-base64###.$sig", sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
    }

    test("tenant mismatch in binding fails decode") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload(tenantId = TenantId("acme")))
        val decoded = codec.decode(cursor, sampleBinding(tenantId = TenantId("foreign")))
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "tenant"
    }

    test("family mismatch in binding fails decode") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload(family = "jobs"))
        val decoded = codec.decode(cursor, sampleBinding(family = "artifacts"))
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "family"
    }

    test("filter mismatch in binding fails decode") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload(filters = mapOf("status" to "SUCCEEDED")))
        val decoded = codec.decode(cursor, sampleBinding(filters = mapOf("status" to "FAILED")))
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "filter"
    }

    test("pageSize mismatch in binding fails decode") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload(pageSize = 50))
        val decoded = codec.decode(cursor, sampleBinding(pageSize = 100))
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "pageSize"
    }

    test("sort mismatch in binding fails decode") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload(sort = "createdAt:desc"))
        val decoded = codec.decode(cursor, sampleBinding(sort = "createdAt:asc"))
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "sort"
    }

    test("cursorType mismatch in binding fails decode") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload())
        val decoded = codec.decode(cursor, sampleBinding(cursorType = "artifact_list"))
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "type"
    }

    test("expired cursor (now > expiresAt + clockSkew) fails decode") {
        // Issue at REF for 10min, decode at REF+11min+1s (past
        // the 30s clockSkew window) → invalid.
        val codec = McpCursorCodec(
            CursorKeyring(activeKey),
            fixedClock(REF.plus(Duration.ofMinutes(11)).plusSeconds(1)),
        )
        val cursor = codec.encode(
            samplePayload(
                issuedAt = REF,
                expiresAt = REF.plus(Duration.ofMinutes(10)),
            ),
        )
        // Re-decode at the late clock — codec built with the late
        // clock validates against it.
        val decoded = codec.decode(cursor, sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "expired"
    }

    test("clock skew tolerance: cursor expired at exact expiry but within skew window passes") {
        val codec = McpCursorCodec(
            CursorKeyring(activeKey),
            fixedClock(REF.plus(Duration.ofMinutes(10)).plusSeconds(15)),
            clockSkew = Duration.ofSeconds(30),
        )
        val cursor = codec.encode(
            samplePayload(
                issuedAt = REF,
                expiresAt = REF.plus(Duration.ofMinutes(10)),
            ),
        )
        val decoded = codec.decode(cursor, sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Valid>()
    }

    test("encode rejects payload where expiresAt <= issuedAt") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        shouldThrow<IllegalArgumentException> {
            codec.encode(samplePayload(issuedAt = REF, expiresAt = REF))
        }
    }

    test("encode rejects TTL exceeding maxTtl") {
        val codec = McpCursorCodec(
            CursorKeyring(activeKey),
            fixedClock(REF),
            maxTtl = Duration.ofMinutes(15),
        )
        shouldThrow<IllegalArgumentException> {
            codec.encode(
                samplePayload(
                    issuedAt = REF,
                    expiresAt = REF.plus(Duration.ofMinutes(20)),
                ),
            )
        }
    }

    test("unknown version fails decode (forward-compat boundary)") {
        val codec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val cursor = codec.encode(samplePayload(version = 99))
        val decoded = codec.decode(cursor, sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "version"
    }

    test("unknown kid fails decode (key not in keyring)") {
        // Sign with rotatedKey, then verify against a keyring that
        // ONLY knows activeKey — kid lookup fails before signature.
        val signingCodec = McpCursorCodec(CursorKeyring(rotatedKey), fixedClock(REF))
        val cursor = signingCodec.encode(samplePayload(kid = rotatedKey.kid))

        val verifyingCodec = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val decoded = verifyingCodec.decode(cursor, sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
        decoded.reason shouldContain "kid"
    }

    test("key rotation: old kid validates as long as it is in the validation set") {
        // Pre-rotation: cursor signed with rotatedKey.
        val preRotation = McpCursorCodec(CursorKeyring(rotatedKey), fixedClock(REF))
        val pinnedCursor = preRotation.encode(samplePayload())

        // Rotation: activeKey is now signing, rotatedKey kept as
        // validation. Existing cursor still verifies.
        val postRotation = McpCursorCodec(
            CursorKeyring(signing = activeKey, additionalValidation = listOf(rotatedKey)),
            fixedClock(REF),
        )
        val decoded = postRotation.decode(pinnedCursor, sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Valid>()

        // New cursors signed by postRotation use the active kid.
        val newCursor = postRotation.encode(samplePayload())
        val newDecoded = postRotation.decode(newCursor, sampleBinding())
        newDecoded.shouldBeInstanceOf<McpCursorDecodeResult.Valid>()
        newDecoded.payload.kid shouldBe activeKey.kid
    }

    test("key rotation: once rotated key is removed, cursors signed with it fail") {
        // Sign with rotated. Verifier only has activeKey now.
        val preRotation = McpCursorCodec(CursorKeyring(rotatedKey), fixedClock(REF))
        val staleCursor = preRotation.encode(samplePayload())

        val finalRotation = McpCursorCodec(CursorKeyring(activeKey), fixedClock(REF))
        val decoded = finalRotation.decode(staleCursor, sampleBinding())
        decoded.shouldBeInstanceOf<McpCursorDecodeResult.Invalid>()
    }

    test("CursorKeyring rejects a validation key that aliases the active kid with a different secret") {
        val collidingValidation = CursorKey(activeKey.kid, ByteArray(SECRET_LEN) { 9 })
        shouldThrow<IllegalArgumentException> {
            CursorKeyring(signing = activeKey, additionalValidation = listOf(collidingValidation))
        }
    }

    test("CursorKeyring rejects duplicate validation kid with different secrets") {
        val first = CursorKey("kid-validation", ByteArray(SECRET_LEN) { 3 })
        val second = CursorKey("kid-validation", ByteArray(SECRET_LEN) { 4 })
        shouldThrow<IllegalArgumentException> {
            CursorKeyring(signing = activeKey, additionalValidation = listOf(first, second))
        }
    }

    test("CursorKey rejects blank kid / empty secret at construction") {
        shouldThrow<IllegalArgumentException> { CursorKey("", ByteArray(SECRET_LEN) { 1 }) }
        shouldThrow<IllegalArgumentException> { CursorKey("kid", ByteArray(0)) }
    }
})

private const val SECRET_LEN: Int = 32
private val REF: Instant = Instant.parse("2026-05-04T10:00:00Z")
