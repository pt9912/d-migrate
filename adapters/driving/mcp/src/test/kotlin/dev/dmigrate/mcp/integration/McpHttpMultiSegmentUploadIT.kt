package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.auth.DisabledAuthValidator
import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.registry.ArtifactUploadInitHandler
import dev.dmigrate.mcp.registry.McpRuntimeRegistries
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.transport.http.installMcpHttpRoute
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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * LF-010 / LF-013 / LN-009 / LN-011: HTTP-Integrationstest fuer den
 * mehrsegmentigen `artifact_upload`-Pfad ueber `contentBase64`.
 *
 * Pin't, dass das streambare HTTP-Transport keinen separaten
 * binaeren Upload-Body braucht (LF-012 / LN-038isclaimer 0.9.7: alle
 * Segmentbytes fliessen ueber JSON-RPC-`contentBase64`). Die
 * MCP-Session bleibt zwischen den Segment-POSTs persistent
 * (`MCP-Session-Id`-Header), und die finale Wirkung
 * (`UPLOAD_INPUT`-Artefakt + Bytes im ArtifactContentStore) ist
 * ueber denselben `McpRuntimeWiring`-Stores beobachtbar wie der
 * stdio-Pfad.
 */
class McpHttpMultiSegmentUploadIT : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-07T11:00:00Z")
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

    fun HttpRequestBuilder.mcpAccept() {
        headers { append(HttpHeaders.Accept, "application/json, text/event-stream") }
    }

    fun extractToolText(responseBody: String): JsonObject {
        val response = JsonParser.parseString(responseBody).asJsonObject
        val resultObj = response.getAsJsonObject("result")
        val text = resultObj.getAsJsonArray("content")
            .get(0).asJsonObject.get("text").asString
        return JsonParser.parseString(text).asJsonObject
    }

    test("LF-010 / LF-013 / LN-009 / LN-011: mehrsegmentiger CSV-Upload (job_input) ueber HTTP/contentBase64 finalisiert COMPLETED") {
        val sessionStore = InMemoryUploadSessionStore()
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val quotaStore = InMemoryQuotaStore()
        val wiring = McpRuntimeWiring(
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
        val registry = McpRuntimeRegistries.defaultToolRegistry(wiring)

        // Pre-seed: durable Session mit drei Segmenten + application/csv-MIME.
        // CSV ist seit LF-010 / LF-013 / LN-009 / LN-011 erlaubt; sowohl `text/csv` als auch
        // `application/csv` werden serverseitig auf `format=csv`
        // normalisiert (vgl. LF-010 / LF-013 / LN-009 / LN-011 und ArtifactUploadHandler.formatFromMimeType).
        val payload = "id,name,age\n1,Alice,42\n2,Bob,37\n4,Eve,31\n".toByteArray()
        val sha = sha256Hex(payload)
        val sessionId = "ups-http-multiseg"
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
                mimeType = "application/csv",
                sizeBytes = payload.size.toLong(),
                segmentTotal = 3,
                checksumSha256 = sha,
                uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                state = UploadSessionState.ACTIVE,
                createdAt = now,
                updatedAt = now,
                idleTimeoutAt = now.plusSeconds(300),
                absoluteLeaseExpiresAt = now.plusSeconds(3600),
                approvalKey = "key-http-multiseg",
                approvalFingerprint = "fp-http",
                targetTable = "warehouse.users",
            ),
        )

        val cfg = McpServerConfig(authMode = AuthMode.DISABLED)
        val authOverride = DisabledAuthValidator(principal = principal)

        testApplication {
            application {
                installMcpHttpRoute(
                    config = cfg,
                    serviceFactory = {
                        McpServiceImpl(
                            serverVersion = "0.9.7-it",
                            toolRegistry = registry,
                            initialPrincipal = principal,
                        )
                    },
                    authValidatorOverride = authOverride,
                )
            }

            // 1. Initialize -> erhaelt MCP-Session-Id + MCP-Protocol-Version.
            val initBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
                """{"protocolVersion":"2025-11-25","clientInfo":{"name":"http-it","version":"0.9.7"},"capabilities":{}}}"""
            val initResp = client.post("/mcp") {
                mcpAccept()
                setBody(initBody)
            }
            initResp.status shouldBe HttpStatusCode.OK
            val mcpSessionId = initResp.headers["MCP-Session-Id"]!!
            val protocolVersion = initResp.headers["MCP-Protocol-Version"]!!
            protocolVersion shouldBe McpProtocol.MCP_PROTOCOL_VERSION

            // 2. Drei artifact_upload-POSTs mit demselben Session-Id-Header.
            //    `contentBase64` ist der einzige Bytetransport — kein
            //    binaerer Body, kein Multipart (LF-012 / LN-038isclaimer 0.9.7).
            fun uploadCall(id: Int, segIndex: Int, segOffset: Int, isFinal: Boolean, segSha: String, bytes: ByteArray): String {
                val args = """{"uploadSessionId":"$sessionId","segmentIndex":$segIndex,"segmentOffset":$segOffset,""" +
                    """"segmentTotal":3,"isFinalSegment":$isFinal,"segmentSha256":"$segSha",""" +
                    """"contentBase64":"${b64(bytes)}"}"""
                return """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"artifact_upload","arguments":$args}}"""
            }

            suspend fun postUpload(body: String): String {
                val resp = client.post("/mcp") {
                    mcpAccept()
                    headers {
                        append("MCP-Session-Id", mcpSessionId)
                        append("MCP-Protocol-Version", protocolVersion)
                    }
                    setBody(body)
                }
                resp.status shouldBe HttpStatusCode.OK
                return resp.bodyAsText()
            }

            val seg1Resp = postUpload(uploadCall(2, 1, 0, false, seg1Sha, seg1Bytes))
            val seg2Resp = postUpload(uploadCall(3, 2, 16, false, seg2Sha, seg2Bytes))
            val seg3Resp = postUpload(uploadCall(4, 3, 32, true, seg3Sha, seg3Bytes))

            // Segment 1 + 2: ACTIVE.
            extractToolText(seg1Resp).get("uploadSessionState").asString shouldBe "ACTIVE"
            extractToolText(seg1Resp).get("bytesReceived").asLong shouldBe 16L
            extractToolText(seg2Resp).get("uploadSessionState").asString shouldBe "ACTIVE"
            extractToolText(seg2Resp).get("bytesReceived").asLong shouldBe 32L

            // Segment 3: COMPLETED + schemaRef-URI fuer den finalisierten Artefakt.
            val seg3Json = extractToolText(seg3Resp)
            seg3Json.get("uploadSessionState").asString shouldBe "COMPLETED"
            seg3Json.get("bytesReceived").asLong shouldBe payload.size.toLong()
            val artifactRef = seg3Json.get("schemaRef").asString
            artifactRef shouldStartWith "dmigrate://tenants/acme/artifacts/art-"

            // 3. Persistente Wirkung pin't durch denselben McpRuntimeWiring-Store.
            val artifactId = artifactRef.substringAfterLast("/")
            artifactContentStore.exists(artifactId) shouldBe true
            val record = artifactStore.findById(tenant, artifactId)!!
            record.kind shouldBe ArtifactKind.UPLOAD_INPUT
            record.managedArtifact.contentType shouldBe "application/csv"
            record.managedArtifact.sha256 shouldBe sha
            sessionStore.findById(tenant, sessionId)!!.state shouldBe UploadSessionState.COMPLETED
        }
    }
})
