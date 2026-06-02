package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.server.application.ai.AiProviderConfig
import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.application.ai.AiProviderResult
import dev.dmigrate.server.application.ai.AiToolOrchestrator
import dev.dmigrate.server.application.ai.DefaultAiProviderRegistry
import dev.dmigrate.server.application.ai.NoOpAiProvider
import dev.dmigrate.server.application.ai.AiProviderError
import dev.dmigrate.server.application.ai.AiProviderPort
import dev.dmigrate.server.application.approval.ApprovalTokenFingerprint
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.audit.AuditFields
import dev.dmigrate.server.application.audit.prompt.DefaultPromptHygieneService
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
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
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
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
 * `procedure_transform_plan`-Handler.
 */
class ProcedureTransformPlanHandlerTest : FunSpec({

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
        providerCallLimit: Long = Long.MAX_VALUE,
    ) {
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val schemaStore = InMemorySchemaStore()
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
        val handler = ProcedureTransformPlanHandler(
            orchestrator = orchestrator,
            artifactStore = artifactStore,
            artifactContentStore = artifactContentStore,
            schemaStore = schemaStore,
            aiArtifactMetadataStore = metadataStore,
            providerRegistry = providerRegistry,
            hygieneService = hygieneService,
            policyService = policyService,
            approvalGrantService = approvalGrantService,
            quotaService = quotaService,
            clock = clock,
        )

        fun seedSchema(id: String) {
            schemaStore.register(
                dev.dmigrate.server.ports.SchemaIndexEntry(
                    schemaId = id,
                    tenantId = tenant,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.SCHEMAS, id),
                    artifactRef = "art-$id",
                    displayName = id,
                    createdAt = now,
                    expiresAt = now.plusSeconds(3600),
                ),
            )
        }

        fun seedArtifact(id: String) {
            artifactStore.save(
                ArtifactRecord(
                    managedArtifact = ManagedArtifact(
                        artifactId = id,
                        filename = "$id.bin",
                        contentType = "application/octet-stream",
                        sizeBytes = 16,
                        sha256 = "0".repeat(64),
                        createdAt = now,
                        expiresAt = now.plusSeconds(3600),
                    ),
                    kind = ArtifactKind.OTHER,
                    tenantId = tenant,
                    ownerPrincipalId = alice,
                    visibility = JobVisibility.TENANT,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, id),
                ),
            )
        }
    }

    fun ctx(
        args: String,
        principalCtx: PrincipalContext = principal,
        auditFields: AuditFields = AuditFields(),
    ) = ToolCallContext(
        name = "procedure_transform_plan",
        arguments = JsonParser.parseString(args),
        principal = principalCtx,
        requestId = "req-test",
        auditFields = auditFields,
    )

    test("LF-017 / LF-024 / LN-030 / LN-031: gueltiger Minimalaufruf -> Success-Envelope mit planRef + providerMeta + executionMeta") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """
                {
                  "approvalKey": "k-1",
                  "schemaRef": "dmigrate://tenants/acme/schemas/schema-1",
                  "procedureName": "process_orders",
                  "targetDialect": "POSTGRESQL"
                }
                """.trimIndent(),
            ),
        )
        val success = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val text = success.content.single().text!!
        val json = JsonParser.parseString(text).asJsonObject
        json.get("planRef").asString shouldStartWith "dmigrate://tenants/acme/artifacts/art-"
        json.get("planArtifactId").asString shouldStartWith "art-"
        json.getAsJsonObject("providerMeta").get("providerName").asString shouldBe "noop"
        json.getAsJsonObject("executionMeta").get("requestId").asString shouldBe "req-test"

        // AiArtifactMetadata wurde geschrieben.
        val artifactId = json.get("planArtifactId").asString
        val md = fx.metadataStore.findByArtifactId(tenant, artifactId)!!
        md.wireArtifactKind shouldBe AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN
        md.aiIntent shouldBe AiIntent.PROCEDURE_TRANSFORM_PLAN
        md.targetDialect shouldBe "POSTGRESQL"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: fehlender approvalKey -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"targetDialect":"POSTGRESQL","procedureRef":"proc-1"}""",
                ),
            )
        }.violations.first().field shouldBe "approvalKey"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: fehlende Source-Variante -> VALIDATION_ERROR(source)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx("""{"approvalKey":"k","targetDialect":"POSTGRESQL"}"""))
        }
        ex.violations.first().field shouldBe "source"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: mehrere Source-Varianten -> VALIDATION_ERROR(source)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """
                    {
                      "approvalKey":"k","targetDialect":"POSTGRESQL",
                      "procedureRef":"p","artifactRef":"dmigrate://tenants/acme/artifacts/a"
                    }
                    """.trimIndent(),
                ),
            )
        }
        ex.violations.first().field shouldBe "source"
    }

    test("schemaRef ohne procedureName -> VALIDATION_ERROR(procedureName)") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                        """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1"}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "procedureName"
    }

    test("Invalides URI-Format (artifactRef) -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","targetDialect":"POSTGRESQL","artifactRef":"not-a-uri"}""",
                ),
            )
        }
        ex.violations.first().field shouldBe "artifactRef"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: fehlender dmigrate:ai:execute-Scope -> ForbiddenPrincipalException") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val readOnlyPrincipal = principal.copy(scopes = setOf("dmigrate:read"))
        shouldThrow<ForbiddenPrincipalException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                        """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}""",
                    principalCtx = readOnlyPrincipal,
                ),
            )
        }
    }

    test("Tenant-Mismatch in schemaRef -> TENANT_SCOPE_DENIED (kein Existenz-Lookup)") {
        val fx = Fixture()
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/other/schemas/s1","procedureName":"p"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.TENANT_SCOPE_DENIED
    }

    test("Unbekanntes schemaRef -> RESOURCE_NOT_FOUND") {
        val fx = Fixture()
        // Schema NICHT geseedet.
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/missing","procedureName":"p"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.RESOURCE_NOT_FOUND
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: PolicyDenied -> POLICY_DENIED Wire-Envelope") {
        val fx = Fixture(policyDefault = PolicyEffect.Deny("policy:tool-blocked"))
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.POLICY_DENIED
        err.envelope.message shouldContain "policy:tool-blocked"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: PolicyRequiresApproval -> POLICY_REQUIRED mit aggregierten Challenge-Details") {
        // Provider darf bei POLICY_REQUIRED nicht aufgerufen werden — Provider-,
        // Quota- und Artefakt-Schritte folgen erst nach Policy-Allow/Grant.
        val providerCalls = AtomicInteger(0)
        val countingProvider = AiProviderPort {
            providerCalls.incrementAndGet()
            // Defensiv: ein Provider-Aufruf wäre LF-017 / LF-024 / LN-030 / LN-031-Fehler.
            error("provider must not be invoked when POLICY_REQUIRED")
        }
        val fx = Fixture(
            policyDefault = PolicyEffect.Challenge(
                requiredScopes = setOf("artifact.read", "ai.execute"),
                reasons = listOf("policy:manual-review", "policy:audit-required"),
            ),
            provider = countingProvider,
        )
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.POLICY_REQUIRED
        val details = err.envelope.details.associate { it.key to it.value }
        details["approvalRequestId"].isNullOrBlank() shouldBe false
        details["correlationKind"] shouldBe ApprovalCorrelationKind.APPROVAL_KEY.name
        details["correlationKey"] shouldBe "k"
        // LF-017 / LF-024 / LN-030 / LN-031: aggregierte Felder analog Job-/Upload-Pfade.
        details["requiredScopes"] shouldBe "ai.execute,artifact.read"
        details["reasons"] shouldBe "policy:manual-review|policy:audit-required"
        // Keine wiederholten Singular-Schlüssel.
        val keys = err.envelope.details.map { it.key }
        keys.contains("requiredScope") shouldBe false
        keys.contains("reason") shouldBe false
        providerCalls.get() shouldBe 0
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: approvalToken validiert durable Challenge und fuehrt zweiten Aufruf aus") {
        val fx = Fixture(policyDefault = PolicyEffect.Challenge(setOf("ai.execute")))
        fx.seedSchema("schema-1")
        val args = """{"approvalKey":"k-approved","targetDialect":"POSTGRESQL",""" +
            """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}"""
        val auditFields = AuditFields()
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
                toolName = ProcedureTransformPlanHandler.TOOL_NAME,
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
        approved.content.single().text.shouldContain("\"summary\":\"plan generated\"")
    }

    test("Unbekannter Provider -> FORBIDDEN_PRINCIPAL") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL","providerId":"openai",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.FORBIDDEN_PRINCIPAL
    }

    test("Modell ausserhalb allowedModels -> VALIDATION_ERROR (Wire)") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL","model":"gpt-9000",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.VALIDATION_ERROR
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: idempotenter Retry mit gleichem approvalKey + Payload -> selber planRef (Replay)") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val args = """{"approvalKey":"k-replay","targetDialect":"POSTGRESQL",""" +
            """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}"""
        val first = fx.handler.handle(ctx(args)) as ToolCallOutcome.Success
        val second = fx.handler.handle(ctx(args)) as ToolCallOutcome.Success
        val refOne = JsonParser.parseString(first.content.single().text!!).asJsonObject.get("planRef").asString
        val refTwo = JsonParser.parseString(second.content.single().text!!).asJsonObject.get("planRef").asString
        refTwo shouldBe refOne
        // summary kennzeichnet den replay
        val summaryTwo = JsonParser.parseString(second.content.single().text!!).asJsonObject.get("summary").asString
        summaryTwo shouldBe "replayed plan"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: gleicher approvalKey + abweichender Payload -> IDEMPOTENCY_CONFLICT") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        fx.seedSchema("schema-2")
        fx.handler.handle(
            ctx(
                """{"approvalKey":"k-conflict","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}""",
            ),
        )
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k-conflict","targetDialect":"MYSQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-2","procedureName":"p"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.IDEMPOTENCY_CONFLICT
    }

    test("Provider-Failure (TIMEOUT) -> OPERATION_TIMEOUT (retryable)") {
        val failing = AiProviderPort {
            AiProviderResult.Failure(
                error = AiProviderError.TIMEOUT,
                message = "simulated timeout",
            )
        }
        val fx = Fixture(provider = failing)
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k-timeout","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.OPERATION_TIMEOUT
    }

    test("Provider-Failure (TIMEOUT) retry mit gleichem Payload replayt ohne zweiten Provider-Aufruf") {
        val calls = AtomicInteger(0)
        val failing = AiProviderPort {
            calls.incrementAndGet()
            AiProviderResult.Failure(
                error = AiProviderError.TIMEOUT,
                message = "simulated timeout",
            )
        }
        val fx = Fixture(provider = failing)
        fx.seedSchema("schema-1")
        val args = """{"approvalKey":"k-timeout-replay","targetDialect":"POSTGRESQL",""" +
            """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}"""
        val first = fx.handler.handle(ctx(args)).shouldBeInstanceOf<ToolCallOutcome.Error>()
        val second = fx.handler.handle(ctx(args)).shouldBeInstanceOf<ToolCallOutcome.Error>()

        first.envelope.code shouldBe ToolErrorCode.OPERATION_TIMEOUT
        second.envelope.code shouldBe ToolErrorCode.OPERATION_TIMEOUT
        calls.get() shouldBe 1
    }

    test("Provider-Konfigurationsfehler gewinnt vor Hygiene und Quota") {
        val fx = Fixture(providerCallLimit = 0)
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k-provider-before-hygiene","targetDialect":"POSTGRESQL","providerId":"openai",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p",""" +
                    """"rules":{"note":"secret_key=abcdefghijklmnop"}}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.FORBIDDEN_PRINCIPAL
    }

    test("LF-017 / LF-024 / LN-030 / LN-031 Output-Hygiene: Provider liefert API-Key-Pattern -> PROMPT_HYGIENE_BLOCKED") {
        val maliciousProvider = AiProviderPort {
            // Ein bösartiger Provider, der einen api_key ins Output einschleust.
            AiProviderResult.Success(
                output = "here is the leaked api_key=sk_live_AKIA1234567890ABCDEF",
                outputFingerprint = "0".repeat(64),
                providerMeta = dev.dmigrate.server.application.ai.ProviderMeta(
                    providerName = "noop",
                    model = "noop:default",
                    modelVersion = "0.9.7",
                    requestId = null,
                ),
            )
        }
        val fx = Fixture(provider = maliciousProvider)
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k-out-hyg","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p"}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.PROMPT_HYGIENE_BLOCKED
        // LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: Fehlerdetails enthalten KEINE Secrets.
        err.envelope.message shouldContain "provider output blocked"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031 Input-Hygiene scannt verschachtelte rules im Payload") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k-rules-hyg","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1","procedureName":"p",""" +
                    """"rules":{"note":"secret_key=abcdefghijklmnop"}}""",
            ),
        )
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.PROMPT_HYGIENE_BLOCKED
        err.envelope.message shouldContain "secret pattern detected"
    }
})
