package dev.dmigrate.mcp.resources

import dev.dmigrate.mcp.cursor.CursorKey
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.cursor.McpCursorCodec
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SealedResourcesListCursorTest : FunSpec({

    val tenantA = TenantId("acme")
    val tenantB = TenantId("beta")

    fun fixedClock(at: Instant): Clock = Clock.fixed(at, ZoneOffset.UTC)

    fun keyring(label: String = "k1"): CursorKeyring = CursorKeyring(
        signing = CursorKey(kid = label, secret = ByteArray(32) { it.toByte() }),
    )

    test("seal then unseal round-trips the inner cursor") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedResourcesListCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val inner = ResourcesListCursor(ResourceKind.JOBS, "page-token-7")
        val sealed = sut.seal(inner, tenantA)
        sealed shouldContain "."
        val outcome = sut.unseal(sealed, tenantA)
        outcome.shouldBeInstanceOf<SealedResourcesListCursor.Result.Success>()
        outcome.cursor shouldBe inner
    }

    test("cursor minted for tenant A fails verification under tenant B") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedResourcesListCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal(ResourcesListCursor(ResourceKind.SCHEMAS, null), tenantA)
        val outcome = sut.unseal(sealed, tenantB)
        outcome.shouldBeInstanceOf<SealedResourcesListCursor.Result.Failure>()
        outcome.reason shouldContain "tenant"
    }

    test("expired cursor surfaces a typed Failure") {
        val issuedAt = Instant.parse("2026-05-04T10:00:00Z")
        // Sign at issuedAt, verify well past TTL ceiling.
        val signClock = fixedClock(issuedAt)
        val verifyClock = fixedClock(issuedAt.plus(Duration.ofMinutes(20)))
        val signCodec = McpCursorCodec(keyring(), clock = signClock)
        val verifyCodec = McpCursorCodec(keyring(), clock = verifyClock)
        val signSut = SealedResourcesListCursor(
            signCodec,
            ttl = Duration.ofMinutes(5),
            clock = signClock,
        )
        val sealed = signSut.seal(ResourcesListCursor(ResourceKind.JOBS, null), tenantA)
        val verifySut = SealedResourcesListCursor(verifyCodec, clock = verifyClock)
        val outcome = verifySut.unseal(sealed, tenantA)
        outcome.shouldBeInstanceOf<SealedResourcesListCursor.Result.Failure>()
        outcome.reason shouldContain "expired"
    }

    test("tampered cursor (HMAC mismatch) surfaces a typed Failure") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedResourcesListCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal(ResourcesListCursor(ResourceKind.JOBS, null), tenantA)
        // Flip a byte in the payload portion (before the '.').
        val (payload, sig) = sealed.split('.', limit = 2)
        val flipped = (payload.first() + 1).toString() + payload.drop(1)
        val outcome = sut.unseal("$flipped.$sig", tenantA)
        outcome.shouldBeInstanceOf<SealedResourcesListCursor.Result.Failure>()
    }

    test("malformed cursor (no '.' separator) surfaces a typed Failure") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedResourcesListCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val outcome = sut.unseal("not-a-valid-cursor", tenantA)
        outcome.shouldBeInstanceOf<SealedResourcesListCursor.Result.Failure>()
    }
})
