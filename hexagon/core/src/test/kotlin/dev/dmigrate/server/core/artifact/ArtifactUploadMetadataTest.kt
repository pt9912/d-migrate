package dev.dmigrate.server.core.artifact

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Construction-coverage for [ArtifactUploadMetadata]. Pure data
 * carrier with two construction shapes — single-file uploads (most
 * fields default-null) and seed-bundle uploads (every nullable field
 * populated). Pinning both forms exercises every default-vs-supplied
 * branch so the auto-generated equals/hashCode/copy/toString count
 * toward kover line coverage. Sibling [ArtifactRecord]'s logic
 * coverage lives in `ArtifactRecordTest`.
 */
class ArtifactUploadMetadataTest : FunSpec({

    test("single-file form uses the expected default-null fields") {
        val md = ArtifactUploadMetadata(
            artifactId = "ar-1",
            resourceUri = "dmigrate://tenants/t-a/artifacts/ar-1",
            uploadIntent = "job_input",
            wireArtifactKind = "csv",
            contentType = "text/csv",
            sourceUploadSessionId = "us-1",
            sizeBytes = 256L,
            sha256 = "deadbeef",
        )
        md.format shouldBe null
        md.targetTable shouldBe null
        md.targetTables shouldBe null
        md.policyFingerprint shouldBe null
        md.bundleFormat shouldBe null
        md.manifestPath shouldBe null
        md.manifestFingerprint shouldBe null
    }

    test("seed-bundle form populates every nullable field") {
        val md = ArtifactUploadMetadata(
            artifactId = "ar-2",
            resourceUri = "dmigrate://tenants/t-a/artifacts/ar-2",
            uploadIntent = "job_input",
            wireArtifactKind = "seed-data-bundle",
            contentType = "application/zip",
            format = "seed-bundle.v1",
            targetTable = null,
            targetTables = listOf("users", "orders"),
            sourceUploadSessionId = "us-2",
            policyFingerprint = "pfp-1",
            sizeBytes = 4096L,
            sha256 = "c0ffee",
            bundleFormat = "seed-bundle.v1.zip",
            manifestPath = "manifest.json",
            manifestFingerprint = "mfp-1",
        )
        md.bundleFormat shouldBe "seed-bundle.v1.zip"
        md.manifestPath shouldBe "manifest.json"
        md.manifestFingerprint shouldBe "mfp-1"
        md.targetTables shouldBe listOf("users", "orders")
        md.format shouldBe "seed-bundle.v1"
        md.policyFingerprint shouldBe "pfp-1"
    }

    test("equals + copy follow data-class semantics") {
        val a = ArtifactUploadMetadata(
            artifactId = "x",
            resourceUri = "dmigrate://tenants/t/artifacts/x",
            uploadIntent = "job_input",
            wireArtifactKind = "csv",
            contentType = "text/csv",
            sourceUploadSessionId = "us",
            sizeBytes = 1L,
            sha256 = "h",
        )
        val b = a.copy()
        a shouldBe b
        a.copy(artifactId = "y").artifactId shouldBe "y"
    }
})
