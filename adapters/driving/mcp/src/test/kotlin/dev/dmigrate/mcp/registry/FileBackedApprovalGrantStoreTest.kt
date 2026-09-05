package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

class FileBackedApprovalGrantStoreTest : FunSpec({

    fun grant(
        approvalRequestId: String = "apr-1",
        tokenFingerprint: String = "fp-1",
        tenantId: TenantId = TenantId("acme"),
    ) = ApprovalGrant(
        approvalRequestId = approvalRequestId,
        correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
        correlationKey = "key-1",
        approvalTokenFingerprint = tokenFingerprint,
        toolName = "testdata_execute",
        tenantId = tenantId,
        callerId = PrincipalId("alice"),
        payloadFingerprint = "a".repeat(64),
        issuerFingerprint = "issuer-fp",
        issuedScopes = setOf("dmigrate:ai:execute"),
        grantSource = "cli-approval-grant",
        expiresAt = Instant.parse("2026-05-13T10:00:00Z"),
    )

    test("save and findByTokenFingerprint round-trip") {
        val file = Files.createTempFile("approval-grants-test-", ".yaml")
        val store = FileBackedApprovalGrantStore(file)
        val g = grant()

        store.save(g)

        store.findByTokenFingerprint(TenantId("acme"), "fp-1") shouldBe g
    }

    test("findByTokenFingerprint is tenant-scoped") {
        val file = Files.createTempFile("approval-grants-test-", ".yaml")
        val store = FileBackedApprovalGrantStore(file)
        store.save(grant(tenantId = TenantId("acme")))

        store.findByTokenFingerprint(TenantId("umbrella"), "fp-1") shouldBe null
    }

    test("save replaces an existing grant with the same tenant + tokenFingerprint") {
        val file = Files.createTempFile("approval-grants-test-", ".yaml")
        val store = FileBackedApprovalGrantStore(file)
        store.save(grant(approvalRequestId = "apr-old"))

        store.save(grant(approvalRequestId = "apr-new"))

        store.findByTokenFingerprint(TenantId("acme"), "fp-1")?.approvalRequestId shouldBe "apr-new"
    }

    test("deleteExpired removes only expired grants and returns the removed count") {
        val file = Files.createTempFile("approval-grants-test-", ".yaml")
        val store = FileBackedApprovalGrantStore(file)
        store.save(grant(approvalRequestId = "keep", tokenFingerprint = "fp-keep"))
        val expired = grant(approvalRequestId = "drop", tokenFingerprint = "fp-drop")
            .copy(expiresAt = Instant.parse("2020-01-01T00:00:00Z"))
        store.save(expired)

        val removed = store.deleteExpired(Instant.parse("2026-01-01T00:00:00Z"))

        removed shouldBe 1
        store.findByTokenFingerprint(TenantId("acme"), "fp-drop") shouldBe null
        store.findByTokenFingerprint(TenantId("acme"), "fp-keep") shouldBe grant(
            approvalRequestId = "keep",
            tokenFingerprint = "fp-keep",
        )
    }

    // Regression: a real deployment had `mcp approval-grant issue` (CLI, host UID) and
    // `mcp serve` (containerised, different UID) share this file. Files.createTempFile()
    // defaults to owner-only (600) on POSIX, and ATOMIC_MOVE carried that onto the replaced
    // file -- the server could no longer read a grant the CLI had just written, surfacing
    // as an unhandled IllegalStateException swallowed deep in AiToolOrchestrator.
    test("save widens the file's permissions so a different OS user can still read it") {
        val file = Files.createTempFile("approval-grants-test-", ".yaml")
        val store = FileBackedApprovalGrantStore(file)

        store.save(grant())

        val perms = Files.getPosixFilePermissions(file)
        perms shouldBe PosixFilePermissions.fromString("rw-r--r--")
        (PosixFilePermission.GROUP_READ in perms) shouldBe true
        (PosixFilePermission.OTHERS_READ in perms) shouldBe true
    }
})
