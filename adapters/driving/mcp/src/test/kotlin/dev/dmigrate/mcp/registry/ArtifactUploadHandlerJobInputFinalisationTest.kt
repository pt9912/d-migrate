package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.upload.DefaultJobInputFinalizer
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.FinalizationOutcomeStatus
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * LF-010 / LF-013 / LN-009 / LN-011 § 8.5 (F.5 2/3) — pin't den End-zu-End-`job_input`-
 * Finalisations-Pfad ueber den `ArtifactUploadHandler`:
 *
 * 1. Handler dispatcht anhand `session.uploadIntent` auf den
 *    [DefaultJobInputFinalizer] (kein Schema-Parse).
 * 2. Bytes landen in [dev.dmigrate.server.ports.ArtifactContentStore]
 *    (deterministischer `art-...`-Id).
 * 3. [dev.dmigrate.server.ports.ArtifactStore] traegt einen Record
 *    mit `kind=session.artifactKind` + `contentType=session.mimeType`.
 * 4. Session ist `COMPLETED`, `finalizationOutcome.status=SUCCEEDED`,
 *    `schemaId=null` (LF-012 / LN-011 / LN-017 / LN-027: kein Schema fuer job_input).
 * 5. Antwort traegt den artifactRef im (generischen)
 *    `schemaRef`-Wirefeld (Feld dient als Final-Ref).
 */
class ArtifactUploadHandlerJobInputFinalisationTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    val uploader = PrincipalContext(
        principalId = alice,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:artifact:upload"),
        isAdmin = false,
        auditSubject = "alice",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    class Fixture(
        sessionId: String = "ups-job",
        sizeBytes: Long,
        checksum: String,
        mimeType: String = "text/csv",
    ) {
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val quotaStore = InMemoryQuotaStore()
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxUploadSegmentBytes = 64),
            options = ArtifactUploadHandler.Options(
                clock = clock,
                jobInputFinalizer = DefaultJobInputFinalizer(
                    artifactStore = artifactStore,
                    artifactContentStore = contentStore,
                    clock = clock,
                ),
            ),
        )

        init {
            sessionStore.save(
                UploadSession(
                    uploadSessionId = sessionId,
                    tenantId = tenant,
                    ownerPrincipalId = alice,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, sessionId),
                    artifactKind = ArtifactKind.UPLOAD_INPUT,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    segmentTotal = 1,
                    checksumSha256 = checksum,
                    uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                    state = UploadSessionState.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                    idleTimeoutAt = now.plusSeconds(300),
                    absoluteLeaseExpiresAt = now.plusSeconds(3600),
                ),
            )
        }
    }

    fun args(s: String) = JsonParser.parseString(s).asJsonObject

    test("job_input final segment -> bytes in ContentStore + Record in ArtifactStore + COMPLETED + SUCCEEDED outcome") {
        val payload = "id,name\n1,Alice\n2,Bob\n".toByteArray()
        val sha = sha256Hex(payload)
        val fx = Fixture(sizeBytes = payload.size.toLong(), checksum = sha)

        val body = """{"uploadSessionId":"ups-job","segmentIndex":1,"segmentOffset":0,""" +
            """"segmentTotal":1,"isFinalSegment":true,"segmentSha256":"$sha",""" +
            """"contentBase64":"${b64(payload)}"}"""
        val outcome = fx.handler.handle(
            ToolCallContext("artifact_upload", args(body), uploader, requestId = "req-job-1"),
        )
        val response = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val json = JsonParser.parseString(response.content.single().text!!).asJsonObject
        json.get("uploadSessionState").asString shouldBe "COMPLETED"
        // LF-012 / LN-011 / LN-017 / LN-027: das `schemaRef`-Wirefeld dient generisch als
        // Final-Ref. Fuer job_input ist es der artifactRef-URI.
        val artifactRef = json.get("schemaRef").asString
        artifactRef shouldStartWith "dmigrate://tenants/acme/artifacts/art-"

        // Bytes sind in ArtifactContentStore.
        val artifactId = artifactRef.substringAfterLast("/")
        fx.contentStore.exists(artifactId) shouldBe true

        // Record in ArtifactStore traegt session-spezifische Felder.
        val record = fx.artifactStore.findById(tenant, artifactId).shouldNotBeNull()
        record.kind shouldBe ArtifactKind.UPLOAD_INPUT
        record.managedArtifact.contentType shouldBe "text/csv"
        record.managedArtifact.sha256 shouldBe sha
        record.managedArtifact.sizeBytes shouldBe payload.size.toLong()
        record.managedArtifact.filename shouldBe "upload-ups-job-$artifactId.bin"

        // Session-State: COMPLETED + SUCCEEDED-Outcome, schemaId=null.
        val session = fx.sessionStore.findById(tenant, "ups-job").shouldNotBeNull()
        session.state shouldBe UploadSessionState.COMPLETED
        session.finalisedSchemaRef shouldBe artifactRef
        val finalisation = session.finalizationOutcome.shouldNotBeNull()
        finalisation.status shouldBe FinalizationOutcomeStatus.SUCCEEDED
        finalisation.schemaId.shouldBeNull()
        finalisation.payloadSha256 shouldBe sha
    }

    test("LF-010 / LF-013 / LN-009 / LN-011 § 8.10 (F.10): application/csv und text/csv liefern denselben artifactId (format=csv)") {
        // CSV-Import-Artefakte sind in LF-010 / LF-013 / LN-009 / LN-011 erlaubt; beide
        // MIME-Allowlist-Schreibweisen muessen serverseitig auf
        // dasselbe `format=csv` normalisieren, damit Caller mit
        // `application/csv` denselben deterministischen `art-...`-Id
        // wiederbekommen wie mit `text/csv`. `idMaterial` =
        // "tenant|sessionId|payloadSha|format" — bei identischem
        // Payload + Session bleibt nur `format` als variable Achse.
        val payload = "id,name\n1,Alice\n".toByteArray()
        val sha = sha256Hex(payload)
        val body = """{"uploadSessionId":"ups-job","segmentIndex":1,"segmentOffset":0,""" +
            """"segmentTotal":1,"isFinalSegment":true,"segmentSha256":"$sha",""" +
            """"contentBase64":"${b64(payload)}"}"""

        val textCsv = Fixture(sizeBytes = payload.size.toLong(), checksum = sha, mimeType = "text/csv")
        val textOutcome = textCsv.handler.handle(
            ToolCallContext("artifact_upload", args(body), uploader, requestId = "req-text"),
        ).shouldBeInstanceOf<ToolCallOutcome.Success>()
        val textRef = JsonParser.parseString(textOutcome.content.single().text!!).asJsonObject
            .get("schemaRef").asString

        val appCsv = Fixture(sizeBytes = payload.size.toLong(), checksum = sha, mimeType = "application/csv")
        val appOutcome = appCsv.handler.handle(
            ToolCallContext("artifact_upload", args(body), uploader, requestId = "req-app"),
        ).shouldBeInstanceOf<ToolCallOutcome.Success>()
        val appRef = JsonParser.parseString(appOutcome.content.single().text!!).asJsonObject
            .get("schemaRef").asString

        appRef shouldBe textRef
        // contentType bleibt 1:1 erhalten — Wire-Klient sieht volle Information.
        val appArtifactId = appRef.substringAfterLast("/")
        appCsv.artifactStore.findById(tenant, appArtifactId)!!
            .managedArtifact.contentType shouldBe "application/csv"
    }

    test("job_input ohne JobInputFinalizer-Wiring faellt auf legacy COMPLETED ohne Materialise") {
        // Simuliert Test-Konstellation, in der der Handler ohne
        // jobInputFinalizer konfiguriert ist (z.B. F.5-(2/3)-Edge-
        // Tests vor F.5 (3/3)-Wiring). vertragskonform: keine Bytes,
        // kein Record — aber Session wird trotzdem COMPLETED
        // (Bestands-Tests bleiben gruen).
        val payload = "x".toByteArray()
        val sha = sha256Hex(payload)
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxUploadSegmentBytes = 16),
            options = ArtifactUploadHandler.Options(clock = clock),
        )
        sessionStore.save(
            UploadSession(
                uploadSessionId = "ups-job-2",
                tenantId = tenant,
                ownerPrincipalId = alice,
                resourceUri = ServerResourceUri(tenant, ResourceKind.UPLOAD_SESSIONS, "ups-job-2"),
                artifactKind = ArtifactKind.UPLOAD_INPUT,
                mimeType = "application/octet-stream",
                sizeBytes = payload.size.toLong(),
                segmentTotal = 1,
                checksumSha256 = sha,
                uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                state = UploadSessionState.ACTIVE,
                createdAt = now,
                updatedAt = now,
                idleTimeoutAt = now.plusSeconds(300),
                absoluteLeaseExpiresAt = now.plusSeconds(3600),
            ),
        )
        val body = """{"uploadSessionId":"ups-job-2","segmentIndex":1,"segmentOffset":0,""" +
            """"segmentTotal":1,"isFinalSegment":true,"segmentSha256":"$sha",""" +
            """"contentBase64":"${b64(payload)}"}"""
        handler.handle(ToolCallContext("artifact_upload", args(body), uploader))

        // Vertrag: Session COMPLETED, aber ohne Artefakt (Wiring fehlt).
        sessionStore.findById(tenant, "ups-job-2")!!.state shouldBe UploadSessionState.COMPLETED
        artifactStore.list(tenant, dev.dmigrate.server.core.pagination.PageRequest(pageSize = 10)).items shouldBe emptyList()
        contentStore.exists("any") shouldBe false
    }
})
