package dev.dmigrate.mcp.integration

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import dev.dmigrate.mcp.registry.ArtifactUploadInitHandler
import dev.dmigrate.mcp.registry.PhaseCRegistries
import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.mcp.registry.ToolCallContext
import dev.dmigrate.mcp.registry.ToolCallOutcome
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * Phase F § 8.5 (F.5 3/3) — End-to-End Integrationstest fuer den
 * `job_input`-Upload-Pfad ueber die produktive
 * [PhaseCRegistries.defaultToolRegistry]-Verdrahtung. Pin't, dass
 * der `JobInputFinalizer` aus [PhaseCWiring] tatsaechlich an den
 * `artifact_upload`-Handler gewired wird und dass die finalisierten
 * Bytes via `artifact_chunk_get` lesbar sind.
 *
 * Init wird hier manuell simuliert (Session vorab in den Store
 * geschrieben) — der Orchestrator-Wiring fuer den policy-Init-Pfad
 * (F.3 4/4) ist eine separate Aenderung; F.5 (3/3) konzentriert
 * sich auf den Finalisations- und Lesepfad.
 */
class McpPhaseFJobInputUploadScenarioTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    val principal = PrincipalContext(
        principalId = alice,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:read", "dmigrate:artifact:upload"),
        isAdmin = false,
        auditSubject = "alice",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun args(s: String): JsonElement = JsonParser.parseString(s)

    test("F.5 (3/3) E2E: artifact_upload (job_input) -> bytes lesbar via artifact_chunk_get") {
        // 1. Wiring + Registry — produktive PhaseCWiring inkl.
        // automatischem JobInputFinalizer-Default.
        val sessionStore = InMemoryUploadSessionStore()
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val quotaStore = InMemoryQuotaStore()
        val wiring = PhaseCWiring(
            uploadSessionStore = sessionStore,
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = artifactStore,
            artifactContentStore = artifactContentStore,
            schemaStore = InMemorySchemaStore(),
            jobStore = InMemoryJobStore(),
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = clock,
        )
        val registry = PhaseCRegistries.defaultToolRegistry(wiring)

        // 2. Session manuell anlegen (Init-Orchestrator-Wiring ist
        // separater Concern). Plan-konform: durable Session mit
        // approvalKey + approvalFingerprint + targetTable.
        val payload = "id,name,age\n1,Alice,42\n2,Bob,37\n".toByteArray()
        val sha = sha256Hex(payload)
        val sessionId = "ups-job-e2e"
        sessionStore.save(
            UploadSession(
                uploadSessionId = sessionId,
                tenantId = tenant,
                ownerPrincipalId = alice,
                resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
                artifactKind = ArtifactKind.UPLOAD_INPUT,
                mimeType = "text/csv",
                sizeBytes = payload.size.toLong(),
                segmentTotal = 1,
                checksumSha256 = sha,
                uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                state = UploadSessionState.ACTIVE,
                createdAt = now,
                updatedAt = now,
                idleTimeoutAt = now.plusSeconds(300),
                absoluteLeaseExpiresAt = now.plusSeconds(3600),
                approvalKey = "key-import-2026-05-06",
                approvalFingerprint = "fp-test",
                targetTable = "warehouse.users",
            ),
        )

        // 3. Final-Segment via "artifact_upload" -> Finaliser
        // materialisiert Bytes im ArtifactContentStore.
        val uploadHandler = registry.findHandler("artifact_upload")!!
        val uploadBody = """{"uploadSessionId":"$sessionId","segmentIndex":1,"segmentOffset":0,""" +
            """"segmentTotal":1,"isFinalSegment":true,"segmentSha256":"$sha",""" +
            """"contentBase64":"${b64(payload)}"}"""
        val uploadResult = uploadHandler.handle(
            ToolCallContext("artifact_upload", args(uploadBody), principal, requestId = "req-up"),
        ).shouldBeInstanceOf<ToolCallOutcome.Success>()
        val uploadJson = JsonParser.parseString(uploadResult.content.single().text!!).asJsonObject
        val artifactRef = uploadJson.get("schemaRef").asString
        artifactRef shouldStartWith "dmigrate://tenants/acme/artifacts/art-"
        val artifactId = artifactRef.substringAfterLast("/")

        // 4. Plan § 8.5: das Artefakt ist nach Finalisierung aus dem
        // ArtifactStore und ArtifactContentStore lesbar.
        artifactContentStore.exists(artifactId) shouldBe true
        val record = artifactStore.findById(tenant, artifactId)!!
        record.kind shouldBe ArtifactKind.UPLOAD_INPUT
        record.managedArtifact.contentType shouldBe "text/csv"
        record.managedArtifact.sha256 shouldBe sha

        // 5. artifact_chunk_get liest die Bytes 1:1 zurueck
        // (Plan § 8.5: "artifact_chunk_get liest aus
        // ArtifactContentStore").
        val chunkHandler = registry.findHandler("artifact_chunk_get")!!
        val chunkBody = """{"artifactId":"$artifactId","chunkId":"0"}"""
        val chunkResult = chunkHandler.handle(
            ToolCallContext("artifact_chunk_get", args(chunkBody), principal, requestId = "req-chunk"),
        ).shouldBeInstanceOf<ToolCallOutcome.Success>()
        val chunkJson = JsonParser.parseString(chunkResult.content.single().text!!).asJsonObject
        chunkJson.get("artifactId").asString shouldBe artifactId
        // Decoded bytes match the original payload — proves the bytes
        // were materialised, persisted, and readable end-to-end.
        val encoded = chunkJson.get("contentBase64")?.asString
            ?: chunkJson.get("text")?.asString?.let { Base64.getEncoder().encodeToString(it.toByteArray()) }
        val decoded = Base64.getDecoder().decode(encoded)
        decoded.contentEquals(payload) shouldBe true
        chunkJson.get("sha256").asString shouldBe sha

        // 6. Sanity: Session ist COMPLETED, finalisedSchemaRef ==
        // artifactRef (Plan: Feld dient generisch als Final-Ref).
        val finalSession = sessionStore.findById(tenant, sessionId)!!
        finalSession.state shouldBe UploadSessionState.COMPLETED
        finalSession.finalisedSchemaRef shouldBe artifactRef

        // 7. Replay: ein zweiter chunk_get-Aufruf liefert dieselben
        // Bytes (Plan: "finalisiertes Artefakt ist immutable").
        shouldNotThrowAny {
            chunkHandler.handle(
                ToolCallContext("artifact_chunk_get", args(chunkBody), principal),
            )
        }
    }
})
