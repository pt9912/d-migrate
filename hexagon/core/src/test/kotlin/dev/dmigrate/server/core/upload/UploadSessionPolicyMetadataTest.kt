package dev.dmigrate.server.core.upload

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — Policy-Init-Metadaten auf
 * [UploadSession]. Pin't, dass die drei neuen Felder
 * `approvalKey`/`approvalFingerprint`/`targetTable` per Default-
 * Konstruktion `null` sind (Bestands-Compat: read-only Schema-
 * Staging und LF-012 / LN-038-Session-Records ohne Policy-Pfad sind
 * unveraendert konstruierbar) und dass die Policy-Pfad-Konstruktion
 * alle drei Werte durabel haelt.
 */
class UploadSessionPolicyMetadataTest : FunSpec({

    val tenant = TenantId("acme")
    val owner = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-06T10:00:00Z")

    fun base(uploadSessionId: String, intent: String): UploadSession = UploadSession(
        uploadSessionId = uploadSessionId,
        tenantId = tenant,
        ownerPrincipalId = owner,
        resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, uploadSessionId),
        artifactKind = ArtifactKind.SCHEMA,
        mimeType = "application/json",
        sizeBytes = 1024,
        segmentTotal = 1,
        checksumSha256 = "deadbeef",
        uploadIntent = intent,
        state = UploadSessionState.ACTIVE,
        createdAt = now,
        updatedAt = now,
        idleTimeoutAt = now.plusSeconds(60),
        absoluteLeaseExpiresAt = now.plusSeconds(3_600),
    )

    test("Bestands-Session (read-only schema-staging) traegt approvalKey/Fingerprint/targetTable = null") {
        val session = base("up-readonly", intent = "schema_staging_readonly")
        session.approvalKey.shouldBeNull()
        session.approvalFingerprint.shouldBeNull()
        session.targetTable.shouldBeNull()
    }

    test("Policy-Init-Pfad (job_input) haelt approvalKey + approvalFingerprint + optional targetTable durabel") {
        val session = base("up-job-input", intent = "job_input").copy(
            approvalKey = "key-2026-05-06-acme-import",
            approvalFingerprint = "fp-sha256-acme-acme.export-1024-deadbeef",
            targetTable = "warehouse.events",
        )
        session.approvalKey shouldBe "key-2026-05-06-acme-import"
        session.approvalFingerprint shouldBe "fp-sha256-acme-acme.export-1024-deadbeef"
        session.targetTable shouldBe "warehouse.events"
    }

    test("Policy-Init ohne targetTable (CLI faellt auf data_import_start.table zurueck)") {
        val session = base("up-no-table", intent = "job_input").copy(
            approvalKey = "key-1",
            approvalFingerprint = "fp-1",
        )
        session.approvalKey shouldBe "key-1"
        session.approvalFingerprint shouldBe "fp-1"
        session.targetTable.shouldBeNull()
    }
})
