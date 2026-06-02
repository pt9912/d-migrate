package dev.dmigrate.server.core.ai

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.time.Instant

/**
 * Init-block coverage for [AiArtifactMetadata]. The class enforces:
 *
 * - Non-blank string fields (artifactId, originToolName, policyIntent,
 *   targetDialect, providerName, model).
 * - Tenant + resource-URI invariants (`resourceUri.tenantId == tenantId`,
 *   `resourceUri.kind == ARTIFACTS`, `resourceUri.id == artifactId`).
 * - `wireArtifactKind`/`aiIntent` membership in their constant `ALL` sets.
 * - 64-char hex `outputFingerprint`.
 * - Optional `modelVersion` non-blank-or-null.
 * - Cross-field intent ↔ wireKind ↔ provenance mapping
 *   (`validateIntentKindProvenance`).
 */
class AiArtifactMetadataTest : FunSpec({

    val tenantId = TenantId("t-1")
    val artifactId = "ar-1"
    val resourceUri = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, artifactId)
    val ownerPrincipalId = PrincipalId("p-1")
    val fp64 = "a".repeat(64)
    val fp64b = "b".repeat(64)
    val now = Instant.parse("2026-05-10T12:00:00Z")

    fun planMetadata(
        artifactId: String = "ar-1",
        resourceUri: ServerResourceUri = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, artifactId),
        wireArtifactKind: String = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
        aiIntent: String = AiIntent.PROCEDURE_TRANSFORM_PLAN,
        originToolName: String = "procedure_transform_plan",
        policyIntent: String = "ai.execute.procedure_transform_plan",
        targetDialect: String = "POSTGRESQL",
        provenance: AiArtifactProvenance = AiArtifactProvenance.Plan(fp64, fp64b),
        providerName: String = "anthropic",
        model: String = "claude",
        modelVersion: String? = null,
        outputFingerprint: String = fp64,
    ) = AiArtifactMetadata(
        tenantId = tenantId,
        artifactId = artifactId,
        resourceUri = resourceUri,
        wireArtifactKind = wireArtifactKind,
        aiIntent = aiIntent,
        originToolName = originToolName,
        ownerPrincipalId = ownerPrincipalId,
        policyIntent = policyIntent,
        sourceRefs = emptyList(),
        targetDialect = targetDialect,
        provenance = provenance,
        providerName = providerName,
        model = model,
        modelVersion = modelVersion,
        outputFingerprint = outputFingerprint,
        createdAt = now,
    )

    test("happy-path Plan metadata constructs cleanly") {
        planMetadata()
    }

    test("rejects blank artifactId") {
        shouldThrow<IllegalArgumentException> { planMetadata(artifactId = "") }
            .message shouldContain "artifactId"
    }

    test("rejects resourceUri whose tenantId differs from metadata tenantId") {
        val mismatchedUri = ServerResourceUri(TenantId("t-2"), ResourceKind.ARTIFACTS, artifactId)
        shouldThrow<IllegalArgumentException> {
            planMetadata(resourceUri = mismatchedUri)
        }.message shouldContain "resourceUri.tenantId must match"
    }

    test("rejects resourceUri whose kind isn't ARTIFACTS") {
        val wrongKind = ServerResourceUri(tenantId, ResourceKind.JOBS, artifactId)
        shouldThrow<IllegalArgumentException> {
            planMetadata(resourceUri = wrongKind)
        }.message shouldContain "resourceUri.kind must be ARTIFACTS"
    }

    test("rejects resourceUri whose id differs from artifactId") {
        val wrongId = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, "different")
        shouldThrow<IllegalArgumentException> {
            planMetadata(resourceUri = wrongId)
        }.message shouldContain "resourceUri.id must match artifactId"
    }

    test("rejects wireArtifactKind outside AiWireArtifactKind.ALL") {
        shouldThrow<IllegalArgumentException> {
            planMetadata(
                wireArtifactKind = "made-up-kind",
                aiIntent = AiIntent.PROCEDURE_TRANSFORM_PLAN,
            )
        }.message shouldContain "wireArtifactKind must be one of"
    }

    test("rejects aiIntent outside AiIntent.ALL") {
        shouldThrow<IllegalArgumentException> {
            planMetadata(aiIntent = "made-up-intent")
        }.message shouldContain "aiIntent must be one of"
    }

    test("rejects blank originToolName / policyIntent / targetDialect / providerName / model") {
        shouldThrow<IllegalArgumentException> { planMetadata(originToolName = "") }
        shouldThrow<IllegalArgumentException> { planMetadata(policyIntent = "") }
        shouldThrow<IllegalArgumentException> { planMetadata(targetDialect = "") }
        shouldThrow<IllegalArgumentException> { planMetadata(providerName = "") }
        shouldThrow<IllegalArgumentException> { planMetadata(model = "") }
    }

    test("modelVersion must be null or non-blank") {
        planMetadata(modelVersion = null)         // ok
        planMetadata(modelVersion = "v3")         // ok
        shouldThrow<IllegalArgumentException> { planMetadata(modelVersion = "") }
    }

    test("rejects outputFingerprint that isn't 64-char hex") {
        shouldThrow<IllegalArgumentException> {
            planMetadata(outputFingerprint = "short")
        }.message shouldContain "outputFingerprint must be a 64-char hex"
    }

    // ── validateIntentKindProvenance: cross-field consistency ──

    test("PROCEDURE_TRANSFORM_PLAN intent rejects mismatched wireKind") {
        shouldThrow<IllegalArgumentException> {
            planMetadata(
                aiIntent = AiIntent.PROCEDURE_TRANSFORM_PLAN,
                wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT,
            )
        }.message shouldContain "wireArtifactKind=${AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN}"
    }

    test("PROCEDURE_TRANSFORM_PLAN intent rejects non-Plan provenance") {
        shouldThrow<IllegalArgumentException> {
            planMetadata(
                aiIntent = AiIntent.PROCEDURE_TRANSFORM_PLAN,
                wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
                provenance = AiArtifactProvenance.TestdataPlan(fp64, fp64b),
            )
        }.message shouldContain "Plan provenance"
    }

    test("PROCEDURE_TRANSFORM_EXECUTE intent / wireKind / Execute provenance round-trips") {
        val exec = AiArtifactProvenance.Execute(
            promptFingerprint = fp64,
            payloadFingerprint = fp64b,
            planRef = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, "plan-1"),
            planArtifactFingerprint = fp64,
        )
        planMetadata(
            aiIntent = AiIntent.PROCEDURE_TRANSFORM_EXECUTE,
            wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT,
            provenance = exec,
        )
        // Mismatched wireKind for the Execute intent.
        shouldThrow<IllegalArgumentException> {
            planMetadata(
                aiIntent = AiIntent.PROCEDURE_TRANSFORM_EXECUTE,
                wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
                provenance = exec,
            )
        }
        // Non-Execute provenance for the Execute intent.
        shouldThrow<IllegalArgumentException> {
            planMetadata(
                aiIntent = AiIntent.PROCEDURE_TRANSFORM_EXECUTE,
                wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT,
                provenance = AiArtifactProvenance.Plan(fp64, fp64b),
            )
        }
    }

    test("TESTDATA_PLAN intent / wireKind / TestdataPlan provenance round-trips") {
        val td = AiArtifactProvenance.TestdataPlan(fp64, fp64b)
        planMetadata(
            aiIntent = AiIntent.TESTDATA_PLAN,
            wireArtifactKind = AiWireArtifactKind.TESTDATA_PLAN,
            provenance = td,
        )
        shouldThrow<IllegalArgumentException> {
            planMetadata(
                aiIntent = AiIntent.TESTDATA_PLAN,
                wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
                provenance = td,
            )
        }
        shouldThrow<IllegalArgumentException> {
            planMetadata(
                aiIntent = AiIntent.TESTDATA_PLAN,
                wireArtifactKind = AiWireArtifactKind.TESTDATA_PLAN,
                provenance = AiArtifactProvenance.Plan(fp64, fp64b),
            )
        }
    }

    test("TESTDATA_EXECUTE intent accepts both GENERATED_TESTDATA and SEED_DATA_BUNDLE wireKinds") {
        val tdExec = AiArtifactProvenance.TestdataExecute(
            promptFingerprint = fp64,
            payloadFingerprint = fp64b,
            planRef = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, "td-plan-1"),
            planArtifactFingerprint = fp64,
        )
        planMetadata(
            aiIntent = AiIntent.TESTDATA_EXECUTE,
            wireArtifactKind = AiWireArtifactKind.GENERATED_TESTDATA,
            provenance = tdExec,
        )
        planMetadata(
            aiIntent = AiIntent.TESTDATA_EXECUTE,
            wireArtifactKind = AiWireArtifactKind.SEED_DATA_BUNDLE,
            provenance = tdExec,
        )
        // Other wireKinds are rejected.
        shouldThrow<IllegalArgumentException> {
            planMetadata(
                aiIntent = AiIntent.TESTDATA_EXECUTE,
                wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
                provenance = tdExec,
            )
        }
        // Non-TestdataExecute provenance for the Execute intent.
        shouldThrow<IllegalArgumentException> {
            planMetadata(
                aiIntent = AiIntent.TESTDATA_EXECUTE,
                wireArtifactKind = AiWireArtifactKind.GENERATED_TESTDATA,
                provenance = AiArtifactProvenance.Plan(fp64, fp64b),
            )
        }
    }
})
