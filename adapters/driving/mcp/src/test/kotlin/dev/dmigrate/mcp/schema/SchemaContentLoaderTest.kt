package dev.dmigrate.mcp.schema

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.time.Instant

private val ACME = TenantId("acme")
private val ALICE = PrincipalId("alice")

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

private const val MINIMAL_JSON_SCHEMA = """{"name":"orders","version":"1.0","tables":{}}"""

/**
 * Dieselbe Sicht in der Form, die `schema reverse` schreibt — YAML, und mit
 * `schema_format` als erstem Token. Genau daran scheiterte der JSON-Codec.
 */
private val MINIMAL_YAML_SCHEMA = """
    schema_format: "1.0"
    name: orders
    version: "1.0"
    tables: {}
""".trimIndent()

class SchemaContentLoaderTest : FunSpec({

    test("inline JSON schema parses into a SchemaDefinition") {
        val loader = SchemaContentLoader(
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            limits = McpLimitsConfig(),
        )
        val source = SchemaSource.Inline(
            schema = JsonParser.parseString(MINIMAL_JSON_SCHEMA).asJsonObject,
            serialisedJson = MINIMAL_JSON_SCHEMA,
            byteSize = MINIMAL_JSON_SCHEMA.toByteArray(Charsets.UTF_8).size,
        )
        val schema = loader.load(source, format = null)
        schema.name shouldBe "orders"
        schema.version shouldBe "1.0"
    }

    test("schemaRef resolves through ArtifactStore + ArtifactContentStore") {
        val artifactStore = InMemoryArtifactStore().apply {
            save(artifactRecord("art-1", MINIMAL_JSON_SCHEMA.toByteArray(Charsets.UTF_8).size.toLong()))
        }
        val contentStore = InMemoryArtifactContentStore().apply {
            val bytes = MINIMAL_JSON_SCHEMA.toByteArray(Charsets.UTF_8)
            write("art-1", ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        val loader = SchemaContentLoader(artifactStore, contentStore, McpLimitsConfig())
        val source = SchemaSource.Reference(schemaEntry("s1", "art-1"))
        val schema = loader.load(source, format = "json")
        schema.name shouldBe "orders"
    }

    test("schemaRef with missing ArtifactRecord throws RESOURCE_NOT_FOUND") {
        // Store inconsistency: SchemaIndexEntry exists but the artifact
        // record is gone (e.g. retention reaped artifacts but the
        // schema index lagged). Must not 500.
        val source = SchemaSource.Reference(schemaEntry("s1", "art-missing"))
        val loader = SchemaContentLoader(
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            limits = McpLimitsConfig(),
        )
        val ex = shouldThrow<ResourceNotFoundException> { loader.load(source, format = null) }
        ex.resourceUri.id shouldBe "s1"
    }

    test("schemaRef with artifact size over maxArtifactUploadBytes throws PAYLOAD_TOO_LARGE") {
        val tinyLimits = McpLimitsConfig(maxArtifactUploadBytes = 4)
        val artifactStore = InMemoryArtifactStore().apply {
            save(artifactRecord("art-big", sizeBytes = 8))
        }
        val source = SchemaSource.Reference(schemaEntry("s1", "art-big"))
        val loader = SchemaContentLoader(
            artifactStore,
            InMemoryArtifactContentStore(),
            tinyLimits,
        )
        val ex = shouldThrow<PayloadTooLargeException> { loader.load(source, format = null) }
        ex.maxBytes shouldBe 4L
        ex.actualBytes shouldBe 8L
    }

    test("a YAML schemaRef loads WITHOUT an explicit format (Konsumentenbefund gegen 1.7.0)") {
        // `schema reverse` legt sein Artefakt als YAML ab, der Verweis-Pfad
        // nahm ohne `format` aber JSON an: jeder Aufruf, der
        // `schema_reverse_start` mit `schema_generate` verkettete, scheiterte
        // mit "Unrecognized token 'schema_format'". Jetzt wird erkannt.
        val artifactStore = InMemoryArtifactStore().apply {
            val bytes = MINIMAL_YAML_SCHEMA.toByteArray(Charsets.UTF_8)
            save(artifactRecord("art-yaml", sizeBytes = bytes.size.toLong()))
        }
        val contentStore = InMemoryArtifactContentStore().apply {
            val bytes = MINIMAL_YAML_SCHEMA.toByteArray(Charsets.UTF_8)
            write("art-yaml", ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        val loader = SchemaContentLoader(artifactStore, contentStore, McpLimitsConfig())
        val schema = loader.load(SchemaSource.Reference(schemaEntry("s1", "art-yaml")), format = null)
        schema.name shouldBe "orders"
        schema.version shouldBe "1.0"
    }

    test("a JSON schemaRef loads WITHOUT an explicit format either") {
        // Die Erkennung muss beide Richtungen koennen: ein Artefakt aus einem
        // Upload ist JSON, eines aus `schema_reverse` YAML. Ohne diesen Fall
        // waere nur der YAML-Zweig gepinnt.
        val artifactStore = InMemoryArtifactStore().apply {
            val bytes = MINIMAL_JSON_SCHEMA.toByteArray(Charsets.UTF_8)
            save(artifactRecord("art-json", sizeBytes = bytes.size.toLong()))
        }
        val contentStore = InMemoryArtifactContentStore().apply {
            val bytes = MINIMAL_JSON_SCHEMA.toByteArray(Charsets.UTF_8)
            write("art-json", ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        val loader = SchemaContentLoader(artifactStore, contentStore, McpLimitsConfig())
        val schema = loader.load(SchemaSource.Reference(schemaEntry("s1", "art-json")), format = null)
        schema.name shouldBe "orders"
    }

    test("an explicit format still wins over the detected one") {
        // Die Erkennung ist der Rueckfall, keine Uebersteuerung: ein falsch
        // angegebenes `format` bleibt ein benannter Fehler.
        val artifactStore = InMemoryArtifactStore().apply {
            save(artifactRecord("art-yaml", sizeBytes = MINIMAL_YAML_SCHEMA.length.toLong()))
        }
        val contentStore = InMemoryArtifactContentStore().apply {
            val bytes = MINIMAL_YAML_SCHEMA.toByteArray(Charsets.UTF_8)
            write("art-yaml", ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        val loader = SchemaContentLoader(artifactStore, contentStore, McpLimitsConfig())
        val ex = shouldThrow<ValidationErrorException> {
            loader.load(SchemaSource.Reference(schemaEntry("s1", "art-yaml")), format = "json")
        }
        ex.violations.map { it.field } shouldContain "schema"
    }

    test("schemaRef with unknown format throws VALIDATION_ERROR (not raw IllegalArgumentException)") {
        val artifactStore = InMemoryArtifactStore().apply {
            save(artifactRecord("art-1", sizeBytes = 1))
        }
        val source = SchemaSource.Reference(schemaEntry("s1", "art-1"))
        val loader = SchemaContentLoader(
            artifactStore,
            InMemoryArtifactContentStore(),
            McpLimitsConfig(),
        )
        val ex = shouldThrow<ValidationErrorException> {
            loader.load(source, format = "toml")
        }
        ex.violations.map { it.field } shouldContain "format"
    }

    test("inline schema with malformed JSON content throws VALIDATION_ERROR (no raw codec exception)") {
        // The inline JsonObject can carry structurally-valid JSON that
        // is structurally invalid as a SchemaDefinition; the codec
        // throws JsonSyntaxException / IllegalStateException — the
        // handler must surface VALIDATION_ERROR(schema, ...) instead.
        val loader = SchemaContentLoader(
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            limits = McpLimitsConfig(),
        )
        val malformed = JsonParser.parseString("""{"tables":"not-an-object"}""").asJsonObject
        val malformedJson = malformed.toString()
        val source = SchemaSource.Inline(
            schema = malformed,
            serialisedJson = malformedJson,
            byteSize = malformedJson.toByteArray(Charsets.UTF_8).size,
        )
        val ex = shouldThrow<ValidationErrorException> { loader.load(source, format = null) }
        ex.violations.map { it.field } shouldContain "schema"
    }
})
