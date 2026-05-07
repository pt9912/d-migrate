package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.server.application.ai.AiProviderConfig
import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.application.ai.AiToolOrchestrator
import dev.dmigrate.server.application.ai.DefaultAiProviderRegistry
import dev.dmigrate.server.application.ai.NoOpAiProvider
import dev.dmigrate.server.application.audit.prompt.DefaultPromptHygieneService
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.core.ai.AiArtifactMetadata
import dev.dmigrate.server.core.ai.AiArtifactProvenance
import dev.dmigrate.server.core.ai.AiIntent
import dev.dmigrate.server.core.ai.AiWireArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase G § 5.5 + § 6 G.6 (G.6.e) — Akzeptanztests fuer den
 * `procedure_transform_execute`-Handler.
 *
 * Schwerpunkt: Plan-Provenance-Validierung (Plan §5.5 Z. 783-799),
 * weil das die Pflicht ist, die G.6.e von G.6.d unterscheidet.
 */
class ProcedureTransformExecuteHandlerTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-07T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    val principal = PrincipalContext(
        principalId = alice,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:ai:execute"),
        isAdmin = false,
        auditSubject = "alice",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    class Fixture(policyDefault: PolicyEffect = PolicyEffect.Allow) {
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val outcomeStore = InProcessAiToolOutcomeStore()
        val metadataStore = InProcessAiArtifactMetadataStore()
        val providerRegistry = DefaultAiProviderRegistry(
            configs = listOf(AiProviderConfig.noOpDefault()),
            ports = mapOf(AiProviderId.NOOP to NoOpAiProvider()),
        )
        val hygieneService = DefaultPromptHygieneService()
        val policyService = ConfiguredPolicyService(emptyList(), policyDefault)
        val orchestrator = AiToolOrchestrator(outcomeStore)
        val handler = ProcedureTransformExecuteHandler(
            orchestrator = orchestrator,
            artifactStore = artifactStore,
            artifactContentStore = artifactContentStore,
            aiArtifactMetadataStore = metadataStore,
            providerRegistry = providerRegistry,
            hygieneService = hygieneService,
            policyService = policyService,
            clock = clock,
        )

        /**
         * Seedet ein freigegebenes Plan-Artefakt mit der korrekten
         * `wireArtifactKind=procedure-transform-plan`-Provenance,
         * sodass der Execute-Handler eine valide Plan-Quelle hat.
         */
        fun seedPlanArtifact(
            artifactId: String = "art-plan-1",
            targetDialect: String = "POSTGRESQL",
            sourceRefId: String = "schema-1",
            wireArtifactKind: String = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
            aiIntent: String = AiIntent.PROCEDURE_TRANSFORM_PLAN,
            provenance: AiArtifactProvenance = AiArtifactProvenance.Plan(
                promptFingerprint = "0".repeat(64),
                payloadFingerprint = "1".repeat(64),
            ),
        ): ServerResourceUri {
            val resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, artifactId)
            artifactStore.save(
                ArtifactRecord(
                    managedArtifact = ManagedArtifact(
                        artifactId = artifactId,
                        filename = "$artifactId.plan.json",
                        contentType = "application/json",
                        sizeBytes = 100,
                        sha256 = "deadbeef".repeat(8),
                        createdAt = now,
                        expiresAt = now.plusSeconds(86_400),
                    ),
                    kind = ArtifactKind.OTHER,
                    tenantId = tenant,
                    ownerPrincipalId = alice,
                    visibility = JobVisibility.TENANT,
                    resourceUri = resourceUri,
                ),
            )
            metadataStore.save(
                AiArtifactMetadata(
                    tenantId = tenant,
                    artifactId = artifactId,
                    resourceUri = resourceUri,
                    wireArtifactKind = wireArtifactKind,
                    aiIntent = aiIntent,
                    originToolName = if (aiIntent == AiIntent.PROCEDURE_TRANSFORM_PLAN) {
                        "procedure_transform_plan"
                    } else {
                        aiIntent
                    },
                    ownerPrincipalId = alice,
                    policyIntent = "ai.execute.procedure_transform_plan",
                    sourceRefs = listOf(
                        ServerResourceUri(tenant, ResourceKind.SCHEMAS, sourceRefId),
                    ),
                    targetDialect = targetDialect,
                    provenance = provenance,
                    providerName = "noop",
                    model = "noop:default",
                    modelVersion = "0.9.6",
                    outputFingerprint = "a".repeat(64),
                    createdAt = now,
                ),
            )
            return resourceUri
        }
    }

    fun ctx(args: String, principalCtx: PrincipalContext = principal) = ToolCallContext(
        name = "procedure_transform_execute",
        arguments = JsonParser.parseString(args),
        principal = principalCtx,
        requestId = "req-test",
    )

    test("Plan §5.5 happy path: planArtifactId zeigt auf gueltigen Plan -> Success mit targetArtifactId") {
        val fx = Fixture()
        fx.seedPlanArtifact(artifactId = "art-plan-1")
        val outcome = fx.handler.handle(
            ctx(
                """
                {
                  "approvalKey": "k-exec-1",
                  "planArtifactId": "art-plan-1",
                  "targetDialect": "POSTGRESQL"
                }
                """.trimIndent(),
            ),
        )
        val success = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val json = JsonParser.parseString(success.content.single().text!!).asJsonObject
        // Plan §5.5: Wire-Form heisst targetArtifactId (nicht planRef).
        json.get("targetArtifactId").asString shouldStartWith "art-"
        json.get("targetResourceUri").asString shouldStartWith "dmigrate://tenants/acme/artifacts/art-"

        // Output-Artefakt traegt korrekte AiArtifactMetadata.
        val targetId = json.get("targetArtifactId").asString
        val md = fx.metadataStore.findByArtifactId(tenant, targetId)!!
        md.wireArtifactKind shouldBe AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT
        md.aiIntent shouldBe AiIntent.PROCEDURE_TRANSFORM_EXECUTE
        // Plan §5.5 Z. 794-799: Source-Refs werden aus Plan-Provenance
        // uebernommen, plus planRef selbst.
        md.sourceRefs.size shouldBe 2
        md.sourceRefs.map { it.kind } shouldBe listOf(ResourceKind.SCHEMAS, ResourceKind.ARTIFACTS)
        // Provenance ist Execute mit Plan-Bindung.
        val executeProvenance = md.provenance.shouldBeInstanceOf<AiArtifactProvenance.Execute>()
        executeProvenance.planRef shouldBe ServerResourceUri(tenant, ResourceKind.ARTIFACTS, "art-plan-1")
        executeProvenance.planArtifactFingerprint shouldBe "deadbeef".repeat(8)
    }

    test("Plan §5.5: planRef (anstatt planArtifactId) -> Success") {
        val fx = Fixture()
        fx.seedPlanArtifact(artifactId = "art-plan-2")
        val outcome = fx.handler.handle(
            ctx(
                """
                {
                  "approvalKey":"k-exec-ref",
                  "planRef":"dmigrate://tenants/acme/artifacts/art-plan-2",
                  "targetDialect":"POSTGRESQL"
                }
                """.trimIndent(),
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("Plan §6 G.5: fehlender approvalKey -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"targetDialect":"POSTGRESQL","planArtifactId":"art-plan-1"}""",
                ),
            )
        }.violations.first().field shouldBe "approvalKey"
    }

    test("Plan §6 G.5: fehlende Plan-Source -> VALIDATION_ERROR(plan)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx("""{"approvalKey":"k","targetDialect":"POSTGRESQL"}"""))
        }
        ex.violations.first().field shouldBe "plan"
    }

    test("Plan §6 G.5: planRef + planArtifactId zusammen -> VALIDATION_ERROR(plan)") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """
                    {
                      "approvalKey":"k","targetDialect":"POSTGRESQL",
                      "planArtifactId":"art-plan-1",
                      "planRef":"dmigrate://tenants/acme/artifacts/art-plan-1"
                    }
                    """.trimIndent(),
                ),
            )
        }
        ex.violations.first().field shouldBe "plan"
    }

    test("planRef mit invalider URI -> VALIDATION_ERROR(planRef)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","targetDialect":"POSTGRESQL","planRef":"not-a-uri"}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "planRef"
    }

    test("planRef in falschem ResourceKind -> VALIDATION_ERROR(planRef)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                        """"planRef":"dmigrate://tenants/acme/schemas/s1"}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "planRef"
    }

    test("Plan §6 G.6: fehlender dmigrate:ai:execute-Scope -> ForbiddenPrincipalException") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val readOnly = principal.copy(scopes = setOf("dmigrate:read"))
        shouldThrow<ForbiddenPrincipalException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","targetDialect":"POSTGRESQL","planArtifactId":"art-plan-1"}""",
                    principalCtx = readOnly,
                ),
            )
        }
    }

    test("Plan §5.5: planRef mit fremdem Tenant -> TENANT_SCOPE_DENIED") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                    """"planRef":"dmigrate://tenants/other/artifacts/art-plan-1"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.TENANT_SCOPE_DENIED
    }

    test("planArtifactId zeigt auf nicht existierenden Artefakt -> RESOURCE_NOT_FOUND") {
        val fx = Fixture()
        // KEIN seedPlanArtifact.
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL","planArtifactId":"art-missing"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.RESOURCE_NOT_FOUND
    }

    test("Plan §5.5: ArtifactRecord ohne AiArtifactMetadata (orphaned) -> RESOURCE_NOT_FOUND") {
        val fx = Fixture()
        // Nur den ArtifactRecord seeden — KEINE Metadata.
        fx.artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = "art-orphan",
                    filename = "art-orphan.bin",
                    contentType = "application/octet-stream",
                    sizeBytes = 10,
                    sha256 = "0".repeat(64),
                    createdAt = now,
                    expiresAt = now.plusSeconds(3600),
                ),
                kind = ArtifactKind.OTHER,
                tenantId = tenant,
                ownerPrincipalId = alice,
                visibility = JobVisibility.TENANT,
                resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, "art-orphan"),
            ),
        )
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL","planArtifactId":"art-orphan"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.RESOURCE_NOT_FOUND
    }

    test("Plan §5.5 Z. 794-797: falscher wireArtifactKind (z.B. testdata-plan) -> VALIDATION_ERROR") {
        val fx = Fixture()
        fx.seedPlanArtifact(
            wireArtifactKind = AiWireArtifactKind.TESTDATA_PLAN,
            aiIntent = AiIntent.TESTDATA_PLAN,
            provenance = AiArtifactProvenance.TestdataPlan(
                promptFingerprint = "0".repeat(64),
                payloadFingerprint = "1".repeat(64),
            ),
        )
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL","planArtifactId":"art-plan-1"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.VALIDATION_ERROR
        err.envelope.message.contains("wireArtifactKind") shouldBe true
    }

    test("Plan §5.5: targetDialect-Mismatch zu Plan-Provenance -> VALIDATION_ERROR") {
        val fx = Fixture()
        fx.seedPlanArtifact(targetDialect = "POSTGRESQL")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"MYSQL","planArtifactId":"art-plan-1"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.VALIDATION_ERROR
        err.envelope.message.contains("targetDialect") shouldBe true
    }

    test("Plan §6 G.6: PolicyDenied -> POLICY_DENIED") {
        val fx = Fixture(policyDefault = PolicyEffect.Deny("policy:execute-blocked"))
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL","planArtifactId":"art-plan-1"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.POLICY_DENIED
    }

    test("Plan §6 G.6: idempotenter Retry -> selber targetArtifactId (Replay)") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val args = """{"approvalKey":"k-replay","targetDialect":"POSTGRESQL","planArtifactId":"art-plan-1"}"""
        val first = fx.handler.handle(ctx(args)) as ToolCallOutcome.Success
        val second = fx.handler.handle(ctx(args)) as ToolCallOutcome.Success
        val refOne = JsonParser.parseString(first.content.single().text!!).asJsonObject
            .get("targetResourceUri").asString
        val refTwo = JsonParser.parseString(second.content.single().text!!).asJsonObject
            .get("targetResourceUri").asString
        refTwo shouldBe refOne
        val summaryTwo = JsonParser.parseString(second.content.single().text!!).asJsonObject
            .get("summary").asString
        summaryTwo shouldBe "replayed transform output"
    }

    test("Plan §6 G.6: gleicher approvalKey + abweichender Payload -> IDEMPOTENCY_CONFLICT") {
        val fx = Fixture()
        fx.seedPlanArtifact(artifactId = "art-plan-1")
        fx.seedPlanArtifact(artifactId = "art-plan-2")
        fx.handler.handle(
            ctx(
                """{"approvalKey":"k-conflict","targetDialect":"POSTGRESQL","planArtifactId":"art-plan-1"}""",
            ),
        )
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k-conflict","targetDialect":"POSTGRESQL","planArtifactId":"art-plan-2"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.IDEMPOTENCY_CONFLICT
    }
})
