package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.ResourceNotFoundException
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
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

private val ACME = TenantId("acme")
private val ALICE = PrincipalId("alice")
private val BOB = PrincipalId("bob")

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

private val ADMIN = PRINCIPAL.copy(isAdmin = true)
private val FIXED_NOW: Instant = Instant.parse("2026-05-02T12:00:00Z")

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private class ChunkFixture(
    val handler: ArtifactChunkGetHandler,
    val artifactStore: InMemoryArtifactStore,
    val contentStore: InMemoryArtifactContentStore,
)

private fun fixture(maxChunkBytes: Int = 8): ChunkFixture {
    val artifactStore = InMemoryArtifactStore()
    val contentStore = InMemoryArtifactContentStore()
    val handler = ArtifactChunkGetHandler(
        artifactStore = artifactStore,
        contentStore = contentStore,
        limits = McpLimitsConfig(maxArtifactChunkBytes = maxChunkBytes),
        requestIdProvider = { "req-deadbeef" },
    )
    return ChunkFixture(handler, artifactStore, contentStore)
}

private fun stageArtifact(
    f: ChunkFixture,
    artifactId: String = "art-1",
    bytes: ByteArray = "abcdefghij".toByteArray(),
    contentType: String = "application/json",
    visibility: JobVisibility = JobVisibility.TENANT,
    owner: PrincipalId = ALICE,
): ArtifactRecord {
    f.contentStore.write(artifactId, ByteArrayInputStream(bytes), bytes.size.toLong())
    val record = ArtifactRecord(
        managedArtifact = ManagedArtifact(
            artifactId = artifactId,
            filename = "$artifactId.bin",
            contentType = contentType,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
            createdAt = FIXED_NOW,
            expiresAt = FIXED_NOW.plusSeconds(3600),
        ),
        kind = ArtifactKind.SCHEMA,
        tenantId = ACME,
        ownerPrincipalId = owner,
        visibility = visibility,
        resourceUri = ServerResourceUri(ACME, ResourceKind.ARTIFACTS, artifactId),
    )
    f.artifactStore.save(record)
    return record
}

private fun args(content: String): JsonObject = JsonParser.parseString(content).asJsonObject

private fun parsePayload(outcome: ToolCallOutcome): JsonObject {
    val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
    return JsonParser.parseString(text).asJsonObject
}

class ArtifactChunkGetHandlerTest : FunSpec({

    test("first chunk of a small text artifact returns encoding=text and inline text content") {
        val payload = """{"name":"orders"}"""
        val f = fixture(maxChunkBytes = 32)
        stageArtifact(f, "art-1", payload.toByteArray(), contentType = "application/json")
        val outcome = f.handler.handle(
            ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-1"}"""), PRINCIPAL),
        )
        val json = parsePayload(outcome)
        json.get("artifactId").asString shouldBe "art-1"
        json.get("resourceUri").asString shouldBe "dmigrate://tenants/acme/artifacts/art-1"
        json.get("chunkId").asString shouldBe "0"
        json.get("offset").asLong shouldBe 0L
        json.get("lengthBytes").asInt shouldBe payload.length
        json.get("contentType").asString shouldBe "application/json"
        json.get("encoding").asString shouldBe "text"
        json.get("text").asString shouldBe payload
        json.has("contentBase64") shouldBe false
        json.has("nextChunkUri") shouldBe false
        json.get("sha256").asString shouldBe sha256Hex(payload.toByteArray())
        json.getAsJsonObject("executionMeta").get("requestId").asString shouldBe "req-deadbeef"
    }

    test("binary contentType returns encoding=base64 with contentBase64 (no text field)") {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0x10)
        val f = fixture(maxChunkBytes = 32)
        stageArtifact(f, "art-bin", bytes, contentType = "application/octet-stream")
        val outcome = f.handler.handle(
            ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-bin"}"""), PRINCIPAL),
        )
        val json = parsePayload(outcome)
        json.get("encoding").asString shouldBe "base64"
        json.get("contentBase64").asString shouldBe Base64.getEncoder().encodeToString(bytes)
        json.has("text") shouldBe false
        json.get("sha256").asString shouldBe sha256Hex(bytes)
    }

    test("multi-chunk traversal: nextChunkUri is set on intermediate chunks, null on the last") {
        // 10-byte payload, 4-byte chunks → 3 chunks (4 + 4 + 2).
        val payload = "abcdefghij".toByteArray()
        val f = fixture(maxChunkBytes = 4)
        stageArtifact(f, "art-1", payload)

        val first = parsePayload(
            f.handler.handle(
                ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-1"}"""), PRINCIPAL),
            ),
        )
        first.get("chunkId").asString shouldBe "0"
        first.get("offset").asLong shouldBe 0L
        first.get("lengthBytes").asInt shouldBe 4
        first.get("nextChunkUri").asString shouldBe
            "dmigrate://tenants/acme/artifacts/art-1/chunks/1"

        val second = parsePayload(
            f.handler.handle(
                ToolCallContext(
                    "artifact_chunk_get",
                    args("""{"artifactId":"art-1","chunkId":"1"}"""),
                    PRINCIPAL,
                ),
            ),
        )
        second.get("offset").asLong shouldBe 4L
        second.get("lengthBytes").asInt shouldBe 4

        val third = parsePayload(
            f.handler.handle(
                ToolCallContext(
                    "artifact_chunk_get",
                    args("""{"artifactId":"art-1","chunkId":"2"}"""),
                    PRINCIPAL,
                ),
            ),
        )
        third.get("offset").asLong shouldBe 8L
        third.get("lengthBytes").asInt shouldBe 2
        third.has("nextChunkUri") shouldBe false
    }

    test("missing artifact throws RESOURCE_NOT_FOUND") {
        val f = fixture()
        shouldThrow<ResourceNotFoundException> {
            f.handler.handle(
                ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-missing"}"""), PRINCIPAL),
            )
        }
    }

    test("artifact in different tenant maps to RESOURCE_NOT_FOUND (no-oracle)") {
        // The store is tenant-scoped; a foreign-tenant lookup
        // returns null even if the bytes physically exist for
        // another tenant. The handler surfaces the same
        // RESOURCE_NOT_FOUND as a missing id in the caller's
        // tenant.
        val f = fixture()
        // Stage an artifact in a different tenant by writing
        // directly to the content store without registering a
        // record via ArtifactStore in our tenant.
        f.contentStore.write("art-foreign", ByteArrayInputStream("x".toByteArray()), 1)
        shouldThrow<ResourceNotFoundException> {
            f.handler.handle(
                ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-foreign"}"""), PRINCIPAL),
            )
        }
    }

    test("OWNER-only artifact is invisible to a non-owner principal — RESOURCE_NOT_FOUND") {
        val f = fixture()
        stageArtifact(f, "art-1", visibility = JobVisibility.OWNER, owner = BOB)
        shouldThrow<ResourceNotFoundException> {
            f.handler.handle(
                ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-1"}"""), PRINCIPAL),
            )
        }
    }

    test("ADMIN-visibility artifact is readable to admin principals") {
        val f = fixture()
        stageArtifact(f, "art-1", "x".toByteArray(), visibility = JobVisibility.ADMIN)
        val outcome = f.handler.handle(
            ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-1"}"""), ADMIN),
        )
        parsePayload(outcome).get("artifactId").asString shouldBe "art-1"
    }

    test("invalid chunkId (non-numeric) throws VALIDATION_ERROR with field=chunkId") {
        val f = fixture()
        stageArtifact(f, "art-1")
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_chunk_get",
                    args("""{"artifactId":"art-1","chunkId":"abc"}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "chunkId"
    }

    test("negative chunkId throws VALIDATION_ERROR") {
        val f = fixture()
        stageArtifact(f, "art-1")
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_chunk_get",
                    args("""{"artifactId":"art-1","chunkId":"-1"}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "chunkId"
    }

    test("out-of-range chunkId for an existing artifact throws RESOURCE_NOT_FOUND") {
        // 8-byte payload, 8-byte chunks → only chunk 0 exists.
        val f = fixture(maxChunkBytes = 8)
        stageArtifact(f, "art-1", "12345678".toByteArray())
        shouldThrow<ResourceNotFoundException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_chunk_get",
                    args("""{"artifactId":"art-1","chunkId":"5"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("text/* content types map to encoding=text") {
        val f = fixture(maxChunkBytes = 32)
        stageArtifact(f, "art-1", "hello".toByteArray(), contentType = "text/plain")
        val outcome = f.handler.handle(
            ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-1"}"""), PRINCIPAL),
        )
        parsePayload(outcome).get("encoding").asString shouldBe "text"
    }

    test("contentType with charset parameter is normalised before the text/binary decision") {
        // `application/json; charset=utf-8` is canonical for JSON
        // artifacts emitted by AP 6.5/6.9. Strip the parameter
        // before classifying.
        val f = fixture(maxChunkBytes = 32)
        stageArtifact(f, "art-1", "hello".toByteArray(), contentType = "application/json; charset=utf-8")
        val outcome = f.handler.handle(
            ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-1"}"""), PRINCIPAL),
        )
        parsePayload(outcome).get("encoding").asString shouldBe "text"
    }

    test("missing artifactId throws VALIDATION_ERROR") {
        val f = fixture()
        shouldThrow<ValidationErrorException> {
            f.handler.handle(ToolCallContext("artifact_chunk_get", args("""{}"""), PRINCIPAL))
        }
    }

    test("chunk size strictly capped at maxArtifactChunkBytes regardless of artifact size") {
        // 100-byte payload, 10-byte chunks → first chunk is exactly
        // 10 bytes; nextChunkUri continues from chunk 1.
        val payload = ByteArray(100) { (it % 26 + 'a'.code).toByte() }
        val f = fixture(maxChunkBytes = 10)
        stageArtifact(f, "art-1", payload, contentType = "application/json")
        val first = parsePayload(
            f.handler.handle(
                ToolCallContext("artifact_chunk_get", args("""{"artifactId":"art-1"}"""), PRINCIPAL),
            ),
        )
        first.get("lengthBytes").asInt shouldBe 10
        first.get("nextChunkUri").asString shouldStartWith
            "dmigrate://tenants/acme/artifacts/art-1/chunks/1"
    }
})
