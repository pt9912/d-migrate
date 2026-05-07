package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.server.application.ai.AiProviderConfig
import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.application.ai.AiProviderPort
import dev.dmigrate.server.application.ai.AiToolOrchestrator
import dev.dmigrate.server.application.ai.DefaultAiProviderRegistry
import dev.dmigrate.server.application.ai.NoOpAiProvider
import dev.dmigrate.server.application.audit.AuditFields
import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.application.audit.prompt.DefaultPromptHygieneService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase G § 6 G.8 — Provider-Quota + Audit-Goldens.
 *
 * Pin't:
 * - Plan §6 G.8: Provider-Quota=0 -> RATE_LIMITED + Provider wird
 *   NICHT aufgerufen (kein Provider-Client, kein Secret-Read).
 * - Plan §6 G.8: nach Quota-Verletzung kann ein nachgelagerter
 *   Aufruf mit Quota-Granted normal durchlaufen.
 * - Plan §6 G.8: Audit-Felder enthalten Provider-/Modell-Metadaten
 *   im Erfolgsfall.
 * - Plan §6 G.8: Audit-Scrubbing ueber resourceRefs entfernt
 *   bekannte Secret-Pattern (JDBC-Passwoerter, Bearer-Tokens).
 * - Plan §6 G.8: Hygiene-Block ist auditierbar (payloadFingerprint
 *   gesetzt, kein providerMeta in resourceRefs).
 */
class AiToolQuotaAndAuditTest : FunSpec({

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

    /**
     * Spy-Provider, der zaehlt, wie oft er invoked wurde — Plan §6
     * G.8 Akzeptanz: bei Quota-RATE_LIMITED muss der Counter 0
     * bleiben.
     */
    class CountingProvider : AiProviderPort {
        val invokeCount = AtomicInteger(0)
        private val noOp = NoOpAiProvider()
        override fun invoke(
            request: dev.dmigrate.server.application.ai.AiProviderRequest,
        ): dev.dmigrate.server.application.ai.AiProviderResult {
            invokeCount.incrementAndGet()
            return noOp.invoke(request)
        }
    }

    fun handler(
        spy: CountingProvider,
        providerCallLimit: Long,
        auditFields: AuditFields = AuditFields(),
    ): Pair<ProcedureTransformPlanHandler, Map<String, Any>> {
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val schemaStore = InMemorySchemaStore()
        val outcomeStore = InProcessAiToolOutcomeStore()
        val metadataStore = InProcessAiArtifactMetadataStore()
        val providerRegistry = DefaultAiProviderRegistry(
            configs = listOf(AiProviderConfig.noOpDefault()),
            ports = mapOf(AiProviderId.NOOP to spy),
        )
        val hygieneService = DefaultPromptHygieneService()
        val policyService = ConfiguredPolicyService(emptyList(), PolicyEffect.Allow)
        val quotaStore = InMemoryQuotaStore()
        val quotaService = DefaultQuotaService(quotaStore) { providerCallLimit }
        val orchestrator = AiToolOrchestrator(outcomeStore)

        // Schema seeden, damit das Tool eine valide Source hat.
        schemaStore.register(
            SchemaIndexEntry(
                schemaId = "schema-1",
                tenantId = tenant,
                resourceUri = ServerResourceUri(tenant, ResourceKind.SCHEMAS, "schema-1"),
                artifactRef = "art-schema-1",
                displayName = "schema-1",
                createdAt = now,
                expiresAt = now.plusSeconds(3600),
            ),
        )

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
        return handler to mapOf(
            "auditFields" to auditFields,
            "metadataStore" to metadataStore,
        )
    }

    fun ctx(args: String, auditFields: AuditFields = AuditFields()) = ToolCallContext(
        name = "procedure_transform_plan",
        arguments = JsonParser.parseString(args),
        principal = principal,
        requestId = "req-test",
        auditFields = auditFields,
    )

    val validArgs = """
        {
          "approvalKey":"k-1",
          "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
          "procedureName":"process_orders",
          "targetDialect":"POSTGRESQL"
        }
    """.trimIndent()

    test("Plan §6 G.8 Akzeptanz: Provider-Quota=0 -> RATE_LIMITED, Provider-Counter 0") {
        val spy = CountingProvider()
        val (h, _) = handler(spy, providerCallLimit = 0)

        val outcome = h.handle(ctx(validArgs))
        val err = outcome.shouldBeInstanceOf<ToolCallOutcome.Error>()
        err.envelope.code shouldBe ToolErrorCode.RATE_LIMITED
        err.envelope.message shouldContain "provider quota exceeded"
        // Plan §6 G.8: kein Provider-Aufruf bei Quota-Verletzung.
        spy.invokeCount.get() shouldBe 0
    }

    test("Plan §6 G.8: Quota wird auf Erfolg released, naechster Aufruf laeuft durch") {
        val spy = CountingProvider()
        val (h, _) = handler(spy, providerCallLimit = 1)
        val first = h.handle(ctx(validArgs))
        first.shouldBeInstanceOf<ToolCallOutcome.Success>()
        spy.invokeCount.get() shouldBe 1

        // Zweiter Aufruf mit anderem approvalKey; Slot ist nach
        // dem ersten Aufruf freigegeben.
        val secondArgs = validArgs.replace("\"k-1\"", "\"k-2\"")
        val second = h.handle(ctx(secondArgs))
        second.shouldBeInstanceOf<ToolCallOutcome.Success>()
        spy.invokeCount.get() shouldBe 2
    }

    test("Plan §6 G.8 Akzeptanz: Audit-Felder enthalten Provider-/Modell-Metadaten im Erfolgsfall") {
        val spy = CountingProvider()
        val auditFields = AuditFields()
        val (h, _) = handler(spy, providerCallLimit = Long.MAX_VALUE, auditFields = auditFields)
        h.handle(ctx(validArgs, auditFields)).shouldBeInstanceOf<ToolCallOutcome.Success>()

        // payloadFingerprint ist Pflicht, providerMeta-Lines werden
        // nach erfolgreicher dispatch in resourceRefs gespiegelt.
        auditFields.payloadFingerprint shouldNotBe null
        val refs = auditFields.resourceRefs
        refs.any { it.startsWith("provider:noop") } shouldBe true
        refs.any { it.startsWith("model:noop:default") } shouldBe true
        refs.any { it.startsWith("providerRequestId:") } shouldBe true
    }

    test("Plan §6 G.8 Akzeptanz: Audit-Scrubbing entfernt JDBC-Passwoerter aus resourceRefs") {
        // SecretScrubber wird auf jede Audit-resourceRef-Eintrag im
        // AuditScope.buildEvent aufgerufen. Wir testen den Scrubber
        // direkt — der Tool-Handler legt Refs als Strings ab, der
        // Scrubber maskiert in-place beim Audit-Emit.
        val refs = listOf(
            "jdbc:postgresql://app:topsecret@db.internal/mydb",
            "Bearer abcdef0123456789",
            "tok_aaaaaaaaaa",
            "provider:noop",
        )
        val scrubbed = refs.map(SecretScrubber::scrub)
        scrubbed[0] shouldNotContain "topsecret"
        scrubbed[0] shouldContain "***"
        scrubbed[1] shouldNotContain "abcdef0123456789"
        scrubbed[2] shouldNotContain "tok_"
        scrubbed[3] shouldBe "provider:noop"
    }

    test("Plan §6 G.8 Akzeptanz: Hygiene-Block ist auditierbar (payloadFingerprint gesetzt)") {
        val spy = CountingProvider()
        val auditFields = AuditFields()
        val (h, _) = handler(spy, providerCallLimit = Long.MAX_VALUE, auditFields = auditFields)
        // Argumente, die die Hygiene zwingen zu blocken — wir
        // verwenden die Plan-§-6-G.4-Akzeptanz: der Prompt darf
        // keine Refs ausserhalb der allowedRefs zitieren. Da der
        // Handler `procedureName` direkt im Prompt einfuegt, kann
        // ein procedureName mit api_key-Pattern den Hygiene-Block
        // ausloesen.
        val poisonedArgs = """
            {
              "approvalKey":"k-poison",
              "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
              "procedureName":"my_proc__api_key=AKIA1234567890ABCDEF",
              "targetDialect":"POSTGRESQL"
            }
        """.trimIndent()

        val outcome = h.handle(ctx(poisonedArgs, auditFields))
        outcome.shouldBeInstanceOf<ToolCallOutcome.Error>().envelope.code shouldBe
            ToolErrorCode.PROMPT_HYGIENE_BLOCKED

        // Hygiene-Block ist auditierbar: payloadFingerprint ist
        // gesetzt, providerMeta NICHT (Provider wurde nicht
        // aufgerufen).
        auditFields.payloadFingerprint shouldNotBe null
        auditFields.resourceRefs.none { it.startsWith("provider:") } shouldBe true
        spy.invokeCount.get() shouldBe 0
    }
})

private infix fun Any?.shouldNotBe(other: Any?) {
    if (this == other) error("expected $this != $other")
}
