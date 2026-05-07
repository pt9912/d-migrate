package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.ai.AiArtifactMetadata
import dev.dmigrate.server.core.ai.AiArtifactProvenance
import dev.dmigrate.server.core.ai.AiIntent
import dev.dmigrate.server.core.ai.AiWireArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SaveAiArtifactMetadataOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * Phase G § 5.4 + § 6 G.6 (G.6.b 2/2) — Akzeptanz für den
 * In-Process-Provenance-Store.
 */
class InProcessAiArtifactMetadataStoreTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-07T12:00:00Z")

    fun planMetadata(
        artifactId: String = "art-plan-1",
        promptFp: String = "0".repeat(64),
        payloadFp: String = "1".repeat(64),
    ): AiArtifactMetadata = AiArtifactMetadata(
        tenantId = tenant,
        artifactId = artifactId,
        resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, artifactId),
        wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
        aiIntent = AiIntent.PROCEDURE_TRANSFORM_PLAN,
        originToolName = "procedure_transform_plan",
        ownerPrincipalId = alice,
        policyIntent = "ai.execute.procedure_transform_plan",
        sourceRefs = listOf(
            ServerResourceUri(tenant, ResourceKind.SCHEMAS, "warehouse-v1"),
        ),
        targetDialect = "POSTGRESQL",
        provenance = AiArtifactProvenance.Plan(
            promptFingerprint = promptFp,
            payloadFingerprint = payloadFp,
        ),
        providerName = "noop",
        model = "noop:default",
        modelVersion = "0.9.6",
        outputFingerprint = "a".repeat(64),
        createdAt = now,
    )

    test("save liefert Saved fuer neuen Eintrag und macht Lookups verfuegbar") {
        val store = InProcessAiArtifactMetadataStore()
        val md = planMetadata()
        val outcome = store.save(md)
        outcome.shouldBeInstanceOf<SaveAiArtifactMetadataOutcome.Saved>()
        outcome.metadata shouldBe md
        store.findByArtifactId(tenant, md.artifactId) shouldBe md
        store.findByResourceUri(tenant, md.resourceUri) shouldBe md
    }

    test("save ist idempotent: gleicher Eintrag liefert AlreadyExists") {
        val store = InProcessAiArtifactMetadataStore()
        val md = planMetadata()
        store.save(md)
        val replay = store.save(md)
        replay.shouldBeInstanceOf<SaveAiArtifactMetadataOutcome.AlreadyExists>()
        replay.metadata shouldBe md
        // Lookups bleiben unveraendert.
        store.findByArtifactId(tenant, md.artifactId) shouldBe md
    }

    test("save mit abweichenden Metadaten unter gleicher (tenantId, artifactId) -> Conflict") {
        val store = InProcessAiArtifactMetadataStore()
        val first = planMetadata(promptFp = "a".repeat(64))
        val second = first.copy(
            provenance = AiArtifactProvenance.Plan(
                promptFingerprint = "b".repeat(64),
                payloadFingerprint = first.provenance.payloadFingerprint,
            ),
        )
        store.save(first)
        val outcome = store.save(second)
        val conflict = outcome.shouldBeInstanceOf<SaveAiArtifactMetadataOutcome.Conflict>()
        conflict.existing shouldBe first
        conflict.attempted shouldBe second
        // Existing-Eintrag wird nicht ueberschrieben (Plan §5.4: Provenance ist immutable).
        store.findByArtifactId(tenant, first.artifactId) shouldBe first
    }

    test("findByArtifactId in falschem Tenant -> null") {
        val store = InProcessAiArtifactMetadataStore()
        val md = planMetadata()
        store.save(md)
        store.findByArtifactId(TenantId("other"), md.artifactId) shouldBe null
    }

    test("findByResourceUri in falschem Tenant -> null") {
        val store = InProcessAiArtifactMetadataStore()
        val md = planMetadata()
        store.save(md)
        store.findByResourceUri(TenantId("other"), md.resourceUri) shouldBe null
    }

    test("deleteByArtifactId entfernt sowohl Primaer- als auch URI-Lookup") {
        val store = InProcessAiArtifactMetadataStore()
        val md = planMetadata()
        store.save(md)
        store.deleteByArtifactId(tenant, md.artifactId) shouldBe true
        store.findByArtifactId(tenant, md.artifactId) shouldBe null
        store.findByResourceUri(tenant, md.resourceUri) shouldBe null
    }

    test("deleteByArtifactId fuer nicht existierenden Eintrag liefert false (idempotent)") {
        val store = InProcessAiArtifactMetadataStore()
        store.deleteByArtifactId(tenant, "art-missing") shouldBe false
    }

    test("AiArtifactMetadata Konstruktor: aiIntent ↔ wireArtifactKind ↔ provenance werden konsistent erzwungen") {
        // wireArtifactKind passt nicht zu aiIntent.
        shouldThrow<IllegalArgumentException> {
            planMetadata().copy(
                wireArtifactKind = AiWireArtifactKind.TESTDATA_PLAN,
            )
        }
        // Provenance-Typ passt nicht zu aiIntent.
        shouldThrow<IllegalArgumentException> {
            planMetadata().copy(
                provenance = AiArtifactProvenance.TestdataPlan(
                    promptFingerprint = "0".repeat(64),
                    payloadFingerprint = "1".repeat(64),
                ),
            )
        }
    }

    test("AiArtifactMetadata Konstruktor: resourceUri muss zum tenantId+artifactId passen") {
        shouldThrow<IllegalArgumentException> {
            planMetadata().copy(
                resourceUri = ServerResourceUri(TenantId("other"), ResourceKind.ARTIFACTS, "art-plan-1"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            planMetadata().copy(
                resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, "different-id"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            planMetadata().copy(
                resourceUri = ServerResourceUri(tenant, ResourceKind.SCHEMAS, "art-plan-1"),
            )
        }
    }

    test("AiArtifactMetadata Konstruktor: unbekannter wireArtifactKind/aiIntent wird abgelehnt") {
        shouldThrow<IllegalArgumentException> {
            planMetadata().copy(wireArtifactKind = "custom-kind")
        }
        shouldThrow<IllegalArgumentException> {
            planMetadata().copy(
                aiIntent = "custom_intent",
                originToolName = "custom_intent",
                wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
            )
        }
    }

    test("Execute-Provenance braucht planRef + planArtifactFingerprint") {
        val executeMd = planMetadata(artifactId = "art-exec-1").copy(
            wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT,
            aiIntent = AiIntent.PROCEDURE_TRANSFORM_EXECUTE,
            originToolName = "procedure_transform_execute",
            policyIntent = "ai.execute.procedure_transform_execute",
            resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, "art-exec-1"),
            provenance = AiArtifactProvenance.Execute(
                promptFingerprint = "0".repeat(64),
                payloadFingerprint = "1".repeat(64),
                planRef = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, "art-plan-1"),
                planArtifactFingerprint = "2".repeat(64),
            ),
        )
        // Sollte ohne Exception bauen — Smoke fuer Execute-Pfad.
        val store = InProcessAiArtifactMetadataStore()
        store.save(executeMd).shouldBeInstanceOf<SaveAiArtifactMetadataOutcome.Saved>()
    }

    test("AiWireArtifactKind.ALL und AiIntent.ALL sind die kanonischen Whitelist-Quellen") {
        AiWireArtifactKind.ALL shouldBe setOf(
            "procedure-transform-plan",
            "procedure-transform-output",
            "testdata-plan",
        )
        AiIntent.ALL shouldBe setOf(
            "procedure_transform_plan",
            "procedure_transform_execute",
            "testdata_plan",
        )
    }
})
