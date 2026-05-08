package dev.dmigrate.mcp.prompts

import dev.dmigrate.mcp.protocol.PromptsGetParams
import dev.dmigrate.mcp.protocol.PromptsListParams
import dev.dmigrate.server.application.audit.prompt.DefaultPromptHygieneService
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class PromptsHandlerTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val principal = PrincipalContext(
        principalId = alice,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:read"),
        isAdmin = false,
        auditSubject = "alice",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    fun handler() = PromptsHandler(
        registry = DefaultPromptRegistry.mandatory(),
        hygieneService = DefaultPromptHygieneService(),
    )

    test("LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: list() liefert die drei Pflichtprompts in alphabetischer Reihenfolge") {
        val out = handler().list(PromptsListParams())
        out.prompts.map { it.name } shouldBe listOf(
            "procedure_analysis", "procedure_transformation", "testdata_planning",
        )
    }

    test("procedure_analysis happy path mit schemaRef + procedureName -> Found") {
        val outcome = handler().get(
            PromptsGetParams(
                name = "procedure_analysis",
                arguments = mapOf(
                    "schemaRef" to "dmigrate://tenants/acme/schemas/orders",
                    "procedureName" to "process_orders",
                ),
            ),
            principal,
        )
        val found = outcome.shouldBeInstanceOf<PromptsLookupOutcome.Found>()
        found.result.messages.size shouldBe 1
        found.result.messages.single().role shouldBe "user"
        found.result.messages.single().content.text shouldContain "schemaRef=dmigrate://tenants/acme/schemas/orders"
        found.result.description shouldContain "expectedTools=procedure_transform_plan"
    }

    test("procedure_analysis happy path mit artifactRef -> Found") {
        val outcome = handler().get(
            PromptsGetParams(
                name = "procedure_analysis",
                arguments = mapOf(
                    "artifactRef" to "dmigrate://tenants/acme/artifacts/proc-1",
                ),
            ),
            principal,
        )
        outcome.shouldBeInstanceOf<PromptsLookupOutcome.Found>()
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: unbekannter Prompt -> NotFound") {
        val outcome = handler().get(
            PromptsGetParams(name = "no_such_prompt", arguments = null),
            principal,
        )
        val notFound = outcome.shouldBeInstanceOf<PromptsLookupOutcome.NotFound>()
        notFound.name shouldBe "no_such_prompt"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: fehlendes Pflichtargument -> InvalidArguments(MISSING_REQUIRED)") {
        val outcome = handler().get(
            PromptsGetParams(
                name = "testdata_planning",
                arguments = mapOf("targetDialect" to "POSTGRESQL"),
                // schemaRef fehlt
            ),
            principal,
        )
        val invalid = outcome.shouldBeInstanceOf<PromptsLookupOutcome.InvalidArguments>()
        invalid.violations.map { it.field } shouldContainAll listOf("schemaRef")
        invalid.violations.first { it.field == "schemaRef" }.code shouldBe
            PromptArgumentValidationError.MISSING_REQUIRED
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: unbekanntes Argument -> InvalidArguments(UNKNOWN_ARGUMENT)") {
        val outcome = handler().get(
            PromptsGetParams(
                name = "testdata_planning",
                arguments = mapOf(
                    "schemaRef" to "dmigrate://tenants/acme/schemas/x",
                    "targetDialect" to "POSTGRESQL",
                    "unknownField" to "leaks",
                ),
            ),
            principal,
        )
        val invalid = outcome.shouldBeInstanceOf<PromptsLookupOutcome.InvalidArguments>()
        invalid.violations.first { it.field == "unknownField" }.code shouldBe
            PromptArgumentValidationError.UNKNOWN_ARGUMENT
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: ungueltiges enum -> InvalidArguments(ENUM_VIOLATION)") {
        val outcome = handler().get(
            PromptsGetParams(
                name = "testdata_planning",
                arguments = mapOf(
                    "schemaRef" to "dmigrate://tenants/acme/schemas/x",
                    "targetDialect" to "ORACLE",
                ),
            ),
            principal,
        )
        val invalid = outcome.shouldBeInstanceOf<PromptsLookupOutcome.InvalidArguments>()
        invalid.violations.first { it.field == "targetDialect" }.code shouldBe
            PromptArgumentValidationError.ENUM_VIOLATION
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: invalides URI-Format -> InvalidArguments(INVALID_URI)") {
        val outcome = handler().get(
            PromptsGetParams(
                name = "testdata_planning",
                arguments = mapOf(
                    "schemaRef" to "not-a-uri",
                    "targetDialect" to "POSTGRESQL",
                ),
            ),
            principal,
        )
        val invalid = outcome.shouldBeInstanceOf<PromptsLookupOutcome.InvalidArguments>()
        invalid.violations.first { it.field == "schemaRef" }.code shouldBe
            PromptArgumentValidationError.INVALID_URI
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: falscher ResourceKind -> InvalidArguments(WRONG_RESOURCE_KIND)") {
        val outcome = handler().get(
            PromptsGetParams(
                name = "testdata_planning",
                arguments = mapOf(
                    "schemaRef" to "dmigrate://tenants/acme/artifacts/x",
                    "targetDialect" to "POSTGRESQL",
                ),
            ),
            principal,
        )
        val invalid = outcome.shouldBeInstanceOf<PromptsLookupOutcome.InvalidArguments>()
        invalid.violations.first { it.field == "schemaRef" }.code shouldBe
            PromptArgumentValidationError.WRONG_RESOURCE_KIND
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: cross-tenant URI -> InvalidArguments(TENANT_SCOPE_DENIED)") {
        val outcome = handler().get(
            PromptsGetParams(
                name = "testdata_planning",
                arguments = mapOf(
                    "schemaRef" to "dmigrate://tenants/other/schemas/x",
                    "targetDialect" to "POSTGRESQL",
                ),
            ),
            principal,
        )
        val invalid = outcome.shouldBeInstanceOf<PromptsLookupOutcome.InvalidArguments>()
        invalid.violations.first { it.field == "schemaRef" }.code shouldBe
            PromptArgumentValidationError.TENANT_SCOPE_DENIED
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: Secret-/Rohdatenparameter -> HygieneBlocked") {
        // rulesSummary mit api_key-Pattern → die Hygiene blockt den
        // gebauten Prompt-Text.
        val outcome = handler().get(
            PromptsGetParams(
                name = "testdata_planning",
                arguments = mapOf(
                    "schemaRef" to "dmigrate://tenants/acme/schemas/x",
                    "targetDialect" to "POSTGRESQL",
                    "rulesSummary" to "use the secret_key=AKIA1234567890ABCDEF for connection",
                ),
            ),
            principal,
        )
        val blocked = outcome.shouldBeInstanceOf<PromptsLookupOutcome.HygieneBlocked>()
        // LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: kein Secret-Wert im Public-Message.
        blocked.publicMessage.contains("AKIA") shouldBe false
        blocked.publicMessage.contains("secret_key") shouldBe false
    }

    test("LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: prompts/get fuehrt KEIN Tool aus") {
        // Strukturzusage: PromptsHandler hat keinen Zugriff auf
        // ToolRegistry/Handler — der Konstruktor nimmt nur Registry +
        // Hygiene. Wir verifizieren das hier durch Reflection-Check
        // auf den Konstruktor-Parameter-Set.
        val ctor = PromptsHandler::class.constructors.single()
        ctor.parameters.size shouldBe 2
        ctor.parameters.map { it.name }.toSet() shouldBe setOf("registry", "hygieneService")
    }

    test("LF-017 / LF-024 / LN-030 / LN-031 Pflichtprompts haben revision != null und nicht-leere expectedTools") {
        val registry = DefaultPromptRegistry.mandatory()
        for (entry in registry.list()) {
            val descriptor = registry.find(entry.name)!!
            descriptor.revision.isNotBlank() shouldBe true
            descriptor.expectedTools.isNotEmpty() shouldBe true
            descriptor.hygieneRules.isNotEmpty() shouldBe true
        }
    }
})
