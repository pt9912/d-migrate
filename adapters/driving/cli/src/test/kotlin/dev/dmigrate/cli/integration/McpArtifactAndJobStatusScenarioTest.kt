package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E5: read-only artefact-chunking + job-status slice of the
 * §7.3 Pflichtfluesse.
 *
 *  - `artifact_chunk_get` chunk 0 of a multi-chunk artefact returns
 *    the canonical `dmigrate://tenants/.../chunks/1` `nextChunkUri`,
 *    the right `lengthBytes` + `sha256`, and the chunk text content
 *  - `artifact_chunk_get` on the LAST chunk returns
 *    `nextChunkUri=null` and the final-tail bytes
 *  - `job_status_get` for an own job returns the canonical
 *    projection (jobId / status / terminal / artifacts as URIs)
 *  - `job_status_get` for an unknown jobId returns the no-oracle
 *    `RESOURCE_NOT_FOUND` envelope on both transports
 *
 * Each transport gets its own state-dir / wiring; artefacts and
 * jobs are pre-staged via [IntegrationFixtures] so the chunk-read
 * and job-status flows do NOT depend on the upload flow tested in
 * E4.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpArtifactAndJobStatusScenarioTest : FunSpec({

    tags(IntegrationTag)

    // 64 bytes per chunk: a 200-byte payload spans 4 chunks (0..3),
    // which is small enough to keep the test data printable but big
    // enough to exercise both `nextChunkUri` and the final-chunk
    // termination path.
    val tinyLimits = McpLimitsConfig(maxArtifactChunkBytes = CHUNK_BYTES)

    test("artifact_chunk_get first chunk surfaces nextChunkUri + chunk sha on both transports") {
        val artefactBytes = buildArtefactBytes(byteCount = ARTEFACT_BYTES)
        val expectedChunkCount = (artefactBytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        check(expectedChunkCount >= MIN_CHUNKS) {
            "test artefact must span >= $MIN_CHUNKS chunks; got $expectedChunkCount"
        }

        withFreshTransports(limits = tinyLimits) { s, h ->
            for (harness in listOf(s, h)) {
                IntegrationFixtures.stageArtifact(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    artifactId = ARTIFACT_ID,
                    content = artefactBytes,
                    contentType = "application/json",
                )
                val first = parsePayload(
                    harness.toolsCall("artifact_chunk_get", chunkArgs(ARTIFACT_ID, "0")).text(),
                )
                val expectedFirstSha = sha256Hex(
                    artefactBytes.copyOfRange(0, CHUNK_BYTES.coerceAtMost(artefactBytes.size)),
                )
                withClue("${harness.name} chunk 0 must echo back the artifactId") {
                    first.get("artifactId").asString shouldBe ARTIFACT_ID
                }
                withClue("${harness.name} chunk 0 must report offset=0") {
                    first.get("offset").asLong shouldBe 0L
                }
                withClue("${harness.name} chunk 0 lengthBytes must equal CHUNK_BYTES") {
                    first.get("lengthBytes").asInt shouldBe CHUNK_BYTES
                }
                withClue("${harness.name} chunk 0 sha256 must match the prefix bytes") {
                    first.get("sha256").asString shouldBe expectedFirstSha
                }
                withClue("${harness.name} chunk 0 nextChunkUri must point at chunk 1") {
                    first.get("nextChunkUri").asString
                        .endsWith("/artifacts/$ARTIFACT_ID/chunks/1") shouldBe true
                }
                withClue("${harness.name} chunk 0 encoding must be text for application/json") {
                    first.get("encoding").asString shouldBe "text"
                }
            }
        }
    }

    test("artifact_chunk_get last chunk has nextChunkUri=null on both transports") {
        val artefactBytes = buildArtefactBytes(byteCount = ARTEFACT_BYTES)
        val lastChunkIndex = ((artefactBytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES) - 1
        val lastChunkOffset = lastChunkIndex.toLong() * CHUNK_BYTES.toLong()
        val tail = artefactBytes.copyOfRange(lastChunkOffset.toInt(), artefactBytes.size)

        withFreshTransports(limits = tinyLimits) { s, h ->
            for (harness in listOf(s, h)) {
                IntegrationFixtures.stageArtifact(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    artifactId = ARTIFACT_ID,
                    content = artefactBytes,
                    contentType = "application/json",
                )
                val last = parsePayload(
                    harness.toolsCall(
                        "artifact_chunk_get",
                        chunkArgs(ARTIFACT_ID, lastChunkIndex.toString()),
                    ).text(),
                )
                withClue("${harness.name} last chunk must drop nextChunkUri (JSON null)") {
                    last.get("nextChunkUri").isJsonNull shouldBe true
                }
                withClue("${harness.name} last chunk lengthBytes must match remaining tail") {
                    last.get("lengthBytes").asInt shouldBe tail.size
                }
                withClue("${harness.name} last chunk sha256 must match the tail bytes") {
                    last.get("sha256").asString shouldBe sha256Hex(tail)
                }
                withClue("${harness.name} last chunk offset must be the chunk start") {
                    last.get("offset").asLong shouldBe lastChunkOffset
                }
            }
        }
    }

    test("job_status_get for an own job projects the canonical shape on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                // Stage the linked artefact first so the artefact-
                // backfill projection has something to lift onto a URI.
                IntegrationFixtures.stageArtifact(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    artifactId = ARTIFACT_ID,
                    content = "ok".toByteArray(Charsets.UTF_8),
                )
                IntegrationFixtures.stageJob(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    jobId = JOB_ID,
                    operation = "schema_validate",
                    artifacts = listOf(ARTIFACT_ID),
                )
                val payload = parsePayload(
                    harness.toolsCall(
                        "job_status_get",
                        JsonObject().apply { addProperty("jobId", JOB_ID) },
                    ).text(),
                )
                withClue("${harness.name} jobId must echo back the requested id") {
                    payload.get("jobId").asString shouldBe JOB_ID
                }
                withClue("${harness.name} status must reflect the staged SUCCEEDED state") {
                    payload.get("status").asString shouldBe "SUCCEEDED"
                }
                withClue("${harness.name} terminal must be true for SUCCEEDED") {
                    payload.get("terminal").asBoolean shouldBe true
                }
                withClue("${harness.name} resourceUri must be a Phase-C tenant jobs URI") {
                    payload.get("resourceUri").asString
                        .startsWith("dmigrate://tenants/") shouldBe true
                    payload.get("resourceUri").asString
                        .contains("/jobs/$JOB_ID") shouldBe true
                }
                // §6.16 / §6.23 N7: the wire projection lifts naked
                // artifact ids onto canonical artefact URIs.
                val artifacts = payload.getAsJsonArray("artifacts")
                withClue("${harness.name} artifacts must be backfilled to artefact URIs") {
                    artifacts.size() shouldBe 1
                    artifacts.get(0).asString
                        .endsWith("/artifacts/$ARTIFACT_ID") shouldBe true
                }
            }
        }
    }

    test("job_status_get for an unknown jobId surfaces RESOURCE_NOT_FOUND on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val raw = harness.toolsCall(
                    "job_status_get",
                    JsonObject().apply { addProperty("jobId", "unknown-job-id") },
                )
                withClue("${harness.name} unknown jobId must surface a tool-error envelope") {
                    raw.isError shouldBe true
                }
                val envelope = parsePayload(
                    raw.content.firstOrNull()?.text
                        ?: error("${harness.name}: error envelope had no text content"),
                )
                withClue("${harness.name} envelope code must be RESOURCE_NOT_FOUND") {
                    envelope.get("code").asString shouldBe "RESOURCE_NOT_FOUND"
                }
            }
        }
    }
})

private const val CHUNK_BYTES: Int = 64
private const val ARTEFACT_BYTES: Int = 200
private const val MIN_CHUNKS: Int = 3
private const val ARTIFACT_ID: String = "art-e5-fixture"
private const val JOB_ID: String = "job-e5-fixture"

/**
 * Builds an artefact body of exactly [byteCount] bytes. The content
 * is deterministic ASCII so chunk boundaries are easy to follow in
 * a debugger.
 */
private fun buildArtefactBytes(byteCount: Int): ByteArray {
    val sb = StringBuilder(byteCount)
    var i = 0
    while (sb.length < byteCount) {
        sb.append("seg-${i % CHUNK_BYTES}-")
        i += 1
    }
    return sb.toString().substring(0, byteCount).toByteArray(Charsets.UTF_8)
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun chunkArgs(artifactId: String, chunkId: String): JsonObject = JsonObject().apply {
    addProperty("artifactId", artifactId)
    addProperty("chunkId", chunkId)
}

/**
 * E5 reuses the E2/E3/E4 transport-pair pattern. Block does its
 * own assertions; cleanup runs even on failure.
 */
private fun withFreshTransports(
    limits: McpLimitsConfig = McpLimitsConfig(),
    block: (StdioHarness, HttpHarness) -> Unit,
) {
    val stdioDir = IntegrationFixtures.freshStateDir("dmigrate-it-stdio-")
    val httpDir = IntegrationFixtures.freshStateDir("dmigrate-it-http-")
    val stdio = StdioHarness.start(stdioDir, IntegrationFixtures.INTEGRATION_PRINCIPAL, limits)
    val http = HttpHarness.start(httpDir, IntegrationFixtures.INTEGRATION_PRINCIPAL, limits)
    try {
        stdio.initialize()
        stdio.initializedNotification()
        http.initialize()
        http.initializedNotification()
        block(stdio, http)
    } finally {
        try { stdio.close() } catch (_: Throwable) {}
        try { http.close() } catch (_: Throwable) {}
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        try { stdioDir.deleteRecursively() } catch (_: Throwable) {}
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        try { httpDir.deleteRecursively() } catch (_: Throwable) {}
    }
}

private fun dev.dmigrate.mcp.protocol.ToolsCallResult.text(): String {
    val body = content.firstOrNull()?.text ?: error("tools/call response carried no text content")
    if (this.isError) {
        error("tools/call returned isError=true; body=$body")
    }
    return body
}

private fun parsePayload(text: String): JsonObject =
    JsonParser.parseString(text).asJsonObject
