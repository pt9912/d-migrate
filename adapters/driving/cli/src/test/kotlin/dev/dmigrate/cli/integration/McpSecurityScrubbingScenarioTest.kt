package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.core.job.JobError
import dev.dmigrate.server.core.job.JobProgress
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E8(B): security-scrubbing canary per
 * `ImpPlan-0.9.6-C.md` §6.24 Akzeptanz Z. 2086-2091.
 *
 * Plants three deterministic fixture secrets — a Bearer token, a
 * JDBC URL with embedded credentials, and an approval-token-shaped
 * string — into job state the Phase-C handlers project back onto
 * the wire (`progress.phase`, `error.code`, `error.message`).
 * Drives `job_status_get` and `resources/read` end-to-end on stdio
 * AND http and asserts the planted raw values NEVER appear in:
 *
 * - the JSON-RPC `result` payload of either tool (the wire-visible
 *   `content[0].text`)
 * - any `AuditEvent` recorded by the per-transport audit sink
 *   (resourceRefs / errorCode / fingerprint surface)
 *
 * Pattern coverage matches `SecretScrubber.scrub(...)`:
 * - `Bearer <token>` → `Bearer ***`
 * - `tok_<base62>` → `***`
 * - JDBC `user:pwd@host` and `?password=…` → `***`
 *
 * If a future handler bypasses [SecretScrubber] on a covered field,
 * this test surfaces it as a wire leak — no heuristic regex
 * involved, only the deterministic fixture rawvalues are scanned.
 *
 * Out of scope for this commit (deferred to follow-up):
 * - stderr scrubbing (the harness does not capture server stderr)
 * - HTTP error-body scrubbing (the harness throws on non-2xx so
 *   error bodies are not currently inspected)
 * - artifact-content scrubbing (large artefact bodies have their
 *   own scrubbing pipeline and need their own scenario)
 *
 * The complementary "what's NOT scrubbed by design" boundary
 * (generic passwords, local paths, arbitrary canaries) is covered
 * by `SchemaSecretGuard` at schema-construction time and not
 * exercised here.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpSecurityScrubbingScenarioTest : FunSpec({

    tags(IntegrationTag)

    test("job_status_get scrubs planted Bearer / JDBC URL / tok_ values on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val tenant = harness.principal.effectiveTenantId.value
                val jobId = "job-e8b-${harness.name}"
                stageJobWithSecrets(harness, jobId)

                val payload = parsePayload(
                    harness.toolsCall(
                        "job_status_get",
                        JsonObject().apply { addProperty("jobId", jobId) },
                    ).text(),
                )

                val raw = payload.toString()
                assertNoSecretLeaks(harness.name, "job_status_get", raw)
                assertAuditSinkNoLeaks(harness, harness.name, "job_status_get")

                // Sanity: the projection MUST still surface the
                // structural fields — proving the scrubber redacted
                // VALUES, not stripped the whole projection.
                payload.get("status").asString shouldBe "FAILED"
                payload.get("error").asJsonObject.get("code").asString.let {
                    withClue("$tenant/${harness.name} error.code must remain present, scrubbed") {
                        it shouldBe "DRIVER" // pure-letter code untouched by all three regexes
                    }
                }
            }
        }
    }

    test("resources/read on an artifact scrubs planted secrets from filename AND contentType") {
        // AP 6.24 final-review: contentType is operator-supplied via
        // `artifact_upload_init` headers — same scrubbing surface as
        // filename. A planted Bearer/JDBC/tok_ value in contentType
        // must not leak through resources/read.
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val tenant = harness.principal.effectiveTenantId.value
                val artifactId = "art-e8b-ct-${harness.name}"
                stageArtifactWithSecretsInMetadata(harness, artifactId)

                val response = harness.resourcesReadRaw(
                    "dmigrate://tenants/$tenant/artifacts/$artifactId",
                )
                val resultEl = response.result
                    ?: error("${harness.name}: resources/read returned an error: ${response.error}")
                val text = resultEl.asJsonObject.getAsJsonArray("contents")
                    .get(0).asJsonObject.get("text").asString
                assertNoSecretLeaks(harness.name, "resources/read[artifact]", text)
            }
        }
    }

    test("HTTP error body scrubs a planted Bearer in a rejected Origin header") {
        // §6.24 final-review: McpHttpRoute echoes the rejected
        // Origin string into the 403 response body. A client that
        // sends `Origin: Bearer <token>` would otherwise see the
        // raw token reflected back. Scan the rejection body for
        // every fixture secret fragment.
        withFreshTransports { _, h ->
            val (status, body) = h.postUnchecked(
                """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
                extraHeaders = mapOf("Origin" to "http://$BEARER_CANARY"),
            )
            check(status == HTTP_STATUS_FORBIDDEN) {
                "expected 403 Forbidden for non-allowlisted Origin, got $status; body=$body"
            }
            assertNoSecretLeaks("http", "Origin-rejection-body", body)
        }
    }

    test("artifact_chunk_get text response scrubs planted secrets in artifact bytes") {
        // §6.24 acceptance: artefacts MUST NOT carry Secret-/JDBC-/
        // tok_-Rohwerte. AP-6.23 already scrubs DDL artefacts at
        // SchemaGenerateHandler write-time; the read-time defense-
        // in-depth here covers user-uploaded artefacts (where the
        // raw bytes were never routed through SecretScrubber on
        // ingestion). Stage an artefact whose bytes contain a
        // Bearer + JDBC URL, read it via artifact_chunk_get, and
        // pin that the wire `text` response is scrubbed.
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val artifactId = "art-e8b-content-${harness.name}"
                val plantedContent = """
                    -- DDL with embedded leak vectors:
                    -- $BEARER_CANARY
                    CREATE TABLE secrets (
                        id INT,
                        token VARCHAR(255) DEFAULT '$APPROVAL_CANARY',
                        connection_uri VARCHAR(2048) DEFAULT '$JDBC_CANARY'
                    );
                """.trimIndent()
                IntegrationFixtures.stageArtifact(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    artifactId = artifactId,
                    content = plantedContent.toByteArray(Charsets.UTF_8),
                    // The chunk_get handler emits `encoding=text`
                    // for known text-shaped MIME types; "application/sql"
                    // would land in the base64 branch (which is
                    // out-of-scope for the scrubbing test). Use a
                    // text-shaped MIME so the scrubbing code path
                    // is exercised.
                    contentType = "application/json",
                )
                val response = harness.toolsCall(
                    "artifact_chunk_get",
                    JsonObject().apply {
                        addProperty("artifactId", artifactId)
                        addProperty("chunkId", "0")
                    },
                )
                response.isError shouldBe false
                val payload = parsePayload(
                    response.content.firstOrNull()?.text
                        ?: error("${harness.name}: chunk_get response had no text content"),
                )
                // The chunk's `text` field is the wire-visible byte
                // projection — must be scrubbed.
                val chunkText = payload.get("text")?.asString
                    ?: error("${harness.name}: chunk_get did not surface text encoding")
                assertNoSecretLeaks(harness.name, "artifact_chunk_get[text]", chunkText)
            }
        }
    }

    test("server log output scrubs planted Bearer/tok_ values from non-allowlisted progress keys") {
        // AP 6.24 final-review: JobStatusGetHandler.projectProgress
        // logs dropped non-allowlisted numeric-value keys via SLF4J
        // (which Logback routes to stderr). User-supplied keys MUST
        // be scrubbed BEFORE the log call so a Bearer-/tok_-shaped
        // key never lands in stderr verbatim. Capture the SLF4J
        // event stream during the dispatch and scan the rendered
        // lines for the deterministic fixture fragments.
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val jobId = "job-e8b-log-${harness.name}"
                stageJobWithSecrets(harness, jobId)

                val capture = LogbackCapture.during {
                    harness.toolsCall(
                        "job_status_get",
                        JsonObject().apply { addProperty("jobId", jobId) },
                    )
                }
                capture.value.isError shouldBe false

                val warningLogs = capture.events
                    .filter { it.loggerName.endsWith("JobStatusGetHandler") }
                    .map { "[${it.level}] ${it.formattedMessage}" }

                withClue("${harness.name}: at least one log entry from JobStatusGetHandler must capture (sanity)") {
                    warningLogs.isNotEmpty() shouldBe true
                }
                val joined = warningLogs.joinToString("|")
                PLANTED_SECRET_FRAGMENTS.forEach { secret ->
                    withClue("${harness.name}: log output MUST NOT leak planted secret '$secret'; lines=$joined") {
                        joined.contains(secret) shouldBe false
                    }
                }
            }
        }
    }

    test("resources/read on a job with planted secrets scrubs them in the projected JSON content") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val tenant = harness.principal.effectiveTenantId.value
                val jobId = "job-e8b-res-${harness.name}"
                stageJobWithSecrets(harness, jobId)

                // resources/read goes through ResourceContentProjector,
                // a different code path than tools/call → JobStatusGetHandler.
                // Both paths MUST scrub identically — pin that here.
                val response = harness.resourcesReadRaw(
                    "dmigrate://tenants/$tenant/jobs/$jobId",
                )
                val resultEl = response.result
                    ?: error("${harness.name}: resources/read returned an error: ${response.error}")
                val contents = resultEl.asJsonObject.getAsJsonArray("contents")
                contents.size() shouldBe 1
                val text = contents.get(0).asJsonObject.get("text").asString
                assertNoSecretLeaks(harness.name, "resources/read", text)
            }
        }
    }
})

// --- E8(B) fixtures + helpers -----------------------------------------------

/** Bearer-token raw value planted into job state. */
private const val BEARER_CANARY: String = "Bearer e8b-canary-bearer-abcdef0123456789"

/** Approval-token raw value planted into job state. */
private const val APPROVAL_CANARY: String = "tok_e8bcanaryapproval0123"

/** JDBC URL raw value with embedded credentials planted into job state. */
private const val JDBC_CANARY: String = "jdbc:postgresql://e8buser:e8bcanarypwd@db.example/internal"

/** HTTP 403 status for the Origin-rejection scrubbing test. */
private const val HTTP_STATUS_FORBIDDEN: Int = 403

/**
 * Substring fragments scanned for in wire output. The full raw
 * values would already trip the assertion; we additionally scan for
 * the SECRET-bearing portion alone (`abcdef0123456789` for the
 * Bearer, the user:password segment for the JDBC URL) so a partial
 * leak (e.g. only the password gets through) still fails.
 */
private val PLANTED_SECRET_FRAGMENTS: List<String> = listOf(
    "e8b-canary-bearer-abcdef0123456789",
    "e8bcanaryapproval",
    "e8bcanarypwd",
    "e8buser:e8bcanarypwd",
)

private fun stageArtifactWithSecretsInMetadata(harness: McpClientHarness, artifactId: String) {
    val tenantId = harness.principal.effectiveTenantId
    val now = Instant.now()
    val record = dev.dmigrate.server.core.artifact.ArtifactRecord(
        managedArtifact = dev.dmigrate.server.core.artifact.ManagedArtifact(
            artifactId = artifactId,
            // Both fields operator-supplied — both must be scrubbed.
            filename = "leaky-${BEARER_CANARY}.bin",
            contentType = "application/x-leak; charset=$BEARER_CANARY",
            sizeBytes = 0,
            sha256 = "0".repeat(64),
            createdAt = now,
            expiresAt = now.plusSeconds(3600),
        ),
        kind = dev.dmigrate.server.core.artifact.ArtifactKind.OTHER,
        tenantId = tenantId,
        ownerPrincipalId = harness.principal.principalId,
        visibility = JobVisibility.TENANT,
        resourceUri = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, artifactId),
    )
    harness.wiring.artifactStore.save(record)
}

private fun stageJobWithSecrets(harness: McpClientHarness, jobId: String) {
    val tenantId = harness.principal.effectiveTenantId
    val now = Instant.now()
    val record = JobRecord(
        managedJob = ManagedJob(
            jobId = jobId,
            operation = "schema_validate",
            status = JobStatus.FAILED,
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plusSeconds(3600),
            createdBy = harness.principal.principalId.value,
            artifacts = emptyList(),
            // Three planted secrets in the fields handlers project to
            // the wire. JobStatusGetHandler uses SecretScrubber on
            // each of these; the test asserts the wire never leaks
            // the raw values regardless of handler path.
            error = JobError(
                code = "DRIVER",
                message = "connection failed: $BEARER_CANARY (auth header) " +
                    "via $JDBC_CANARY ; previous approval $APPROVAL_CANARY",
            ),
            progress = JobProgress(
                phase = "polling-with-leak: $BEARER_CANARY",
                // AP 6.24 final-review: keys must also be a no-leak
                // surface — a planted Bearer-shaped key would slip
                // into wire output if either projector or handler
                // copied the map verbatim. JobStatusGetHandler drops
                // non-allowlisted keys; ResourceContentProjector
                // applies the same filter; both surfaces must hold.
                numericValues = mapOf(
                    "attempts" to 3L,
                    BEARER_CANARY to 1L,
                    APPROVAL_CANARY to 2L,
                ),
            ),
        ),
        tenantId = tenantId,
        ownerPrincipalId = harness.principal.principalId,
        visibility = JobVisibility.TENANT,
        resourceUri = ServerResourceUri(tenantId, ResourceKind.JOBS, jobId),
    )
    harness.wiring.jobStore.save(record)
}

private fun assertNoSecretLeaks(transportName: String, surface: String, text: String) {
    PLANTED_SECRET_FRAGMENTS.forEach { secret ->
        withClue("$transportName/$surface MUST NOT leak planted secret '$secret'; payload was: $text") {
            text.contains(secret) shouldBe false
        }
    }
}

private fun assertAuditSinkNoLeaks(harness: McpClientHarness, transportName: String, surface: String) {
    // AuditEvent doesn't carry raw payload — only metadata + IDs +
    // resourceRefs. Scan the rendered string of every recorded
    // event for the planted secret fragments so a future change
    // that lifts payload snippets into resourceRefs / errorCode
    // / fingerprint surfaces them.
    val sink = when (harness) {
        is StdioHarness -> harness.auditSink
        is HttpHarness -> harness.auditSink
        else -> error("unsupported harness type: ${harness::class}")
    }
    val rendered = sink.recorded().joinToString("|") { it.renderForScan() }
    PLANTED_SECRET_FRAGMENTS.forEach { secret ->
        withClue("$transportName/$surface AuditEvent surface MUST NOT leak planted secret '$secret'") {
            rendered.contains(secret) shouldBe false
        }
    }
}

private fun AuditEvent.renderForScan(): String =
    listOf(
        "requestId=$requestId",
        "outcome=$outcome",
        "toolName=$toolName",
        "tenantId=$tenantId",
        "principalId=$principalId",
        "errorCode=$errorCode",
        "fingerprint=$payloadFingerprint",
        "resourceRefs=${resourceRefs.joinToString(",")}",
    ).joinToString("|")

private fun parsePayload(text: String): JsonObject =
    JsonParser.parseString(text).asJsonObject

private fun dev.dmigrate.mcp.protocol.ToolsCallResult.text(): String =
    content.firstOrNull()?.text ?: error("tools/call response carried no text content")

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun withFreshTransports(
    block: (StdioHarness, HttpHarness) -> Unit,
) {
    val stdioDir = IntegrationFixtures.freshStateDir("dmigrate-it-stdio-")
    val httpDir = IntegrationFixtures.freshStateDir("dmigrate-it-http-")
    val stdio = StdioHarness.start(stdioDir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
    val http = HttpHarness.start(httpDir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
    try {
        stdio.initialize()
        stdio.initializedNotification()
        http.initialize()
        http.initializedNotification()
        block(stdio, http)
    } finally {
        try { stdio.close() } catch (_: Throwable) {}
        try { http.close() } catch (_: Throwable) {}
        try { stdioDir.deleteRecursively() } catch (_: Throwable) {}
        try { httpDir.deleteRecursively() } catch (_: Throwable) {}
    }
}
