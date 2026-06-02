package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.server.application.ai.AiProviderConfig
import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.application.ai.AiProviderPort
import dev.dmigrate.server.application.ai.AiToolOrchestrator
import dev.dmigrate.server.application.ai.DefaultAiProviderRegistry
import dev.dmigrate.server.application.ai.NoOpAiProvider
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.ApprovalTokenFingerprint
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.audit.prompt.DefaultPromptHygieneService
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.core.ai.AiArtifactMetadata
import dev.dmigrate.server.core.ai.AiArtifactProvenance
import dev.dmigrate.server.core.ai.AiIntent
import dev.dmigrate.server.core.ai.AiWireArtifactKind
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
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
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — Akzeptanztests fuer den
 * `procedure_transform_execute`-Handler.
 *
 * Schwerpunkt: Plan-Provenance-Validierung (LF-017 / LF-024 / LN-030 / LN-031 Z. 783-799),
 * weil das die Execute-spezifische Pflicht gegenüber dem Plan-Pfad ist.
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

    class Fixture(
        policyDefault: PolicyEffect = PolicyEffect.Allow,
        providerCallLimit: Long = Long.MAX_VALUE,
        provider: AiProviderPort = NoOpAiProvider(),
    ) {
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val outcomeStore = InProcessAiToolOutcomeStore()
        val metadataStore = InProcessAiArtifactMetadataStore()
        val providerRegistry = DefaultAiProviderRegistry(
            configs = listOf(AiProviderConfig.noOpDefault()),
            ports = mapOf(AiProviderId.NOOP to provider),
        )
        val hygieneService = DefaultPromptHygieneService()
        val policyService = ConfiguredPolicyService(emptyList(), policyDefault)
        val approvalGrantStore = InMemoryApprovalGrantStore()
        val approvalGrantService = DefaultApprovalGrantService(
            approvalGrantStore,
            ApprovalGrantValidator(),
        )
        val quotaStore = dev.dmigrate.server.ports.memory.InMemoryQuotaStore()
        val quotaService = dev.dmigrate.server.application.quota.DefaultQuotaService(
            quotaStore,
        ) { providerCallLimit }
        val orchestrator = AiToolOrchestrator(outcomeStore)
        val handler = ProcedureTransformExecuteHandler(
            orchestrator = orchestrator,
            artifactStore = artifactStore,
            artifactContentStore = artifactContentStore,
            aiArtifactMetadataStore = metadataStore,
            providerRegistry = providerRegistry,
            hygieneService = hygieneService,
            policyService = policyService,
            approvalGrantService = approvalGrantService,
            quotaService = quotaService,
            clock = clock,
        )

        /**
         * Seedet ein freigegebenes Plan-Artefakt mit der korrekten
         * `wireArtifactKind=procedure-transform-plan`-Provenance,
         * sodass der Execute-Handler eine valide Planquelle hat.
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
                    modelVersion = "0.9.7",
                    outputFingerprint = "a".repeat(64),
                    createdAt = now,
                ),
            )
            return resourceUri
        }
    }

    fun ctx(
        args: String,
        principalCtx: PrincipalContext = principal,
        auditFields: dev.dmigrate.server.application.audit.AuditFields =
            dev.dmigrate.server.application.audit.AuditFields(),
    ) = ToolCallContext(
        name = "procedure_transform_execute",
        arguments = JsonParser.parseString(args),
        principal = principalCtx,
        requestId = "req-test",
        auditFields = auditFields,
    )

    test("LF-017 / LF-024 / LN-030 / LN-031 happy path: planArtifactId zeigt auf gueltigen Plan -> Success mit targetArtifactId") {
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
        // LF-017 / LF-024 / LN-030 / LN-031: Wire-Form heisst targetArtifactId (nicht planRef).
        json.get("targetArtifactId").asString shouldStartWith "art-"
        json.get("targetResourceUri").asString shouldStartWith "dmigrate://tenants/acme/artifacts/art-"

        // Output-Artefakt traegt korrekte AiArtifactMetadata.
        val targetId = json.get("targetArtifactId").asString
        val md = fx.metadataStore.findByArtifactId(tenant, targetId)!!
        md.wireArtifactKind shouldBe AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT
        md.aiIntent shouldBe AiIntent.PROCEDURE_TRANSFORM_EXECUTE
        // LF-017 / LF-024 / LN-030 / LN-031 Z. 794-799: Source-Refs werden aus Plan-Provenance
        // uebernommen, plus planRef selbst.
        md.sourceRefs.size shouldBe 2
        md.sourceRefs.map { it.kind } shouldBe listOf(ResourceKind.SCHEMAS, ResourceKind.ARTIFACTS)
        // Provenance ist Execute mit Plan-Bindung.
        val executeProvenance = md.provenance.shouldBeInstanceOf<AiArtifactProvenance.Execute>()
        executeProvenance.planRef shouldBe ServerResourceUri(tenant, ResourceKind.ARTIFACTS, "art-plan-1")
        executeProvenance.planArtifactFingerprint shouldBe "deadbeef".repeat(8)
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: planRef (anstatt planArtifactId) -> Success") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031: fehlender approvalKey -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"targetDialect":"POSTGRESQL","planArtifactId":"art-plan-1"}""",
                ),
            )
        }.violations.first().field shouldBe "approvalKey"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: fehlende Plan-Source -> VALIDATION_ERROR(plan)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx("""{"approvalKey":"k","targetDialect":"POSTGRESQL"}"""))
        }
        ex.violations.first().field shouldBe "plan"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: planRef + planArtifactId zusammen -> VALIDATION_ERROR(plan)") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031: fehlender dmigrate:ai:execute-Scope -> ForbiddenPrincipalException") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031: planRef mit fremdem Tenant -> TENANT_SCOPE_DENIED") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031: ArtifactRecord ohne AiArtifactMetadata (orphaned) -> RESOURCE_NOT_FOUND") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031 Z. 794-797: falscher wireArtifactKind (z.B. testdata-plan) -> VALIDATION_ERROR") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031: targetDialect-Mismatch zu Plan-Provenance -> VALIDATION_ERROR") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031: PolicyDenied -> POLICY_DENIED") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031: idempotenter Retry -> selber targetArtifactId (Replay)") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031: gleicher approvalKey + abweichender Payload -> IDEMPOTENCY_CONFLICT") {
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

    test("LF-017 / LF-024 / LN-030 / LN-031: PolicyRequiresApproval -> POLICY_REQUIRED mit aggregierten Challenge-Details") {
        val providerCalls = AtomicInteger(0)
        val countingProvider = AiProviderPort {
            providerCalls.incrementAndGet()
            error("provider must not be invoked when POLICY_REQUIRED")
        }
        val fx = Fixture(
            policyDefault = PolicyEffect.Challenge(
                requiredScopes = setOf("artifact.read", "ai.execute"),
                reasons = listOf("policy:manual-review", "policy:audit-required"),
            ),
            provider = countingProvider,
        )
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL","planArtifactId":"art-plan-1"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.POLICY_REQUIRED
        val details = err.envelope.details.associate { it.key to it.value }
        details["approvalRequestId"].isNullOrBlank() shouldBe false
        details["correlationKind"] shouldBe ApprovalCorrelationKind.APPROVAL_KEY.name
        details["correlationKey"] shouldBe "k"
        details["requiredScopes"] shouldBe "ai.execute,artifact.read"
        details["reasons"] shouldBe "policy:manual-review|policy:audit-required"
        val keys = err.envelope.details.map { it.key }
        keys.contains("requiredScope") shouldBe false
        keys.contains("reason") shouldBe false
        providerCalls.get() shouldBe 0
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: approvalToken validiert durable Challenge und fuehrt zweiten Aufruf aus") {
        val fx = Fixture(
            policyDefault = PolicyEffect.Challenge(setOf("ai.execute")),
        )
        fx.seedPlanArtifact()
        val args = """{"approvalKey":"k-approved","targetDialect":"POSTGRESQL","planArtifactId":"art-plan-1"}"""
        val auditFields = dev.dmigrate.server.application.audit.AuditFields()
        val first = fx.handler.handle(ctx(args, auditFields = auditFields))
            .shouldBeInstanceOf<ToolCallOutcome.Error>()
        first.envelope.code shouldBe ToolErrorCode.POLICY_REQUIRED
        val approvalRequestId = first.envelope.details.single { it.key == "approvalRequestId" }.value

        fx.approvalGrantStore.save(
            ApprovalGrant(
                approvalRequestId = approvalRequestId,
                correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
                correlationKey = "k-approved",
                approvalTokenFingerprint = ApprovalTokenFingerprint.compute("token-ok"),
                toolName = ProcedureTransformExecuteHandler.TOOL_NAME,
                tenantId = tenant,
                callerId = alice,
                payloadFingerprint = auditFields.payloadFingerprint!!,
                issuerFingerprint = "issuer-test",
                issuedScopes = setOf("ai.execute"),
                grantSource = "test",
                expiresAt = now.plusSeconds(3600),
            ),
        )

        val approvedArgs = args.dropLast(1) + ""","approvalToken":"token-ok"}"""
        val approved = fx.handler.handle(ctx(approvedArgs))
            .shouldBeInstanceOf<ToolCallOutcome.Success>()
        approved.content.single().text!! shouldContain "\"summary\":\"transform output generated\""
    }
})
