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
import dev.dmigrate.server.application.audit.prompt.DefaultPromptHygieneService
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
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

/**
 * Phase G § 6 G.6 (G.6.d) — Akzeptanztests fuer den
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

    fun ctx(args: String, principalCtx: PrincipalContext = principal) = ToolCallContext(
        name = "procedure_transform_plan",
        arguments = JsonParser.parseString(args),
        principal = principalCtx,
        requestId = "req-test",
    )

    test("Plan §6 G.5: gueltiger Minimalaufruf -> Success-Envelope mit planRef + providerMeta + executionMeta") {
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

    test("Plan §6 G.5: fehlender approvalKey -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"targetDialect":"POSTGRESQL","procedureRef":"proc-1"}""",
                ),
            )
        }.violations.first().field shouldBe "approvalKey"
    }

    test("Plan §6 G.5: fehlende Source-Variante -> VALIDATION_ERROR(source)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx("""{"approvalKey":"k","targetDialect":"POSTGRESQL"}"""))
        }
        ex.violations.first().field shouldBe "source"
    }

    test("Plan §6 G.5: mehrere Source-Varianten -> VALIDATION_ERROR(source)") {
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

    test("Plan §6 G.6: fehlender dmigrate:ai:execute-Scope -> ForbiddenPrincipalException") {
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

    test("Plan §6 G.6: PolicyDenied -> POLICY_DENIED Wire-Envelope") {
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

    test("Plan §6 G.6: PolicyRequiresApproval -> POLICY_REQUIRED (Challenge-Felder als G.6.e/f-Carve-out)") {
        val fx = Fixture(
            policyDefault = PolicyEffect.Challenge(setOf("ai.execute")),
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

    test("Plan §6 G.6: idempotenter Retry mit gleichem approvalKey + Payload -> selber planRef (Replay)") {
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

    test("Plan §6 G.6: gleicher approvalKey + abweichender Payload -> IDEMPOTENCY_CONFLICT") {
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

    test("Plan §7.4 Output-Hygiene: Provider liefert API-Key-Pattern -> PROMPT_HYGIENE_BLOCKED") {
        val maliciousProvider = AiProviderPort {
            // Ein bösartiger Provider, der einen api_key ins Output einschleust.
            AiProviderResult.Success(
                output = "here is the leaked api_key=sk_live_AKIA1234567890ABCDEF",
                outputFingerprint = "0".repeat(64),
                providerMeta = dev.dmigrate.server.application.ai.ProviderMeta(
                    providerName = "noop",
                    model = "noop:default",
                    modelVersion = "0.9.6",
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
        // Plan §6 G.4 Akzeptanz: Fehlerdetails enthalten KEINE Secrets.
        err.envelope.message shouldContain "provider output blocked"
    }
})
