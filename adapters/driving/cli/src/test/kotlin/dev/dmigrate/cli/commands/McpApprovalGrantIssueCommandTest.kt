package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import dev.dmigrate.mcp.registry.FileBackedApprovalGrantStore
import dev.dmigrate.server.application.approval.ApprovalTokenFingerprint
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class McpApprovalGrantIssueCommandTest : FunSpec({

    fun baseArgs(file: String) = listOf(
        "--file", file,
        "--tenant", "acme",
        "--caller", "alice",
        "--tool", "artifact_upload_init",
        "--approval-request-id", "appr-1",
        "--payload-fingerprint", "f".repeat(64),
        "--scope", "dmigrate:artifact:upload",
        "--expires-at", "2099-01-01T00:00:00Z",
        "--token", "appr_raw_token",
    )

    test("issue stores approvalKey grants as APPROVAL_KEY correlation") {
        val file = Files.createTempFile("dmigrate-approval-grants-", ".json")
        Files.deleteIfExists(file)

        McpApprovalGrantIssueCommand().parse(
            baseArgs(file.toString()) + listOf("--approval-key", "upload-key-1"),
        )

        val grant = FileBackedApprovalGrantStore(file).findByTokenFingerprint(
            TenantId("acme"),
            ApprovalTokenFingerprint.compute("appr_raw_token"),
        )!!
        grant.correlationKind shouldBe ApprovalCorrelationKind.APPROVAL_KEY
        grant.correlationKey shouldBe "upload-key-1"
    }

    test("issue requires exactly one correlation key") {
        val file = Files.createTempFile("dmigrate-approval-grants-", ".json")
        shouldThrow<UsageError> {
            McpApprovalGrantIssueCommand().parse(baseArgs(file.toString()))
        }
        shouldThrow<UsageError> {
            McpApprovalGrantIssueCommand().parse(
                baseArgs(file.toString()) + listOf(
                    "--idempotency-key", "job-key",
                    "--approval-key", "upload-key",
                ),
            )
        }
    }
})
