package dev.dmigrate.mcp.schema

import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.AssembledUploadPayload
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainString
import java.security.MessageDigest
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
    scopes = setOf("dmigrate:artifact:upload"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private val FIXED_NOW = Instant.parse("2026-05-02T12:00:00Z")
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

private fun session(
    sizeBytes: Long,
    sessionId: String = "ups-1",
    checksumSha256: String = "0".repeat(64),
): UploadSession = UploadSession(
    uploadSessionId = sessionId,
    tenantId = ACME,
    ownerPrincipalId = ALICE,
    resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, sessionId),
    artifactKind = ArtifactKind.SCHEMA,
    mimeType = "application/json",
    sizeBytes = sizeBytes,
    segmentTotal = 1,
    checksumSha256 = checksumSha256,
    uploadIntent = "schema_staging_readonly",
    state = UploadSessionState.FINALIZING,
    createdAt = FIXED_NOW,
    updatedAt = FIXED_NOW,
    idleTimeoutAt = FIXED_NOW.plusSeconds(300),
    absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(3600),
    bytesReceived = sizeBytes,
)

private const val VALID_SCHEMA: String =
    """{"name":"orders","version":"1.0","tables":{"t1":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}}}"""

private fun finalizer(
    artifactStore: InMemoryArtifactStore = InMemoryArtifactStore(),
    contentStore: InMemoryArtifactContentStore = InMemoryArtifactContentStore(),
    schemaStore: InMemorySchemaStore = InMemorySchemaStore(),
): DefaultSchemaStagingFinalizer = DefaultSchemaStagingFinalizer(
    artifactStore = artifactStore,
    artifactContentStore = contentStore,
    schemaStore = schemaStore,
    validator = SchemaValidator(),
    clock = FIXED_CLOCK,
)

private fun payloadOf(bytes: ByteArray): AssembledUploadPayload =
    AssembledUploadPayload.fromBytes(bytes, sha256Hex(bytes))

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class SchemaStagingFinalizerTest : FunSpec({

    test("valid schema: artefact is materialised, schemaRef is registered, URI is tenant-scoped") {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val schemaStore = InMemorySchemaStore()
        val sut = finalizer(artifactStore, contentStore, schemaStore)
        val bytes = VALID_SCHEMA.toByteArray()
        val uri = sut.complete(
            session = session(bytes.size.toLong(), checksumSha256 = sha256Hex(bytes)),
            principal = PRINCIPAL,
            payload = payloadOf(bytes),
            artifactId = "art-fixed",
            schemaId = "schema-fixed",
            format = "json",
        )

        uri.tenantId shouldBe ACME
        uri.kind shouldBe ResourceKind.SCHEMAS
        uri.id shouldBe "schema-fixed"

        contentStore.exists("art-fixed") shouldBe true
        val record = artifactStore.findById(ACME, "art-fixed")!!
        record.kind shouldBe ArtifactKind.SCHEMA
        record.tenantId shouldBe ACME
        record.ownerPrincipalId shouldBe ALICE
        record.managedArtifact.sizeBytes shouldBe bytes.size.toLong()
        record.managedArtifact.sha256 shouldBe sha256Hex(bytes)

        val entry = schemaStore.findById(ACME, "schema-fixed")!!
        entry.artifactRef shouldBe "art-fixed"
        entry.displayName shouldBe "orders"
        entry.labels["uploadSessionId"] shouldBe "ups-1"
        entry.labels["ownerPrincipalId"] shouldBe ALICE.value
    }

    test("malformed JSON bytes throw VALIDATION_ERROR with field=schema (no schemaRef registered)") {
        val schemaStore = InMemorySchemaStore()
        val sut = finalizer(schemaStore = schemaStore)
        val malformed = "{ not valid json".toByteArray()
        val ex = shouldThrow<ValidationErrorException> {
            sut.complete(
                session = session(malformed.size.toLong()),
                principal = PRINCIPAL,
                payload = payloadOf(malformed),
                artifactId = "art-fixed",
                schemaId = "schema-fixed",
                format = "json",
            )
        }
        ex.violations.map { it.field } shouldContain "schema"
        schemaStore.findById(ACME, "schema-fixed") shouldBe null
    }

    test("invalid SchemaDefinition surfaces every validator error as a structured violation") {
        val schemaStore = InMemorySchemaStore()
        val sut = finalizer(schemaStore = schemaStore)
        val invalid = """{"name":"x","version":"1.0","tables":{"t1":{"columns":{}}}}""".toByteArray()
        val ex = shouldThrow<ValidationErrorException> {
            sut.complete(
                session = session(invalid.size.toLong()),
                principal = PRINCIPAL,
                payload = payloadOf(invalid),
                artifactId = "art-fixed",
                schemaId = "schema-fixed",
                format = "json",
            )
        }
        ex.violations.map { it.field } shouldContain "tables.t1"
        ex.violations.first().reason shouldContainString "E001"
        schemaStore.findById(ACME, "schema-fixed") shouldBe null
    }

    test("unknown format throws VALIDATION_ERROR with field=format") {
        val sut = finalizer()
        val bytes = VALID_SCHEMA.toByteArray()
        val ex = shouldThrow<ValidationErrorException> {
            sut.complete(
                session = session(bytes.size.toLong()),
                principal = PRINCIPAL,
                payload = payloadOf(bytes),
                artifactId = "art-fixed",
                schemaId = "schema-fixed",
                format = "toml",
            )
        }
        ex.violations.map { it.field } shouldContain "format"
    }

    test("AP 6.22: re-finalising the same payload under the same deterministic ids is idempotent") {
        // Replays after a crash carry the same deterministic
        // artifactId / schemaId derived from (tenant, sessionId,
        // payloadSha, format). The finaliser must accept
        // ArtifactContentStore.AlreadyExists + SchemaStore.AlreadyRegistered
        // as success and return the same schemaRef URI.
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val schemaStore = InMemorySchemaStore()
        val sut = finalizer(artifactStore, contentStore, schemaStore)
        val bytes = VALID_SCHEMA.toByteArray()

        val first = sut.complete(
            session = session(bytes.size.toLong()),
            principal = PRINCIPAL,
            payload = payloadOf(bytes),
            artifactId = "art-det",
            schemaId = "schema-det",
            format = "json",
        )
        val second = sut.complete(
            session = session(bytes.size.toLong()),
            principal = PRINCIPAL,
            payload = payloadOf(bytes),
            artifactId = "art-det",
            schemaId = "schema-det",
            format = "json",
        )

        first shouldBe second
        // Still exactly one persisted entry under each id.
        contentStore.exists("art-det") shouldBe true
        schemaStore.findById(ACME, "schema-det")!!.artifactRef shouldBe "art-det"
    }
})
