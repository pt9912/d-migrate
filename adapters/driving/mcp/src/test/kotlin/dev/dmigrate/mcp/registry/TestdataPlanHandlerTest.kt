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
import dev.dmigrate.server.core.ai.AiArtifactProvenance
import dev.dmigrate.server.core.ai.AiIntent
import dev.dmigrate.server.core.ai.AiWireArtifactKind
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase G § 5.6 + § 6 G.6 (G.6.f) — Akzeptanztests für den
 * `testdata_plan`-Handler.
 */
class TestdataPlanHandlerTest : FunSpec({

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
    ) {
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val schemaStore = InMemorySchemaStore()
        val profileStore = InMemoryProfileStore()
        val outcomeStore = InProcessAiToolOutcomeStore()
        val metadataStore = InProcessAiArtifactMetadataStore()
        val providerRegistry = DefaultAiProviderRegistry(
            configs = listOf(AiProviderConfig.noOpDefault()),
            ports = mapOf(AiProviderId.NOOP to NoOpAiProvider()),
        )
        val hygieneService = DefaultPromptHygieneService()
        val policyService = ConfiguredPolicyService(emptyList(), policyDefault)
        val quotaStore = dev.dmigrate.server.ports.memory.InMemoryQuotaStore()
        val quotaService = dev.dmigrate.server.application.quota.DefaultQuotaService(
            quotaStore,
        ) { providerCallLimit }
        val orchestrator = AiToolOrchestrator(outcomeStore)
        val handler = TestdataPlanHandler(
            orchestrator = orchestrator,
            artifactStore = artifactStore,
            artifactContentStore = artifactContentStore,
            schemaStore = schemaStore,
            profileStore = profileStore,
            aiArtifactMetadataStore = metadataStore,
            providerRegistry = providerRegistry,
            hygieneService = hygieneService,
            policyService = policyService,
            quotaService = quotaService,
            clock = clock,
        )

        fun seedSchema(id: String) {
            schemaStore.register(
                SchemaIndexEntry(
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

        fun seedProfile(id: String) {
            profileStore.save(
                ProfileIndexEntry(
                    profileId = id,
                    tenantId = tenant,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.PROFILES, id),
                    artifactRef = "art-$id",
                    displayName = id,
                    createdAt = now,
                    expiresAt = now.plusSeconds(3600),
                ),
            )
        }
    }

    fun ctx(args: String, principalCtx: PrincipalContext = principal) = ToolCallContext(
        name = "testdata_plan",
        arguments = JsonParser.parseString(args),
        principal = principalCtx,
        requestId = "req-test",
    )

    test("Plan §5.6 happy path: schemaRef + targetDialect -> Success mit testdataPlanArtifactId") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """
                {
                  "approvalKey":"k-td-1",
                  "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
                  "targetDialect":"POSTGRESQL"
                }
                """.trimIndent(),
            ),
        )
        val success = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val json = JsonParser.parseString(success.content.single().text!!).asJsonObject
        // Plan §5.6: Wire-Form heisst testdataPlanArtifactId +
        // testdataPlanResourceUri.
        json.get("testdataPlanArtifactId").asString shouldStartWith "art-"
        json.get("testdataPlanResourceUri").asString shouldStartWith "dmigrate://tenants/acme/artifacts/art-"

        val artifactId = json.get("testdataPlanArtifactId").asString
        val md = fx.metadataStore.findByArtifactId(tenant, artifactId)!!
        md.wireArtifactKind shouldBe AiWireArtifactKind.TESTDATA_PLAN
        md.aiIntent shouldBe AiIntent.TESTDATA_PLAN
        md.targetDialect shouldBe "POSTGRESQL"
        md.sourceRefs.size shouldBe 1
        md.sourceRefs.single().kind shouldBe ResourceKind.SCHEMAS
        // Provenance ist TestdataPlan mit getrennten Fingerprints.
        md.provenance.shouldBeInstanceOf<AiArtifactProvenance.TestdataPlan>()
    }

    test("Plan §5.6: optional profileRef wird in sourceRefs aufgenommen") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        fx.seedProfile("profile-1")
        val outcome = fx.handler.handle(
            ctx(
                """
                {
                  "approvalKey":"k-td-2",
                  "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
                  "profileRef":"dmigrate://tenants/acme/profiles/profile-1",
                  "targetDialect":"POSTGRESQL"
                }
                """.trimIndent(),
            ),
        )
        val success = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val artifactId = JsonParser.parseString(success.content.single().text!!)
            .asJsonObject.get("testdataPlanArtifactId").asString
        val md = fx.metadataStore.findByArtifactId(tenant, artifactId)!!
        md.sourceRefs.size shouldBe 2
        md.sourceRefs.map { it.kind } shouldBe listOf(ResourceKind.SCHEMAS, ResourceKind.PROFILES)
    }

    test("Plan §6 G.5: fehlender approvalKey -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"targetDialect":"POSTGRESQL","schemaRef":"dmigrate://tenants/acme/schemas/x"}""",
                ),
            )
        }.violations.first().field shouldBe "approvalKey"
    }

    test("Plan §6 G.5: fehlendes schemaRef -> VALIDATION_ERROR(schemaRef)") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx("""{"approvalKey":"k","targetDialect":"POSTGRESQL"}"""))
        }.violations.first().field shouldBe "schemaRef"
    }

    test("Plan §6 G.5: fehlendes targetDialect -> VALIDATION_ERROR(targetDialect)") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx("""{"approvalKey":"k","schemaRef":"dmigrate://tenants/acme/schemas/x"}"""),
            )
        }.violations.first().field shouldBe "targetDialect"
    }

    test("schemaRef in falschem ResourceKind -> VALIDATION_ERROR(schemaRef)") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                        """"schemaRef":"dmigrate://tenants/acme/artifacts/x"}""",
                ),
            )
        }.violations.first().field shouldBe "schemaRef"
    }

    test("profileRef in falschem ResourceKind -> VALIDATION_ERROR(profileRef)") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    """
                    {
                      "approvalKey":"k","targetDialect":"POSTGRESQL",
                      "schemaRef":"dmigrate://tenants/acme/schemas/x",
                      "profileRef":"dmigrate://tenants/acme/schemas/wrong"
                    }
                    """.trimIndent(),
                ),
            )
        }.violations.first().field shouldBe "profileRef"
    }

    test("Plan §6 G.6: fehlender dmigrate:ai:execute-Scope -> ForbiddenPrincipalException") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val readOnly = principal.copy(scopes = setOf("dmigrate:read"))
        shouldThrow<ForbiddenPrincipalException> {
            fx.handler.handle(
                ctx(
                    """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                        """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1"}""",
                    principalCtx = readOnly,
                ),
            )
        }
    }

    test("Tenant-Mismatch in schemaRef -> TENANT_SCOPE_DENIED") {
        val fx = Fixture()
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/other/schemas/s1"}""",
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>().envelope.code shouldBe
            ToolErrorCode.TENANT_SCOPE_DENIED
    }

    test("Unbekanntes schemaRef -> RESOURCE_NOT_FOUND") {
        val fx = Fixture()
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/missing"}""",
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>().envelope.code shouldBe
            ToolErrorCode.RESOURCE_NOT_FOUND
    }

    test("Unbekanntes profileRef -> RESOURCE_NOT_FOUND") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        // Profile NICHT geseedet.
        val outcome = fx.handler.handle(
            ctx(
                """
                {
                  "approvalKey":"k","targetDialect":"POSTGRESQL",
                  "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
                  "profileRef":"dmigrate://tenants/acme/profiles/missing"
                }
                """.trimIndent(),
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>().envelope.code shouldBe
            ToolErrorCode.RESOURCE_NOT_FOUND
    }

    test("Plan §6 G.6: PolicyDenied -> POLICY_DENIED") {
        val fx = Fixture(policyDefault = PolicyEffect.Deny("policy:testdata-blocked"))
        fx.seedSchema("schema-1")
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1"}""",
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>().envelope.code shouldBe
            ToolErrorCode.POLICY_DENIED
    }

    test("Plan §6 G.6: idempotenter Retry -> selber testdataPlanArtifactId") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        val args = """{"approvalKey":"k-replay","targetDialect":"POSTGRESQL",""" +
            """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1"}"""
        val first = fx.handler.handle(ctx(args)) as ToolCallOutcome.Success
        val second = fx.handler.handle(ctx(args)) as ToolCallOutcome.Success
        val refOne = JsonParser.parseString(first.content.single().text!!).asJsonObject
            .get("testdataPlanResourceUri").asString
        val refTwo = JsonParser.parseString(second.content.single().text!!).asJsonObject
            .get("testdataPlanResourceUri").asString
        refTwo shouldBe refOne
        JsonParser.parseString(second.content.single().text!!).asJsonObject
            .get("summary").asString shouldBe "replayed testdata plan"
    }

    test("Plan §6 G.6: gleicher approvalKey + abweichender Payload -> IDEMPOTENCY_CONFLICT") {
        val fx = Fixture()
        fx.seedSchema("schema-1")
        fx.seedSchema("schema-2")
        fx.handler.handle(
            ctx(
                """{"approvalKey":"k-conflict","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-1"}""",
            ),
        )
        val outcome = fx.handler.handle(
            ctx(
                """{"approvalKey":"k-conflict","targetDialect":"POSTGRESQL",""" +
                    """"schemaRef":"dmigrate://tenants/acme/schemas/schema-2"}""",
            ),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>().envelope.code shouldBe
            ToolErrorCode.IDEMPOTENCY_CONFLICT
    }
})
