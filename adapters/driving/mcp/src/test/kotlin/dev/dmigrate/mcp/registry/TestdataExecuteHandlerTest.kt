package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.server.application.ai.AiProviderConfig
import dev.dmigrate.server.application.ai.AiProviderError
import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.application.ai.AiProviderPort
import dev.dmigrate.server.application.ai.AiProviderResult
import dev.dmigrate.server.application.ai.AiToolOrchestrator
import dev.dmigrate.server.application.ai.DefaultAiProviderRegistry
import dev.dmigrate.server.application.ai.NoOpAiProvider
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
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
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Follow-up AP 3 — Acceptance-Tests für [TestdataExecuteHandler].
 */
class TestdataExecuteHandlerTest : FunSpec({

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
        val quotaService = dev.dmigrate.server.application.quota.DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
        val orchestrator = AiToolOrchestrator(outcomeStore)
        val handler = TestdataExecuteHandler(
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

        fun seedPlanArtifact(
            artifactId: String = "art-plan-1",
            targetDialect: String = "POSTGRESQL",
            wireArtifactKind: String = AiWireArtifactKind.TESTDATA_PLAN,
            aiIntent: String = AiIntent.TESTDATA_PLAN,
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
            val provenance = if (aiIntent == AiIntent.TESTDATA_PLAN) {
                AiArtifactProvenance.TestdataPlan(
                    promptFingerprint = "0".repeat(64),
                    payloadFingerprint = "1".repeat(64),
                )
            } else {
                AiArtifactProvenance.Plan(
                    promptFingerprint = "0".repeat(64),
                    payloadFingerprint = "1".repeat(64),
                )
            }
            metadataStore.save(
                AiArtifactMetadata(
                    tenantId = tenant,
                    artifactId = artifactId,
                    resourceUri = resourceUri,
                    wireArtifactKind = wireArtifactKind,
                    aiIntent = aiIntent,
                    originToolName = aiIntent,
                    ownerPrincipalId = alice,
                    policyIntent = "ai.execute.$aiIntent",
                    sourceRefs = listOf(
                        ServerResourceUri(tenant, ResourceKind.SCHEMAS, "schema-1"),
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
        name = "testdata_execute",
        arguments = JsonParser.parseString(args),
        principal = principalCtx,
        requestId = "req-test",
    )

    test("Happy-Path Single-Table: planArtifactId + targetTable -> Success mit testdataArtifactId") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx(
                """
                {
                  "approvalKey":"k-1",
                  "planArtifactId":"art-plan-1",
                  "targetDialect":"POSTGRESQL",
                  "targetTable":"users",
                  "outputFormat":"csv"
                }
                """.trimIndent(),
            ),
        )
        val success = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val json = JsonParser.parseString(success.content.single().text!!).asJsonObject
        json.get("testdataArtifactId").asString shouldStartWith "art-"
        json.get("testdataResourceUri").asString shouldStartWith "dmigrate://tenants/acme/artifacts/art-"

        // Output trägt sowohl ArtifactUploadMetadata (Pfad-A) als auch
        // AiArtifactMetadata.
        val artifactId = json.get("testdataArtifactId").asString
        val record = fx.artifactStore.findById(tenant, artifactId)!!
        record.kind shouldBe ArtifactKind.UPLOAD_INPUT
        record.uploadMetadata!!.uploadIntent shouldBe ArtifactUploadInitHandler.INTENT_JOB_INPUT
        record.uploadMetadata!!.wireArtifactKind shouldBe AiWireArtifactKind.GENERATED_TESTDATA
        record.uploadMetadata!!.targetTable shouldBe "users"

        val md = fx.metadataStore.findByArtifactId(tenant, artifactId)!!
        md.aiIntent shouldBe AiIntent.TESTDATA_EXECUTE
        md.wireArtifactKind shouldBe AiWireArtifactKind.GENERATED_TESTDATA
        val prov = md.provenance.shouldBeInstanceOf<AiArtifactProvenance.TestdataExecute>()
        prov.planRef shouldBe ServerResourceUri(tenant, ResourceKind.ARTIFACTS, "art-plan-1")
    }

    test("Happy-Path Bundle: tables + bundleFormat -> SEED_DATA_BUNDLE Wire-Marker") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx(
                """
                {
                  "approvalKey":"k-bundle",
                  "planArtifactId":"art-plan-1",
                  "targetDialect":"POSTGRESQL",
                  "tables":["users","orders"],
                  "bundleFormat":"seed-bundle.v1.zip"
                }
                """.trimIndent(),
            ),
        )
        val success = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val artifactId = JsonParser.parseString(success.content.single().text!!)
            .asJsonObject.get("testdataArtifactId").asString
        val record = fx.artifactStore.findById(tenant, artifactId)!!
        record.uploadMetadata!!.wireArtifactKind shouldBe AiWireArtifactKind.SEED_DATA_BUNDLE
        record.uploadMetadata!!.bundleFormat shouldBe "seed-bundle.v1.zip"
        record.uploadMetadata!!.targetTables shouldBe listOf("users", "orders")
    }

    test("Idempotenter Retry mit gleichem Payload -> selber Artefakt + replay-Marker") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val args = """
            {
              "approvalKey":"k-replay",
              "planArtifactId":"art-plan-1",
              "targetDialect":"POSTGRESQL",
              "targetTable":"users"
            }
        """.trimIndent()
        val first = fx.handler.handle(ctx(args)) as ToolCallOutcome.Success
        val second = fx.handler.handle(ctx(args)) as ToolCallOutcome.Success
        val refOne = JsonParser.parseString(first.content.single().text!!)
            .asJsonObject.get("testdataResourceUri").asString
        val refTwo = JsonParser.parseString(second.content.single().text!!)
            .asJsonObject.get("testdataResourceUri").asString
        refTwo shouldBe refOne
        JsonParser.parseString(second.content.single().text!!)
            .asJsonObject.get("summary").asString shouldBe "replayed testdata output"
    }

    test("Gleicher approvalKey + abweichender Payload -> IDEMPOTENCY_CONFLICT") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        fx.handler.handle(
            ctx(
                """{"approvalKey":"k-x","planArtifactId":"art-plan-1","targetDialect":"POSTGRESQL","targetTable":"users"}""",
            ),
        )
        val second = fx.handler.handle(
            ctx(
                """{"approvalKey":"k-x","planArtifactId":"art-plan-1","targetDialect":"POSTGRESQL","targetTable":"orders"}""",
            ),
        )
        second.shouldBeInstanceOf<ToolCallOutcome.Error>().envelope.code shouldBe ToolErrorCode.IDEMPOTENCY_CONFLICT
    }

    test("Fehlender approvalKey -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx("""{"targetDialect":"POSTGRESQL","planArtifactId":"x","targetTable":"users"}"""),
            )
        }.violations.first().field shouldBe "approvalKey"
    }

    test("Weder planRef noch planArtifactId -> VALIDATION_ERROR(plan)") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx("""{"approvalKey":"k","targetDialect":"POSTGRESQL","targetTable":"users"}"""),
            )
        }.violations.first().field shouldBe "plan"
    }

    test("Beide planRef und planArtifactId -> VALIDATION_ERROR(plan)") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","planRef":"dmigrate://tenants/acme/artifacts/p",""" +
                        """"planArtifactId":"p","targetDialect":"POSTGRESQL","targetTable":"u"}""",
                ),
            )
        }.violations.first().field shouldBe "plan"
    }

    test("targetTable und tables zusammen -> VALIDATION_ERROR(target)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","planArtifactId":"p","targetDialect":"POSTGRESQL",""" +
                        """"targetTable":"u","tables":["a","b"],"bundleFormat":"seed-bundle.v1.zip"}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "target"
    }

    test("tables ohne bundleFormat -> VALIDATION_ERROR(bundleFormat)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","planArtifactId":"p","targetDialect":"POSTGRESQL","tables":["a","b"]}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "bundleFormat"
    }

    test("Leere tables-Liste -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","planArtifactId":"p","targetDialect":"POSTGRESQL",""" +
                        """"tables":[],"bundleFormat":"seed-bundle.v1.zip"}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "tables"
    }

    test("Doppelte tables-Einträge -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","planArtifactId":"p","targetDialect":"POSTGRESQL",""" +
                        """"tables":["a","a"],"bundleFormat":"seed-bundle.v1.zip"}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "tables"
    }

    test("Unbekannter bundleFormat -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","planArtifactId":"p","targetDialect":"POSTGRESQL","tables":["a"],"bundleFormat":"my-bundle"}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "bundleFormat"
    }

    test("invalides planRef-URI -> VALIDATION_ERROR(planRef)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","planRef":"not-a-uri","targetDialect":"POSTGRESQL","targetTable":"u"}""",
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
                    """{"approvalKey":"k","planRef":"dmigrate://tenants/acme/schemas/x","targetDialect":"POSTGRESQL","targetTable":"u"}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "planRef"
    }

    test("Fehlender dmigrate:ai:execute-Scope -> ForbiddenPrincipalException") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val readOnly = principal.copy(scopes = setOf("dmigrate:read"))
        shouldThrow<ForbiddenPrincipalException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","planArtifactId":"art-plan-1","targetDialect":"POSTGRESQL","targetTable":"u"}""",
                    principalCtx = readOnly,
                ),
            )
        }
    }

    test("planRef mit fremdem Tenant -> TENANT_SCOPE_DENIED") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","planRef":"dmigrate://tenants/other/artifacts/art-plan-1",""" +
                    """"targetDialect":"POSTGRESQL","targetTable":"u"}""",
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
            .envelope.code shouldBe ToolErrorCode.TENANT_SCOPE_DENIED
    }

    test("planArtifactId nicht vorhanden -> RESOURCE_NOT_FOUND") {
        val fx = Fixture()
        // KEIN seed
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","planArtifactId":"missing","targetDialect":"POSTGRESQL","targetTable":"u"}""",
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
            .envelope.code shouldBe ToolErrorCode.RESOURCE_NOT_FOUND
    }

    test("Plan-Artefakt ohne Ai-Metadata (orphan) -> RESOURCE_NOT_FOUND") {
        val fx = Fixture()
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
            ctx("""{"approvalKey":"k","planArtifactId":"art-orphan","targetDialect":"POSTGRESQL","targetTable":"u"}"""),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
            .envelope.code shouldBe ToolErrorCode.RESOURCE_NOT_FOUND
    }

    test("Plan-Artefakt mit falschem wireArtifactKind -> VALIDATION_ERROR") {
        val fx = Fixture()
        fx.seedPlanArtifact(
            wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
            aiIntent = AiIntent.PROCEDURE_TRANSFORM_PLAN,
        )
        val outcome = fx.handler.handle(
            ctx("""{"approvalKey":"k","planArtifactId":"art-plan-1","targetDialect":"POSTGRESQL","targetTable":"u"}"""),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.VALIDATION_ERROR
        err.envelope.message shouldContain "wireArtifactKind"
    }

    test("Plan-Artefakt mit anderem targetDialect -> VALIDATION_ERROR") {
        val fx = Fixture()
        fx.seedPlanArtifact(targetDialect = "POSTGRESQL")
        val outcome = fx.handler.handle(
            ctx("""{"approvalKey":"k","planArtifactId":"art-plan-1","targetDialect":"MYSQL","targetTable":"u"}"""),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.VALIDATION_ERROR
        err.envelope.message shouldContain "targetDialect"
    }

    test("PolicyDenied -> POLICY_DENIED") {
        val fx = Fixture(policyDefault = PolicyEffect.Deny("policy:testdata-execute-blocked"))
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","planArtifactId":"art-plan-1","targetDialect":"POSTGRESQL","targetTable":"u"}""",
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
            .envelope.code shouldBe ToolErrorCode.POLICY_DENIED
    }

    test("PolicyChallenge -> POLICY_REQUIRED mit aggregierten Details (Follow-up AP 1)") {
        val fx = Fixture(
            policyDefault = PolicyEffect.Challenge(
                requiredScopes = setOf("ai.execute"),
                reasons = listOf("policy:manual-review"),
            ),
        )
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx("""{"approvalKey":"k","planArtifactId":"art-plan-1","targetDialect":"POSTGRESQL","targetTable":"u"}"""),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.POLICY_REQUIRED
        val details = err.envelope.details.associate { it.key to it.value }
        details["correlationKind"] shouldBe ApprovalCorrelationKind.APPROVAL_KEY.name
        details["requiredScopes"] shouldBe "ai.execute"
        details["reasons"] shouldBe "policy:manual-review"
    }

    test("Provider-Fehler (TIMEOUT) -> OPERATION_TIMEOUT") {
        val failing = AiProviderPort {
            AiProviderResult.Failure(error = AiProviderError.TIMEOUT, message = "timeout")
        }
        val fx = Fixture(provider = failing)
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx("""{"approvalKey":"k","planArtifactId":"art-plan-1","targetDialect":"POSTGRESQL","targetTable":"u"}"""),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
            .envelope.code shouldBe ToolErrorCode.OPERATION_TIMEOUT
    }

    test("Output mit Secret-Pattern -> PROMPT_HYGIENE_BLOCKED") {
        val malicious = AiProviderPort {
            AiProviderResult.Success(
                output = "leaked api_key=sk_live_AKIA1234567890ABCDEF",
                outputFingerprint = "0".repeat(64),
                providerMeta = dev.dmigrate.server.application.ai.ProviderMeta(
                    providerName = "noop",
                    model = "noop:default",
                    modelVersion = "0.9.6",
                    requestId = null,
                ),
            )
        }
        val fx = Fixture(provider = malicious)
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx("""{"approvalKey":"k","planArtifactId":"art-plan-1","targetDialect":"POSTGRESQL","targetTable":"u"}"""),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
            .envelope.code shouldBe ToolErrorCode.PROMPT_HYGIENE_BLOCKED
    }

    test("approvalToken ohne vorherige Challenge -> POLICY_DENIED") {
        val fx = Fixture()
        fx.seedPlanArtifact()
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","planArtifactId":"art-plan-1","targetDialect":"POSTGRESQL",""" +
                    """"targetTable":"u","approvalToken":"orphan"}""",
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
            .envelope.code shouldBe ToolErrorCode.POLICY_DENIED
    }
})
