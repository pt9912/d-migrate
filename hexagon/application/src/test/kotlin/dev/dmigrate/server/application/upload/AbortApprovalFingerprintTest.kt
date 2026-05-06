package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSessionState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength

/**
 * Phase F § 5.3 (F.6 2/3) — Pin't Determinismus + Field-Isolation
 * fuer den Pre-Abort-Fingerprint. Plan-Wortlaut "andere Session,
 * anderer Owner, anderer Pre-Abort-Status, anderer Caller oder
 * anderer reason liefern IDEMPOTENCY_CONFLICT" wird durch
 * field-by-field Hash-Variation gepin't.
 */
class AbortApprovalFingerprintTest : FunSpec({

    val service = AbortApprovalFingerprint(DefaultPayloadFingerprintService())

    fun base() = AbortApprovalAttempt(
        callerTenantId = TenantId("acme"),
        callerId = PrincipalId("admin-1"),
        sessionTenantId = TenantId("acme"),
        sessionOwnerPrincipalId = PrincipalId("alice"),
        uploadSessionId = "ups-1",
        preAbortState = UploadSessionState.ACTIVE,
        artifactKind = ArtifactKind.UPLOAD_INPUT,
        uploadIntent = "job_input",
        preAbortBytes = 1024L,
        reason = null,
    )

    test("Fingerprint ist 64 hex-Zeichen lang (SHA-256)") {
        service.fingerprint(base()) shouldHaveLength 64
    }

    test("Identischer Input -> identischer Fingerprint (Determinismus)") {
        val a = base()
        service.fingerprint(a) shouldBe service.fingerprint(a.copy())
    }

    test("uploadSessionId, sessionTenantId, sessionOwnerPrincipalId gehen jeweils in den Hash ein") {
        val baseFp = service.fingerprint(base())
        val variants = listOf(
            base().copy(uploadSessionId = "ups-2"),
            base().copy(sessionTenantId = TenantId("umbrella")),
            base().copy(sessionOwnerPrincipalId = PrincipalId("bob")),
        )
        for (variant in variants) {
            service.fingerprint(variant) shouldBe service.fingerprint(variant.copy())
            (service.fingerprint(variant) == baseFp) shouldBe false
        }
    }

    test("callerTenantId + callerId binden den Fingerprint an den Admin-Principal") {
        val baseFp = service.fingerprint(base())
        (service.fingerprint(base().copy(callerTenantId = TenantId("other"))) == baseFp) shouldBe false
        (service.fingerprint(base().copy(callerId = PrincipalId("admin-2"))) == baseFp) shouldBe false
    }

    test("preAbortState aendert den Fingerprint") {
        val baseFp = service.fingerprint(base())
        val finalizingFp = service.fingerprint(base().copy(preAbortState = UploadSessionState.FINALIZING))
        (finalizingFp == baseFp) shouldBe false
    }

    test("artifactKind + uploadIntent + preAbortBytes gehen jeweils in den Hash ein") {
        val baseFp = service.fingerprint(base())
        val variants = listOf(
            base().copy(artifactKind = ArtifactKind.SCHEMA),
            base().copy(uploadIntent = "schema_staging_readonly"),
            base().copy(preAbortBytes = 2048L),
        )
        for (variant in variants) {
            (service.fingerprint(variant) == baseFp) shouldBe false
        }
    }

    test("reason-Aenderung -> abweichender Fingerprint (Plan: anderer reason -> Conflict)") {
        val withoutReason = service.fingerprint(base())
        val withReason = service.fingerprint(base().copy(reason = "ops-cleanup"))
        val differentReason = service.fingerprint(base().copy(reason = "user-request"))
        (withReason == withoutReason) shouldBe false
        (differentReason == withReason) shouldBe false
    }

    test("Init- und Abort-Fingerprint unterscheiden sich auch bei sonst gleichem Material") {
        // Plan § 5.3: "keinen separaten Abort-Claim-Key" — toolName
        // im BindContext muss daher zwischen Init und Abort
        // unterscheiden, sonst wuerden parallele Init/Abort-Calls
        // im selben Scope kollidieren.
        val initService = UploadInitApprovalFingerprint(DefaultPayloadFingerprintService())
        val initFp = initService.fingerprint(
            UploadInitApprovalAttempt(
                tenantId = TenantId("acme"),
                callerId = PrincipalId("admin-1"),
                artifactKind = ArtifactKind.UPLOAD_INPUT,
                mimeType = "application/octet-stream",
                sizeBytes = 1024L,
                checksumSha256 = "deadbeef".repeat(8),
                uploadIntent = "job_input",
            ),
        )
        val abortFp = service.fingerprint(base())
        (initFp == abortFp) shouldBe false
    }
})
