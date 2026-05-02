package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.format.json.JsonSchemaCodec
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.TenantScopeDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val ACME = TenantId("acme")
private val ALICE = PrincipalId("alice")

private val PRINCIPAL = PrincipalContext(
    principalId = ALICE,
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-05-02T12:00:00Z"), ZoneOffset.UTC)

private fun managed(artifactId: String, sizeBytes: Long): ManagedArtifact = ManagedArtifact(
    artifactId = artifactId,
    filename = "$artifactId.json",
    contentType = "application/json",
    sizeBytes = sizeBytes,
    sha256 = "0".repeat(64),
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
)

private fun artifactRecord(artifactId: String, sizeBytes: Long): ArtifactRecord = ArtifactRecord(
    managedArtifact = managed(artifactId, sizeBytes),
    kind = ArtifactKind.SCHEMA,
    tenantId = ACME,
    ownerPrincipalId = ALICE,
    visibility = JobVisibility.TENANT,
    resourceUri = ServerResourceUri(ACME, ResourceKind.ARTIFACTS, artifactId),
)

private fun schemaEntry(schemaId: String, artifactRef: String): SchemaIndexEntry = SchemaIndexEntry(
    schemaId = schemaId,
    tenantId = ACME,
    resourceUri = ServerResourceUri(ACME, ResourceKind.SCHEMAS, schemaId),
    artifactRef = artifactRef,
    displayName = "test schema",
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
)

private class HandlerSetup(
    val handler: SchemaCompareHandler,
    val schemaStore: InMemorySchemaStore,
    val artifactStore: InMemoryArtifactStore,
    val contentStore: InMemoryArtifactContentStore,
)

private fun setup(limits: McpLimitsConfig = McpLimitsConfig()): HandlerSetup {
    val schemaStore = InMemorySchemaStore()
    val artifactStore = InMemoryArtifactStore()
    val contentStore = InMemoryArtifactContentStore()
    val handler = SchemaCompareHandler(
        resolver = SchemaSourceResolver(schemaStore, limits),
        contentLoader = SchemaContentLoader(artifactStore, contentStore, limits),
        comparator = SchemaComparator(),
        artifactSink = ArtifactSink(artifactStore, contentStore, FIXED_CLOCK),
        limits = limits,
        requestIdProvider = { "req-deadbeef" },
    )
    return HandlerSetup(handler, schemaStore, artifactStore, contentStore)
}

private fun stageSchema(setup: HandlerSetup, schemaId: String, json: String) {
    val artifactId = "art-$schemaId"
    val bytes = json.toByteArray(Charsets.UTF_8)
    setup.artifactStore.save(artifactRecord(artifactId, bytes.size.toLong()))
    setup.contentStore.write(artifactId, ByteArrayInputStream(bytes), bytes.size.toLong())
    setup.schemaStore.save(schemaEntry(schemaId, artifactId))
}

private fun args(content: String): JsonObject = JsonParser.parseString(content).asJsonObject

private fun parsePayload(outcome: ToolCallOutcome): JsonObject {
    val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
    return JsonParser.parseString(text).asJsonObject
}

private fun ref(schemaId: String): String =
    ServerResourceUri(ACME, ResourceKind.SCHEMAS, schemaId).render()

private fun schemaJson(name: String, vararg tables: String): String {
    val tablePairs = tables.joinToString(",") { """"$it":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}""" }
    return """{"name":"$name","version":"1.0","tables":{$tablePairs}}"""
}

class SchemaCompareHandlerTest : FunSpec({

    test("identical schemas surface status=identical, empty findings") {
        val setup = setup()
        stageSchema(setup, "left", schemaJson("orders", "t1"))
        stageSchema(setup, "right", schemaJson("orders", "t1"))
        val outcome = setup.handler.handle(
            ToolCallContext(
                "schema_compare",
                args("""{"left":{"schemaRef":"${ref("left")}"},"right":{"schemaRef":"${ref("right")}"}}"""),
                PRINCIPAL,
            ),
        )
        val json = parsePayload(outcome)
        json.get("status").asString shouldBe "identical"
        json.get("summary").asString shouldContain "identical"
        json.getAsJsonArray("findings").size() shouldBe 0
        json.get("truncated").asBoolean shouldBe false
        json.has("diffArtifactRef") shouldBe false
    }

    test("differing schemas project SchemaDiff into structured findings") {
        val setup = setup()
        stageSchema(setup, "left", schemaJson("orders", "t1"))
        // right adds t2 and removes t1 → 2 findings (t2 added=info, t1 removed=warning)
        stageSchema(setup, "right", schemaJson("orders", "t2"))
        val json = parsePayload(
            setup.handler.handle(
                ToolCallContext(
                    "schema_compare",
                    args("""{"left":{"schemaRef":"${ref("left")}"},"right":{"schemaRef":"${ref("right")}"}}"""),
                    PRINCIPAL,
                ),
            ),
        )
        json.get("status").asString shouldBe "different"
        val findings = json.getAsJsonArray("findings")
        val codes = findings.map { it.asJsonObject.get("code").asString }.toSet()
        codes shouldBe setOf("TABLE_ADDED", "TABLE_REMOVED")
        val byCode = findings.map { it.asJsonObject }
            .associateBy { it.get("code").asString }
        byCode.getValue("TABLE_ADDED").get("severity").asString shouldBe "info"
        byCode.getValue("TABLE_REMOVED").get("severity").asString shouldBe "warning"
        byCode.getValue("TABLE_REMOVED").get("path").asString shouldBe "tables.t1"
    }

    test("connectionRef on either side is rejected with a hint to schema_compare_start") {
        val setup = setup()
        val ex = shouldThrow<ValidationErrorException> {
            setup.handler.handle(
                ToolCallContext(
                    "schema_compare",
                    args(
                        """{"left":{"connectionRef":"db-1"},"right":{"schemaRef":"${ref("right")}"}}""",
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "left.connectionRef"
        ex.violations.first().reason shouldContain "schema_compare_start"
    }

    test("inline schema is rejected on either side (Phase C is schemaRef-only)") {
        val setup = setup()
        val ex = shouldThrow<ValidationErrorException> {
            setup.handler.handle(
                ToolCallContext(
                    "schema_compare",
                    args(
                        """{"left":{"schema":{"name":"x","version":"1.0"}},"right":{"schemaRef":"${ref("right")}"}}""",
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "left.schema"
    }

    test("missing left or right side throws VALIDATION_ERROR") {
        val setup = setup()
        shouldThrow<ValidationErrorException> {
            setup.handler.handle(
                ToolCallContext(
                    "schema_compare",
                    args("""{"right":{"schemaRef":"${ref("right")}"}}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("missing schemaRef inside a side throws VALIDATION_ERROR with the dotted field") {
        val setup = setup()
        val ex = shouldThrow<ValidationErrorException> {
            setup.handler.handle(
                ToolCallContext(
                    "schema_compare",
                    args("""{"left":{},"right":{"schemaRef":"${ref("right")}"}}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "left.schemaRef"
    }

    test("schemaRef pointing at a foreign tenant throws TENANT_SCOPE_DENIED") {
        val setup = setup()
        stageSchema(setup, "left", schemaJson("orders", "t1"))
        val foreignUri = ServerResourceUri(TenantId("other"), ResourceKind.SCHEMAS, "x").render()
        shouldThrow<TenantScopeDeniedException> {
            setup.handler.handle(
                ToolCallContext(
                    "schema_compare",
                    args("""{"left":{"schemaRef":"${ref("left")}"},"right":{"schemaRef":"$foreignUri"}}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("large diff is moved to a tenant-scoped artefact and returned as diffArtifactRef") {
        // Tiny tool-response budget plus many changed tables forces
        // the artefact fallback. Each table change projects into one
        // finding; the inline cap is also tiny so both truncation
        // paths fire — `truncated=true` either way.
        val tinyLimits = McpLimitsConfig(maxToolResponseBytes = 200, maxInlineFindings = 3)
        val setup = setup(tinyLimits)
        val leftTables = (1..20).map { "t$it" }.toTypedArray()
        val rightTables = (21..40).map { "t$it" }.toTypedArray()
        stageSchema(setup, "left", schemaJson("orders", *leftTables))
        stageSchema(setup, "right", schemaJson("orders", *rightTables))
        val json = parsePayload(
            setup.handler.handle(
                ToolCallContext(
                    "schema_compare",
                    args("""{"left":{"schemaRef":"${ref("left")}"},"right":{"schemaRef":"${ref("right")}"}}"""),
                    PRINCIPAL,
                ),
            ),
        )
        json.get("status").asString shouldBe "different"
        json.get("truncated").asBoolean shouldBe true
        json.get("diffArtifactRef").asString shouldStartWith "dmigrate://tenants/acme/artifacts/"
        json.getAsJsonArray("findings").size() shouldBe 3
    }

    test("identical schemas never produce a diff artefact even at tiny limits") {
        // Defensive: only the `different` branch should ever spend
        // artefact-store quota on the diff — `identical` carries no
        // finding payload.
        val setup = setup(McpLimitsConfig(maxToolResponseBytes = 200))
        stageSchema(setup, "left", schemaJson("orders", "t1"))
        stageSchema(setup, "right", schemaJson("orders", "t1"))
        val json = parsePayload(
            setup.handler.handle(
                ToolCallContext(
                    "schema_compare",
                    args("""{"left":{"schemaRef":"${ref("left")}"},"right":{"schemaRef":"${ref("right")}"}}"""),
                    PRINCIPAL,
                ),
            ),
        )
        json.has("diffArtifactRef") shouldBe false
    }

    test("response carries application/json mime type and executionMeta.requestId") {
        val setup = setup()
        stageSchema(setup, "left", schemaJson("orders", "t1"))
        stageSchema(setup, "right", schemaJson("orders", "t1"))
        val outcome = setup.handler.handle(
            ToolCallContext(
                "schema_compare",
                args("""{"left":{"schemaRef":"${ref("left")}"},"right":{"schemaRef":"${ref("right")}"}}"""),
                PRINCIPAL,
            ),
        )
        val content = (outcome as ToolCallOutcome.Success).content.single()
        content.mimeType shouldBe "application/json"
        parsePayload(outcome).getAsJsonObject("executionMeta").get("requestId").asString shouldBe "req-deadbeef"
    }
})
