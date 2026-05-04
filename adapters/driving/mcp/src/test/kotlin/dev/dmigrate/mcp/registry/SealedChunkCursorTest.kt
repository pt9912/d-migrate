package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.cursor.CursorKey
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.cursor.McpCursorCodec
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SealedChunkCursorTest : FunSpec({

    val tenantA = TenantId("acme")
    val tenantB = TenantId("beta")

    fun keyring(): CursorKeyring = CursorKeyring(
        signing = CursorKey(kid = "k1", secret = ByteArray(32) { it.toByte() }),
    )

    fun fixedClock(at: Instant): Clock = Clock.fixed(at, ZoneOffset.UTC)

    test("seal then unseal round-trips the chunk index") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedChunkCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal(tenantA, "art-1", 32_768, nextChunkIndex = 5)
        sut.unseal(sealed, tenantA, "art-1", 32_768) shouldBe 5
    }

    test("cursor minted for artefact A cannot be replayed against artefact B") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedChunkCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal(tenantA, "art-1", 32_768, 5)
        shouldThrow<ValidationErrorException> {
            sut.unseal(sealed, tenantA, "art-2", 32_768)
        }
    }

    test("cursor minted for tenant A cannot be replayed against tenant B") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedChunkCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal(tenantA, "art-1", 32_768, 5)
        shouldThrow<ValidationErrorException> {
            sut.unseal(sealed, tenantB, "art-1", 32_768)
        }
    }

    test("chunkSize change between mint and verify invalidates the cursor") {
        // Plan-D §10.9: chunkSize is part of the binding so a
        // server that bumps `maxArtifactChunkBytes` mid-walk
        // doesn't silently re-align byte offsets.
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedChunkCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal(tenantA, "art-1", 32_768, 5)
        shouldThrow<ValidationErrorException> {
            sut.unseal(sealed, tenantA, "art-1", 65_536)
        }
    }

    test("malformed cursor surfaces ValidationErrorException") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedChunkCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        shouldThrow<ValidationErrorException> {
            sut.unseal("not-a-valid-cursor", tenantA, "art-1", 32_768)
        }
    }
})
