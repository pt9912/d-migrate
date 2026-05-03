package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.TenantScopeDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

private val ACME = TenantId("acme")

private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private const val TEST_REQUEST_ID = "req-deadbeef"

private fun ctx(args: JsonObject, name: String = "schema_validate"): ToolCallContext =
    ToolCallContext(name = name, arguments = args, principal = PRINCIPAL, requestId = TEST_REQUEST_ID)

private fun handler(
    limits: McpLimitsConfig = McpLimitsConfig(),
): SchemaValidateHandler {
    val schemaStore = InMemorySchemaStore()
    val artifactStore = InMemoryArtifactStore()
    val contentStore = InMemoryArtifactContentStore()
    // AP 6.23: SchemaValidateHandler requires a non-null sink so the
    // truncated → artifactRef schema coupling holds unconditionally.
    val sink = dev.dmigrate.mcp.registry.ArtifactSink(
        artifactStore,
        contentStore,
        java.time.Clock.systemUTC(),
    )
    return SchemaValidateHandler(
        resolver = SchemaSourceResolver(schemaStore, limits),
        contentLoader = SchemaContentLoader(artifactStore, contentStore, limits),
        validator = SchemaValidator(),
        limits = limits,
        artifactSink = sink,
    )
}

private fun parsePayload(outcome: ToolCallOutcome): JsonObject {
    val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
    return JsonParser.parseString(text).asJsonObject
}

private fun args(content: String): JsonObject = JsonParser.parseString(content).asJsonObject

class SchemaValidateHandlerTest : FunSpec({

    test("inline valid schema returns valid=true and a success summary") {
        val outcome = handler().handle(
            ctx(args("""{"schema":{"name":"orders","version":"1.0","tables":{}}}""")),
        )
        val json = parsePayload(outcome)
        json.get("valid").asBoolean shouldBe true
        json.get("summary").asString shouldContain "valid"
        json.getAsJsonArray("findings").size() shouldBe 0
        json.get("truncated").asBoolean shouldBe false
        json.getAsJsonObject("executionMeta").get("requestId").asString shouldBe TEST_REQUEST_ID
    }

    test("inline invalid schema produces structured findings (severity, code, path, message)") {
        // Invalid: a table with no columns triggers SchemaStructure
        // E001 ("Table has no columns"). Each finding carries the
        // four contract fields below.
        val outcome = handler().handle(
            ToolCallContext(
                name = "schema_validate",
                arguments = args(
                    """{"schema":{"name":"orders","version":"1.0","tables":{"t1":{"columns":{}}}}}""",
                ),
                principal = PRINCIPAL,
            ),
        )
        val json = parsePayload(outcome)
        json.get("valid").asBoolean shouldBe false
        val findings = json.getAsJsonArray("findings")
        (findings.size() > 0) shouldBe true
        val first = findings.get(0).asJsonObject
        first.get("severity").asString shouldBe "error"
        first.get("code").asString shouldBe "E001"
        first.get("path").asString shouldBe "tables.t1"
        first.has("message") shouldBe true
    }

    test("strict mode treats warnings as failures while lenient ignores them") {
        // Schema that parses cleanly and produces only a warning:
        // SchemaStructure E008 fires when a table has columns but no
        // explicit primary key and no `identifier`-typed column.
        val schemaJson =
            """{"name":"x","version":"1.0","tables":{"t1":{"columns":{"c1":{"type":"text","max_length":10}}}}}"""
        val argsLenient = args("""{"schema":$schemaJson,"strictness":"lenient"}""")
        val argsStrict = args("""{"schema":$schemaJson,"strictness":"strict"}""")

        val lenient = parsePayload(
            handler().handle(ToolCallContext("schema_validate", argsLenient, PRINCIPAL)),
        )
        val strict = parsePayload(
            handler().handle(ToolCallContext("schema_validate", argsStrict, PRINCIPAL)),
        )
        // Both surface the warning in findings; strictness only flips
        // `valid`.
        lenient.get("valid").asBoolean shouldBe true
        strict.get("valid").asBoolean shouldBe false
        lenient.getAsJsonArray("findings").get(0).asJsonObject
            .get("severity").asString shouldBe "warning"
    }

    test("findings beyond maxInlineFindings are truncated with truncated=true") {
        // Each empty-columns table generates one E001 error. With cap=3
        // and 10 such tables, the response has 3 findings + truncated.
        val tinyLimits = McpLimitsConfig(maxInlineFindings = 3)
        val sut = handler(limits = tinyLimits)
        val tables = (1..10).joinToString(",") { """"t$it":{"columns":{}}""" }
        val schemaJson = """{"name":"x","version":"1.0","tables":{$tables}}"""
        val outcome = sut.handle(
            ToolCallContext("schema_validate", args("""{"schema":$schemaJson}"""), PRINCIPAL),
        )
        val json = parsePayload(outcome)
        json.get("truncated").asBoolean shouldBe true
        json.getAsJsonArray("findings").size() shouldBe 3
    }

    test("missing source throws VALIDATION_ERROR (mapped via the resolver)") {
        shouldThrow<ValidationErrorException> {
            handler().handle(ToolCallContext("schema_validate", args("""{}"""), PRINCIPAL))
        }
    }

    test("schemaRef pointing at a foreign tenant throws TENANT_SCOPE_DENIED") {
        shouldThrow<TenantScopeDeniedException> {
            handler().handle(
                ToolCallContext(
                    "schema_validate",
                    args("""{"schemaRef":"dmigrate://tenants/other/schemas/s1"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("schemaRef pointing at an unknown id throws RESOURCE_NOT_FOUND") {
        shouldThrow<ResourceNotFoundException> {
            handler().handle(
                ToolCallContext(
                    "schema_validate",
                    args("""{"schemaRef":"dmigrate://tenants/acme/schemas/missing"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("invalid strictness value throws VALIDATION_ERROR with field=strictness") {
        val ex = shouldThrow<ValidationErrorException> {
            handler().handle(
                ToolCallContext(
                    "schema_validate",
                    args("""{"schema":{"name":"x","version":"1.0"},"strictness":"medium"}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "strictness"
    }

    test("non-object arguments throw VALIDATION_ERROR (no raw cast exception)") {
        // Defensive: a malformed client could send `arguments: "stuff"`
        // — must surface as VALIDATION_ERROR rather than crashing the
        // dispatch path with a ClassCastException.
        val rawArgs = JsonParser.parseString("\"not an object\"")
        shouldThrow<ValidationErrorException> {
            handler().handle(ToolCallContext("schema_validate", rawArgs, PRINCIPAL))
        }
    }

    test("AP 6.17: validator-produced message + path are scrubbed of Bearer tokens") {
        // A schema whose object names contain a bearer-token literal
        // must not leak the literal via finding paths. Validator
        // rules use the object name in `path` and `message`, so
        // both routes need scrubbing.
        val schemaWithSecret =
            """{"name":"x","version":"1.0","tables":{"$BEARER_TOKEN_LITERAL":{"columns":{}}}}"""
        val outcome = handler().handle(
            ToolCallContext(
                "schema_validate",
                args("""{"schema":$schemaWithSecret}"""),
                PRINCIPAL,
            ),
        )
        val finding = parsePayload(outcome).getAsJsonArray("findings").get(0).asJsonObject
        finding.assertNoBearerLeak("path")
        finding.assertNoBearerLeak("message")
    }

    test("response carries application/json mime type and text content") {
        val outcome = handler().handle(
            ToolCallContext(
                "schema_validate",
                args("""{"schema":{"name":"x","version":"1.0","tables":{}}}"""),
                PRINCIPAL,
            ),
        )
        val content = (outcome as ToolCallOutcome.Success).content.single()
        content.type shouldBe "text"
        content.mimeType shouldBe "application/json"
    }

    test("AP 6.19: truncated findings spill to an artefact and the inline payload carries the artifactRef") {
        // 10 empty-column tables produce 10 E001 errors; cap=3 trips
        // the truncation path. The wired ArtifactSink must persist
        // the FULL findings list while the inline payload stays
        // capped + carries the artifactRef.
        val limits = McpLimitsConfig(maxInlineFindings = 3)
        val schemaStore = InMemorySchemaStore()
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val sink = ArtifactSink(artifactStore, contentStore, java.time.Clock.systemUTC())
        val sut = SchemaValidateHandler(
            resolver = SchemaSourceResolver(schemaStore, limits),
            contentLoader = SchemaContentLoader(artifactStore, contentStore, limits),
            validator = SchemaValidator(),
            limits = limits,
            artifactSink = sink,
        )
        val tables = (1..10).joinToString(",") { """"t$it":{"columns":{}}""" }
        val schema = """{"name":"x","version":"1.0","tables":{$tables}}"""
        val json = parsePayload(
            sut.handle(
                ToolCallContext("schema_validate", args("""{"schema":$schema}"""), PRINCIPAL),
            ),
        )
        json.get("truncated").asBoolean shouldBe true
        json.getAsJsonArray("findings").size() shouldBe 3
        val artifactRef = json.get("artifactRef").asString
        artifactRef shouldContain "/artifacts/"
        val artifactId = artifactRef.substringAfterLast("/")
        val record = artifactStore.findById(ACME, artifactId)!!
        val storedBytes = contentStore.openRangeRead(artifactId, 0L, record.managedArtifact.sizeBytes)
            .use { stream -> stream.readBytes() }
        val storedJson = JsonParser.parseString(String(storedBytes, Charsets.UTF_8)).asJsonArray
        // The artefact carries more findings than fit inline — that is
        // the whole point of the spill. Assert strictly more than the
        // inline cap rather than pinning an exact count, because the
        // validator may legitimately attach extra findings beyond the
        // 10 E001 errors per table (e.g. global missing-PK warnings).
        storedJson.size() shouldBeGreaterThan 3
    }

    // AP 6.23 retired the "no sink wired" path: SchemaValidateHandler's
    // ArtifactSink is now non-null, and the schema's `truncated → artifactRef`
    // if/then makes the truncate-only fallback structurally invalid.
    // The artifactRef-on-truncate behaviour is exercised in
    // "AP 6.19: when findings exceed maxInline, persist them as an artefact".
})
