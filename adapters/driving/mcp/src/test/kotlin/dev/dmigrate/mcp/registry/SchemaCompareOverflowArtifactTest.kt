package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val TENANT = TenantId("acme")
private val EPOCH = Instant.parse("2026-01-01T00:00:00Z")

/**
 * Das Ueberlauf-Artefakt von `schema_compare` (`diffArtifactRef`,
 * `spec/mcp-server.md`): es entsteht, sobald die Antwort das Ergebnis nicht
 * mehr ganz traegt — mehr Funde als `maxInlineFindings` **oder** mehr als die
 * Haelfte von `maxToolResponseBytes` —, hat die Art `COMPARE` und die Form
 * des Job-Artefakts (`status`, `summary`, alle `findings`). Review Runde 3,
 * L1: es war ein nacktes Array unter `DIFF`, und ueber die Anzahl allein
 * entstand es gar nicht.
 */
class SchemaCompareOverflowArtifactTest : FunSpec({

    class Setup(limits: McpLimitsConfig) {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val schemaStore = InMemorySchemaStore()
        val handler = SchemaCompareHandler(
            resolver = SchemaSourceResolver(schemaStore, limits),
            contentLoader = SchemaContentLoader(artifactStore, contentStore, limits),
            comparator = { left, right -> SchemaComparator().compare(left.schema, right.schema) },
            artifactSink = ArtifactSink(artifactStore, contentStore, Clock.fixed(EPOCH, ZoneOffset.UTC)),
            limits = limits,
        )

        fun stage(schemaId: String, vararg tables: String) {
            val pairs = tables.joinToString(",") { """"$it":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}""" }
            val bytes = """{"name":"orders","version":"1.0","tables":{$pairs}}""".toByteArray(Charsets.UTF_8)
            val artifactId = "art-$schemaId"
            artifactStore.save(
                ArtifactRecord(
                    managedArtifact = ManagedArtifact(
                        artifactId = artifactId, filename = "$artifactId.json", contentType = "application/json",
                        sizeBytes = bytes.size.toLong(), sha256 = "0".repeat(64),
                        createdAt = EPOCH, expiresAt = EPOCH.plusSeconds(86_400L * 365),
                    ),
                    kind = ArtifactKind.SCHEMA,
                    tenantId = TENANT,
                    ownerPrincipalId = Fixtures.principal("alice"),
                    visibility = JobVisibility.TENANT,
                    resourceUri = ServerResourceUri(TENANT, ResourceKind.ARTIFACTS, artifactId),
                ),
            )
            contentStore.write(artifactId, ByteArrayInputStream(bytes), bytes.size.toLong())
            schemaStore.save(
                SchemaIndexEntry(
                    schemaId = schemaId, tenantId = TENANT,
                    resourceUri = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, schemaId),
                    artifactRef = artifactId, displayName = schemaId,
                    createdAt = EPOCH, expiresAt = EPOCH.plusSeconds(86_400L * 365),
                ),
            )
        }

        fun compare(): JsonObject {
            fun ref(id: String) = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, id).render()
            val outcome = handler.handle(
                ToolCallContext(
                    "schema_compare",
                    JsonParser.parseString("""{"left":{"schemaRef":"${ref("left")}"},"right":{"schemaRef":"${ref("right")}"}}""")
                        .asJsonObject,
                    Fixtures.principalContext(),
                ),
            )
            val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
            return JsonParser.parseString(text).asJsonObject
        }

        /** Das Artefakt hinter `diffArtifactRef`: Art COMPARE, JSON, der Inhalt als Objekt. */
        fun overflow(payload: JsonObject): JsonObject {
            val artifactId = payload.get("diffArtifactRef").asString.substringAfterLast('/')
            val record = artifactStore.findById(TENANT, artifactId)!!
            record.kind shouldBe ArtifactKind.COMPARE
            record.managedArtifact.contentType shouldBe "application/json"
            val bytes = contentStore.openRangeRead(artifactId, 0, record.managedArtifact.sizeBytes).readAllBytes()
            return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        }
    }

    test("a large result goes to a COMPARE artefact in the one form, with every finding") {
        // Kleines Antwort-Budget und kleine Grenze: beide Wege greifen.
        val setup = Setup(McpLimitsConfig(maxToolResponseBytes = 200, maxInlineFindings = 3))
        setup.stage("left", *(1..20).map { "t$it" }.toTypedArray())
        setup.stage("right", *(21..40).map { "t$it" }.toTypedArray())
        val json = setup.compare()
        json.get("status").asString shouldBe "different"
        json.get("truncated").asBoolean shouldBe true
        json.get("diffArtifactRef").asString shouldStartWith "dmigrate://tenants/acme/artifacts/"
        json.getAsJsonArray("findings").size() shouldBe 3
        val artefact = setup.overflow(json)
        artefact.keySet() shouldBe setOf("status", "summary", "findings")
        artefact.get("status").asString shouldBe "different"
        artefact.get("summary").asString shouldBe "Schemas differ (40 change(s))."
        artefact.getAsJsonArray("findings").size() shouldBe 40
    }

    test("more findings than maxInlineFindings yield the artefact even when the bytes would fit") {
        // Vorher entstand es nur ueber die Byte-Grenze: `truncated` stand dann
        // ohne `diffArtifactRef`, und die Funde jenseits der Grenze waren
        // nirgends abrufbar — entgegen dem Ausgabeschema.
        val setup = Setup(McpLimitsConfig(maxInlineFindings = 3))
        setup.stage("left", "t1", "t2", "t3")
        setup.stage("right", "t4", "t5", "t6")
        val json = setup.compare()
        json.get("truncated").asBoolean shouldBe true
        json.getAsJsonArray("findings").size() shouldBe 3
        setup.overflow(json).getAsJsonArray("findings").size() shouldBe 6
    }

    test("a result that fits produces no artefact") {
        val setup = Setup(McpLimitsConfig())
        setup.stage("left", "t1", "t2")
        setup.stage("right", "t3")
        val json = setup.compare()
        json.get("truncated").asBoolean shouldBe false
        json.has("diffArtifactRef") shouldBe false
    }

    test("identical schemas never produce an artefact even at tiny limits") {
        // Nur ein Unterschied oder gekuerzte Funde kosten Artefakt-Kontingent.
        val setup = Setup(McpLimitsConfig(maxToolResponseBytes = 200))
        setup.stage("left", "t1")
        setup.stage("right", "t1")
        val json = setup.compare()
        json.get("status").asString shouldBe "identical"
        json.has("diffArtifactRef") shouldBe false
    }
})
