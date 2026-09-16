package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.quota.DefaultQuotaService
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
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val TENANT = TenantId("acme")
private val OWNER = PrincipalId("alice")
private val EPOCH = Instant.parse("2026-01-01T00:00:00Z")

private val CALLER = PrincipalContext(
    principalId = OWNER,
    homeTenantId = TENANT,
    effectiveTenantId = TENANT,
    allowedTenantIds = setOf(TENANT),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

/**
 * Die MCP-Baustelle des `schema_compare`-Comparators (`McpRuntimeRegistries`)
 * — durch die **echte** Registry, nicht durch einen handgebauten Handler.
 *
 * Zwei Reverse-Artefakte desselben Schemas: der PostgreSQL-Reverse traegt den
 * Sequenznamen der Identity-Spalte, der MySQL-Reverse keinen. Das ist
 * Server-Buchhaltung und kein Fund; zwei handgeschriebene Schemata mit
 * demselben Unterschied bleiben streng.
 */
class SchemaCompareRuntimeIdentityTest : FunSpec({

    val artifactStore = InMemoryArtifactStore()
    val contentStore = InMemoryArtifactContentStore()
    val schemaStore = InMemorySchemaStore()
    val clock = Clock.fixed(Instant.parse("2026-05-02T12:00:00Z"), ZoneOffset.UTC)
    val wiring = McpRuntimeWiring(
        uploadSessionStore = InMemoryUploadSessionStore(),
        uploadSegmentStore = InMemoryUploadSegmentStore(),
        artifactStore = artifactStore,
        artifactContentStore = contentStore,
        schemaStore = schemaStore,
        jobStore = InMemoryJobStore(),
        quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
        limits = McpLimitsConfig(),
        clock = clock,
    )
    val handler = McpRuntimeRegistries.defaultToolRegistry(wiring).findHandler("schema_compare")!!

    fun stage(schemaId: String, json: String) {
        val artifactId = "art-$schemaId"
        val bytes = json.toByteArray(Charsets.UTF_8)
        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId, filename = "$artifactId.json", contentType = "application/json",
                    sizeBytes = bytes.size.toLong(), sha256 = "0".repeat(64),
                    createdAt = EPOCH, expiresAt = EPOCH.plusSeconds(86_400L * 365),
                ),
                kind = ArtifactKind.SCHEMA,
                tenantId = TENANT,
                ownerPrincipalId = OWNER,
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

    fun schema(name: String, version: String, sequenceName: String?): String {
        val sequence = sequenceName?.let { ""","sequence_name":"$it"""" }.orEmpty()
        return """{"name":"$name","version":"$version","tables":{"customer":{"columns":{"id":{""" +
            """"type":"biginteger","generation":{"type":"identity","mode":"by_default"$sequence,""" +
            """"legacy_serial_syntax":true}}},"primary_key":["id"]}}}"""
    }

    fun codes(left: String, right: String): List<String> {
        fun ref(id: String) = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, id).render()
        val outcome = handler.handle(
            ToolCallContext(
                "schema_compare",
                JsonParser.parseString(
                    """{"left":{"schemaRef":"${ref(left)}"},"right":{"schemaRef":"${ref(right)}"}}""",
                ).asJsonObject,
                CALLER,
            ),
        )
        val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
        return JsonParser.parseString(text).asJsonObject.getAsJsonArray("findings")
            .map { (it as JsonObject).get("code").asString }
    }

    val reverse = ReverseScopeCodec.REVERSE_VERSION
    stage("pg", schema(ReverseScopeCodec.postgresName("shop", "public"), reverse, "public.customer_id_seq"))
    stage("my", schema(ReverseScopeCodec.mysqlName("shop"), reverse, null))
    stage("authored-pg", schema("shop", "1", "public.customer_id_seq"))
    stage("authored-my", schema("shop", "1", null))

    test("PostgreSQL reverse against MySQL reverse reports no identity change") {
        codes("pg", "my") shouldNotContain "TABLE_COLUMN_GENERATION_CHANGED"
    }

    test("two hand-written schemas with the same difference stay strict") {
        codes("authored-pg", "authored-my") shouldContain "TABLE_COLUMN_GENERATION_CHANGED"
    }
})
