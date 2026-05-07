package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.registry.ArtifactUploadInitHandler
import dev.dmigrate.mcp.registry.PhaseCRegistries
import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.transport.stdio.StdioJsonRpc
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * Phase F § 8.10 (F.10): stdio-Integrationstest fuer den
 * mehrsegmentigen `artifact_upload`-Pfad.
 *
 * Validiert, dass der NDJSON-stdio-Transport mehrere
 * `tools/call artifact_upload`-Frames hintereinander entgegennimmt,
 * jeden ueber den `ArtifactUploadHandler` der produktiven
 * [PhaseCRegistries.defaultToolRegistry]-Verdrahtung dispatcht und
 * am Final-Segment den finalisierten `UPLOAD_INPUT`-Artefakt-Ref im
 * Wire-Antwortenvelope ausweist. Init wird bewusst manuell gesetzt
 * — der `UploadInitOrchestrator`-Wiring-Carve-out (F.5 5/5) ist
 * eine separate Aenderung, der Upload-/Finalisationspfad pin't
 * sich hier ueber stdio.
 */
class McpPhaseFStdioMultiSegmentUploadIT : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-07T10:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun extractToolText(responseLine: String): JsonObject {
        // ToolsCallResult.content[0].text ist die JSON-Tool-Antwort als String;
        // unwrap das in eine JsonObject-Form.
        val response = JsonParser.parseString(responseLine).asJsonObject
        val resultObj = response.getAsJsonObject("result")
        val text = resultObj.getAsJsonArray("content")
            .get(0).asJsonObject.get("text").asString
        return JsonParser.parseString(text).asJsonObject
    }

    test("F.10: mehrsegmentiger CSV-Upload (job_input) ueber stdio liefert COMPLETED + Bytes via artifact_chunk_get") {
        // 1. PhaseCWiring + Registry — produktive Default-Verdrahtung
        // (inkl. JobInputFinalizer-Wiring aus F.5 3/3).
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
            limits = McpLimitsConfig(maxUploadSegmentBytes = 16),
            clock = clock,
        )
        val registry = PhaseCRegistries.defaultToolRegistry(wiring)

        // 2. Stdio-bound Principal — die Token-Registry haengt Phase B/C
        // an; fuer den IT genuegt das pre-bound Principal. `dmigrate:read`
        // gated den `artifact_upload`-Methoden-Eintritt, und der
        // intent-abhaengige Scope-Check im Handler erzwingt zusaetzlich
        // `dmigrate:artifact:upload` fuer `job_input`.
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

        // 3. Pre-seed: durable Session mit drei Segmenten + CSV-MIME-Type.
        // Der policy-Init-Pfad selbst ist im PhaseCRegistries-Wiring
        // noch nicht produktiv (Carve-out F.5 5/5); F.10 pin't den
        // mehrsegmentigen Upload + Finalisation, nicht den Init.
        val payload = "id,name,age\n1,Alice,42\n2,Bob,37\n4,Eve,31\n".toByteArray()
        val sha = sha256Hex(payload)
        val sessionId = "ups-stdio-multiseg"
        // Drei Segmente: 16 + 16 + Rest (segmentBytes <= maxUploadSegmentBytes=16).
        val seg1Bytes = payload.copyOfRange(0, 16)
        val seg2Bytes = payload.copyOfRange(16, 32)
        val seg3Bytes = payload.copyOfRange(32, payload.size)
        val seg1Sha = sha256Hex(seg1Bytes)
        val seg2Sha = sha256Hex(seg2Bytes)
        val seg3Sha = sha256Hex(seg3Bytes)

        sessionStore.save(
            UploadSession(
                uploadSessionId = sessionId,
                tenantId = tenant,
                ownerPrincipalId = alice,
                resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
                artifactKind = ArtifactKind.UPLOAD_INPUT,
                mimeType = "text/csv",
                sizeBytes = payload.size.toLong(),
                segmentTotal = 3,
                checksumSha256 = sha,
                uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                state = UploadSessionState.ACTIVE,
                createdAt = now,
                updatedAt = now,
                idleTimeoutAt = now.plusSeconds(300),
                absoluteLeaseExpiresAt = now.plusSeconds(3600),
                approvalKey = "key-stdio-multiseg",
                approvalFingerprint = "fp-stdio",
                targetTable = "warehouse.users",
            ),
        )

        // 4. NDJSON-Frames fuer initialize + drei artifact_upload-Aufrufe + 1 chunk_get.
        fun initFrame(): String =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
                """"clientInfo":{"name":"stdio-it","version":"0.9.6"},"capabilities":{}}}"""
        fun uploadFrame(id: Int, segIndex: Int, segOffset: Int, isFinal: Boolean, segSha: String, bytes: ByteArray): String {
            val body = """{"uploadSessionId":"$sessionId","segmentIndex":$segIndex,"segmentOffset":$segOffset,""" +
                """"segmentTotal":3,"isFinalSegment":$isFinal,"segmentSha256":"$segSha",""" +
                """"contentBase64":"${b64(bytes)}"}"""
            // ToolsCallParams.arguments is a JsonElement — embed inline.
            return """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"artifact_upload","arguments":$body}}"""
        }

        val frames = listOf(
            initFrame(),
            uploadFrame(2, 1, 0, false, seg1Sha, seg1Bytes),
            uploadFrame(3, 2, 16, false, seg2Sha, seg2Bytes),
            uploadFrame(4, 3, 32, true, seg3Sha, seg3Bytes),
        )
        val ndjson = frames.joinToString("\n", postfix = "\n")

        val input = ByteArrayInputStream(ndjson.toByteArray(StandardCharsets.UTF_8))
        val output = ByteArrayOutputStream()
        val service = McpServiceImpl(
            serverVersion = "0.9.6-it",
            toolRegistry = registry,
            initialPrincipal = principal,
        )
        val rpc = StdioJsonRpc(input, output, service)
        rpc.start()

        val deadline = System.currentTimeMillis() + 10_000
        // Vier Antwortzeilen erwartet: 1× initialize + 3× artifact_upload.
        while (output.toString(StandardCharsets.UTF_8).count { it == '\n' } < 4 &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        rpc.stop()

        val responses = output.toString(StandardCharsets.UTF_8)
            .split('\n').filter { it.isNotBlank() }
        responses.size shouldBe 4

        // Initialize-Antwort: protocolVersion echo + serverInfo.
        val init = JsonParser.parseString(responses[0]).asJsonObject
        init.get("id").asInt shouldBe 1
        init.getAsJsonObject("result").get("protocolVersion").asString shouldBe "2025-11-25"

        // Segment 1: ACTIVE, deduplicated=false, bytesReceived=16.
        val seg1Result = extractToolText(responses[1])
        seg1Result.get("uploadSessionState").asString shouldBe "ACTIVE"
        seg1Result.get("acceptedSegmentIndex").asInt shouldBe 1
        seg1Result.get("deduplicated").asBoolean shouldBe false
        seg1Result.get("bytesReceived").asLong shouldBe 16L

        // Segment 2: ACTIVE, bytesReceived=32.
        val seg2Result = extractToolText(responses[2])
        seg2Result.get("uploadSessionState").asString shouldBe "ACTIVE"
        seg2Result.get("acceptedSegmentIndex").asInt shouldBe 2
        seg2Result.get("bytesReceived").asLong shouldBe 32L

        // Segment 3 (final): COMPLETED + schemaRef-URI (generischer
        // Final-Ref fuer job_input, Plan § 8.5).
        val seg3Result = extractToolText(responses[3])
        seg3Result.get("uploadSessionState").asString shouldBe "COMPLETED"
        seg3Result.get("acceptedSegmentIndex").asInt shouldBe 3
        seg3Result.get("bytesReceived").asLong shouldBe payload.size.toLong()
        val artifactRef = seg3Result.get("schemaRef").asString
        artifactRef shouldStartWith "dmigrate://tenants/acme/artifacts/art-"

        // 5. Persistente Wirkung: Session COMPLETED, Bytes im
        // ContentStore, Record im ArtifactStore mit `text/csv`.
        val artifactId = artifactRef.substringAfterLast("/")
        artifactContentStore.exists(artifactId) shouldBe true
        val record = artifactStore.findById(tenant, artifactId)!!
        record.kind shouldBe ArtifactKind.UPLOAD_INPUT
        record.managedArtifact.contentType shouldBe "text/csv"
        record.managedArtifact.sha256 shouldBe sha
        record.managedArtifact.sizeBytes shouldBe payload.size.toLong()

        sessionStore.findById(tenant, sessionId)!!.state shouldBe UploadSessionState.COMPLETED

        // 6. Replay-Frame: artifact_chunk_get ueber dieselbe stdio-
        // Session waere ein zweiter Lifecycle (StdioJsonRpc verbraucht
        // den InputStream einmalig). Statt einer zweiten stdio-Sitzung
        // pruefen wir den Round-Trip ueber den bereits gewireten
        // chunk_get-Handler direkt — Plan-§-8.5-Akzeptanz: "bytes
        // lesbar via artifact_chunk_get" haengt nicht am Transport.
        val chunkHandler = registry.findHandler("artifact_chunk_get")!!
        val chunkBody = JsonParser.parseString(
            """{"artifactId":"$artifactId","chunkId":"0"}""",
        )
        val chunkOutcome = chunkHandler.handle(
            dev.dmigrate.mcp.registry.ToolCallContext(
                "artifact_chunk_get", chunkBody, principal, requestId = "req-chunk",
            ),
        ) as dev.dmigrate.mcp.registry.ToolCallOutcome.Success
        val chunkJson = JsonParser.parseString(chunkOutcome.content.single().text!!).asJsonObject
        val encoded = chunkJson.get("contentBase64")?.asString
            ?: chunkJson.get("text")?.asString?.let { Base64.getEncoder().encodeToString(it.toByteArray()) }
        val decoded = Base64.getDecoder().decode(encoded)
        decoded.contentEquals(payload) shouldBe true
        chunkJson.get("sha256").asString shouldBe sha
    }
})
