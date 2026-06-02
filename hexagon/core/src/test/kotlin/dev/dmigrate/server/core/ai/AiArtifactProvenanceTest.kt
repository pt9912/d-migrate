package dev.dmigrate.server.core.ai

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec

/**
 * Init-block coverage for [AiArtifactProvenance] and its four
 * sealed sub-types. Each variant validates `*Fingerprint`-fields as
 * 64-char hex SHA-256; `Execute` and `TestdataExecute` additionally
 * pin a `planArtifactFingerprint`.
 */
class AiArtifactProvenanceTest : FunSpec({

    val fp = "a".repeat(64)
    val planRef = ServerResourceUri(
        tenantId = TenantId("t-1"),
        kind = ResourceKind.ARTIFACTS,
        id = "plan-1",
    )

    // ── Plan ──

    test("Plan accepts valid 64-char fingerprints") {
        AiArtifactProvenance.Plan(promptFingerprint = fp, payloadFingerprint = fp)
    }

    test("Plan rejects non-64-char promptFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.Plan(promptFingerprint = "short", payloadFingerprint = fp)
        }
    }

    test("Plan rejects non-64-char payloadFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.Plan(promptFingerprint = fp, payloadFingerprint = "short")
        }
    }

    // ── Execute ──

    test("Execute accepts valid 64-char fingerprints + planArtifactFingerprint") {
        AiArtifactProvenance.Execute(
            promptFingerprint = fp,
            payloadFingerprint = fp,
            planRef = planRef,
            planArtifactFingerprint = fp,
        )
    }

    test("Execute rejects non-64-char promptFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.Execute(
                promptFingerprint = "short",
                payloadFingerprint = fp,
                planRef = planRef,
                planArtifactFingerprint = fp,
            )
        }
    }

    test("Execute rejects non-64-char payloadFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.Execute(
                promptFingerprint = fp,
                payloadFingerprint = "short",
                planRef = planRef,
                planArtifactFingerprint = fp,
            )
        }
    }

    test("Execute rejects non-64-char planArtifactFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.Execute(
                promptFingerprint = fp,
                payloadFingerprint = fp,
                planRef = planRef,
                planArtifactFingerprint = "short",
            )
        }
    }

    // ── TestdataPlan ──

    test("TestdataPlan accepts valid 64-char fingerprints") {
        AiArtifactProvenance.TestdataPlan(promptFingerprint = fp, payloadFingerprint = fp)
    }

    test("TestdataPlan rejects non-64-char promptFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.TestdataPlan(promptFingerprint = "short", payloadFingerprint = fp)
        }
    }

    test("TestdataPlan rejects non-64-char payloadFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.TestdataPlan(promptFingerprint = fp, payloadFingerprint = "short")
        }
    }

    // ── TestdataExecute ──

    test("TestdataExecute accepts valid 64-char fingerprints + planArtifactFingerprint") {
        AiArtifactProvenance.TestdataExecute(
            promptFingerprint = fp,
            payloadFingerprint = fp,
            planRef = planRef,
            planArtifactFingerprint = fp,
        )
    }

    test("TestdataExecute rejects non-64-char promptFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.TestdataExecute(
                promptFingerprint = "short",
                payloadFingerprint = fp,
                planRef = planRef,
                planArtifactFingerprint = fp,
            )
        }
    }

    test("TestdataExecute rejects non-64-char payloadFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.TestdataExecute(
                promptFingerprint = fp,
                payloadFingerprint = "short",
                planRef = planRef,
                planArtifactFingerprint = fp,
            )
        }
    }

    test("TestdataExecute rejects non-64-char planArtifactFingerprint") {
        shouldThrow<IllegalArgumentException> {
            AiArtifactProvenance.TestdataExecute(
                promptFingerprint = fp,
                payloadFingerprint = fp,
                planRef = planRef,
                planArtifactFingerprint = "short",
            )
        }
    }
})
