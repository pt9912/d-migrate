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

class SealedListToolCursorTest : FunSpec({

    val tenantA = TenantId("acme")
    val tenantB = TenantId("beta")

    fun keyring(): CursorKeyring = CursorKeyring(
        signing = CursorKey(kid = "k1", secret = ByteArray(32) { it.toByte() }),
    )

    fun fixedClock(at: Instant): Clock = Clock.fixed(at, ZoneOffset.UTC)

    test("seal then unseal round-trips the resumeToken") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedListToolCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal(
            cursorType = "job_list",
            tenantId = tenantA,
            family = "jobs",
            filters = mapOf("status" to "SUCCEEDED"),
            pageSize = 50,
            resumeToken = "page-7",
        )
        val resume = sut.unseal(
            sealed = sealed,
            cursorType = "job_list",
            tenantId = tenantA,
            family = "jobs",
            filters = mapOf("status" to "SUCCEEDED"),
            pageSize = 50,
        )
        resume shouldBe "page-7"
    }

    test("cursor minted for tool A cannot be replayed on tool B") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedListToolCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal("job_list", tenantA, "jobs", emptyMap(), 50, "p1")
        shouldThrow<ValidationErrorException> {
            sut.unseal(sealed, "artifact_list", tenantA, "artifacts", emptyMap(), 50)
        }
    }

    test("cursor minted for tenant A cannot be replayed against tenant B") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedListToolCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal("job_list", tenantA, "jobs", emptyMap(), 50, "p1")
        shouldThrow<ValidationErrorException> {
            sut.unseal(sealed, "job_list", tenantB, "jobs", emptyMap(), 50)
        }
    }

    test("filter manipulation invalidates the cursor") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedListToolCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal(
            "job_list", tenantA, "jobs",
            mapOf("status" to "SUCCEEDED"), 50, "p1",
        )
        shouldThrow<ValidationErrorException> {
            sut.unseal(
                sealed, "job_list", tenantA, "jobs",
                mapOf("status" to "FAILED"), 50,
            )
        }
    }

    test("pageSize manipulation invalidates the cursor") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedListToolCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sealed = sut.seal("job_list", tenantA, "jobs", emptyMap(), 50, "p1")
        shouldThrow<ValidationErrorException> {
            sut.unseal(sealed, "job_list", tenantA, "jobs", emptyMap(), 100)
        }
    }

    test("malformed cursor surfaces ValidationErrorException") {
        val codec = McpCursorCodec(keyring(), clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        val sut = SealedListToolCursor(codec, clock = fixedClock(Instant.parse("2026-05-04T10:00:00Z")))
        shouldThrow<ValidationErrorException> {
            sut.unseal("not-a-valid-cursor", "job_list", tenantA, "jobs", emptyMap(), 50)
        }
    }
})
