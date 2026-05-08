package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.text.FakeUnicodeTextService
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength

/**
 * Phase F § 4.2 (F.3 1/4) — Pin't Determinismus, Field-Isolation und
 * targetTable-Optionalitaet des Approval-Fingerprints.
 */
class UploadInitApprovalFingerprintTest : FunSpec({

    val service = UploadInitApprovalFingerprint(DefaultPayloadFingerprintService(FakeUnicodeTextService()))

    fun base() = UploadInitApprovalAttempt(
        tenantId = TenantId("acme"),
        callerId = PrincipalId("alice"),
        artifactKind = ArtifactKind.SCHEMA,
        mimeType = "application/json",
        sizeBytes = 1024L,
        checksumSha256 = "a".repeat(64),
        uploadIntent = "schema_staging_readonly",
        targetTable = null,
    )

    test("Fingerprint ist 64 hex-Zeichen lang (SHA-256)") {
        service.fingerprint(base()) shouldHaveLength 64
    }

    test("Identischer Input -> identischer Fingerprint (Determinismus)") {
        val attempt = base()
        service.fingerprint(attempt) shouldBe service.fingerprint(attempt.copy())
    }

    test("Tenant-Bind aendert den Fingerprint") {
        val a = service.fingerprint(base())
        val b = service.fingerprint(base().copy(tenantId = TenantId("umbrella")))
        (a == b) shouldBe false
    }

    test("Principal-Bind aendert den Fingerprint") {
        val a = service.fingerprint(base())
        val b = service.fingerprint(base().copy(callerId = PrincipalId("bob")))
        (a == b) shouldBe false
    }

    test("artifactKind, mimeType, sizeBytes, checksumSha256, uploadIntent gehen jeweils in den Hash ein") {
        val baseFp = service.fingerprint(base())
        val variants = listOf(
            base().copy(artifactKind = ArtifactKind.PROFILE),
            base().copy(mimeType = "application/sql"),
            base().copy(sizeBytes = 2048L),
            base().copy(checksumSha256 = "b".repeat(64)),
            base().copy(uploadIntent = "job_input"),
        )
        for (variant in variants) {
            (service.fingerprint(variant) == baseFp) shouldBe false
        }
    }

    test("targetTable=null und absent ist semantisch gleich (kein leeres-String-Bias)") {
        val a = service.fingerprint(base().copy(targetTable = null))
        // Direkt-Konstruktion mit Default-targetTable:
        val b = service.fingerprint(
            UploadInitApprovalAttempt(
                tenantId = TenantId("acme"),
                callerId = PrincipalId("alice"),
                artifactKind = ArtifactKind.SCHEMA,
                mimeType = "application/json",
                sizeBytes = 1024L,
                checksumSha256 = "a".repeat(64),
                uploadIntent = "schema_staging_readonly",
            ),
        )
        a shouldBe b
    }

    test("targetTable-Setzen aendert den Fingerprint") {
        val a = service.fingerprint(base().copy(targetTable = null))
        val b = service.fingerprint(base().copy(targetTable = "warehouse.events"))
        (a == b) shouldBe false
    }

    test("zwei unterschiedliche targetTable-Werte erzeugen unterschiedliche Fingerprints") {
        val a = service.fingerprint(base().copy(targetTable = "warehouse.events"))
        val b = service.fingerprint(base().copy(targetTable = "staging.events"))
        (a == b) shouldBe false
    }

    test("targetTable=null vs targetTable=\"\" sind verschiedene Fingerprints") {
        // Plan § 5.1: targetTable ist optional; ein leerer String ist
        // kein gueltiger CLI-Identifier. Trotzdem soll der Fingerprint
        // den semantischen Unterschied "Feld nicht gesetzt" vs "Feld
        // explizit leerstring" pinnen, damit Caller nicht stillschweigend
        // dieselbe Approval-Reservation teilen.
        val absent = service.fingerprint(base().copy(targetTable = null))
        val empty = service.fingerprint(base().copy(targetTable = ""))
        (absent == empty) shouldBe false
    }
})
