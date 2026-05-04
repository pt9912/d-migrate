package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E4: end-to-end upload flow per §7.3 Pflichtfluesse:
 *
 *  - `artifact_upload_init` → multi-segment `artifact_upload` →
 *    final segment finalises to a `schemaRef` (read-only schema
 *    staging) on BOTH transports
 *  - the AP-6.21 file-backed wiring writes real files under
 *    `<stateDir>/segments/<sessionId>/...` and
 *    `<stateDir>/artifacts/<shard>/<artifactId>/...` for both
 *    transports
 *  - replay of the completing segment returns the same `schemaRef`
 *    with `deduplicated=true` (idempotent finalisation, AP 6.22 C2)
 *  - `artifact_upload_abort` on a transport's own ACTIVE session
 *    transitions to `ABORTED` and reports the deleted segment count
 *
 * Each transport runs in its own state-dir / wiring (§6.24); the
 * test does not share session ids, schema ids, or artefact ids
 * between stdio and HTTP.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpUploadFlowScenarioTest : FunSpec({

    tags(IntegrationTag)

    // Segment boundary small enough to trigger multi-segment uploads
    // with a parseable JSON schema that fits in a few hundred bytes
    // per table. SEGMENT_BYTES=512 keeps the test data well under
    // any per-call limit, and the resulting payload (~1.2 KiB) splits
    // into 3 segments deterministically.
    val tinyLimits = McpLimitsConfig(maxUploadSegmentBytes = SEGMENT_BYTES)

    test("multi-segment upload finalises to a schemaRef on both transports") {
        val bytes = buildSchemaBytes(tableCount = SCHEMA_TABLES)
        val segments = splitSegments(bytes, SEGMENT_BYTES)
        check(segments.size >= MIN_SEGMENTS) {
            "test data must be multi-segment; got ${segments.size}"
        }

        withFreshTransports(limits = tinyLimits) { s, h ->
            val stdioFinal = uploadAll(s, bytes, segments)
            val httpFinal = uploadAll(h, bytes, segments)

            // Both transports must surface a Phase-C tenant schemaRef
            // on the completing segment; the URI shape is asserted
            // here, the dynamic id is normalised before equality.
            for ((name, payload) in listOf("stdio" to stdioFinal, "http" to httpFinal)) {
                withClue("$name completing segment must include schemaRef") {
                    payload.has("schemaRef") shouldBe true
                }
                withClue("$name schemaRef must be a Phase-C tenant URI") {
                    payload.get("schemaRef").asString
                        .startsWith("dmigrate://tenants/") shouldBe true
                }
                withClue("$name uploadSessionState must transition to COMPLETED") {
                    payload.get("uploadSessionState").asString shouldBe "COMPLETED"
                }
            }

            // AP 6.21 file-backed wiring: real files MUST land under
            // <stateDir>/artifacts/... after a successful finalise.
            // The segments tree may be cleaned up by the finaliser —
            // we only pin the artefact tree which is the durable side
            // of the staging contract.
            for ((name, dir) in listOf("stdio" to s.stateDir, "http" to h.stateDir)) {
                val artifactsTree = dir.resolve("artifacts")
                withClue("$name artifacts tree must exist after finalise") {
                    Files.isDirectory(artifactsTree) shouldBe true
                }
                val artifactFiles = Files.walk(artifactsTree).use { stream ->
                    stream.filter(Files::isRegularFile).toList()
                }
                withClue("$name artifacts tree must contain at least one persisted artefact file") {
                    artifactFiles.isNotEmpty() shouldBe true
                }
            }

            // Transport-neutral structural equality: the dynamic
            // sessionId, schemaRef id, ttl, requestId all differ
            // between the two transports' runs by design — we mask
            // them and compare only the contractual shape.
            val mask = REQUEST_ID_MASK + DYNAMIC_UPLOAD_FIELDS
            TransportNormalisation.maskFields(
                TransportNormalisation.normaliseResourceUris(stdioFinal),
                mask,
            ) shouldBe TransportNormalisation.maskFields(
                TransportNormalisation.normaliseResourceUris(httpFinal),
                mask,
            )
        }
    }

    test("replay of the completing segment is idempotent on both transports") {
        val bytes = buildSchemaBytes(tableCount = SCHEMA_TABLES)
        val segments = splitSegments(bytes, SEGMENT_BYTES)

        withFreshTransports(limits = tinyLimits) { s, h ->
            for (harness in listOf(s, h)) {
                val sessionId = initSession(harness, bytes, segments.size)
                val nonFinalCount = segments.size - 1
                for (i in 0 until nonFinalCount) {
                    callSegment(harness, sessionId, segments, i, isFinal = false)
                }
                val first = callSegment(harness, sessionId, segments, nonFinalCount, isFinal = true)
                val replay = callSegment(harness, sessionId, segments, nonFinalCount, isFinal = true)

                withClue("${harness.name} first finalise must return schemaRef") {
                    first.get("schemaRef")?.asString
                        ?.startsWith("dmigrate://tenants/") shouldBe true
                }
                withClue("${harness.name} replay must return the SAME schemaRef") {
                    replay.get("schemaRef")?.asString shouldBe first.get("schemaRef")?.asString
                }
                withClue("${harness.name} replay must report deduplicated=true") {
                    replay.get("deduplicated").asBoolean shouldBe true
                }
                withClue("${harness.name} replay session state stays COMPLETED") {
                    replay.get("uploadSessionState").asString shouldBe "COMPLETED"
                }
            }
        }
    }

    test("artifact_upload_abort terminates an active session on both transports") {
        val bytes = buildSchemaBytes(tableCount = SCHEMA_TABLES)
        val segments = splitSegments(bytes, SEGMENT_BYTES)

        withFreshTransports(limits = tinyLimits) { s, h ->
            for (harness in listOf(s, h)) {
                val sessionId = initSession(harness, bytes, segments.size)
                // Upload the first segment to make the session non-empty,
                // so segmentsDeleted is observable.
                callSegment(harness, sessionId, segments, 0, isFinal = false)

                val abortArgs = JsonObject().apply { addProperty("uploadSessionId", sessionId) }
                val abort = parsePayload(harness.toolsCall("artifact_upload_abort", abortArgs).text())

                withClue("${harness.name} abort must transition to ABORTED") {
                    abort.get("uploadSessionState").asString shouldBe "ABORTED"
                }
                withClue("${harness.name} abort must echo back the same uploadSessionId") {
                    abort.get("uploadSessionId").asString shouldBe sessionId
                }
                withClue("${harness.name} abort must delete the staged segment(s)") {
                    abort.get("segmentsDeleted").asInt shouldBe 1
                }
            }
        }
    }
})

private const val SEGMENT_BYTES: Int = 512
private const val SCHEMA_TABLES: Int = 16
private const val MIN_SEGMENTS: Int = 2
private val REQUEST_ID_MASK: Set<String> = setOf("requestId")

// Wire fields whose values are dynamic (server-minted ids, lease
// counters) and are NOT business invariants per §6.24 — masked
// before transport-neutral structural equality.
private val DYNAMIC_UPLOAD_FIELDS: Set<String> = setOf(
    "uploadSessionId",
    "uploadSessionTtlSeconds",
    "bytesReceived",
)

/** Builds a parseable JSON schema with [tableCount] tables. */
private fun buildSchemaBytes(tableCount: Int): ByteArray {
    val tables = (1..tableCount).joinToString(",") { idx ->
        """"orders_$idx":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}"""
    }
    return """{"name":"orders","version":"1.0","tables":{$tables}}""".toByteArray(Charsets.UTF_8)
}

private fun splitSegments(bytes: ByteArray, segmentSize: Int): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < bytes.size) {
        val end = (offset + segmentSize).coerceAtMost(bytes.size)
        out += bytes.copyOfRange(offset, end)
        offset = end
    }
    return out
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun uploadAll(
    harness: McpClientHarness,
    bytes: ByteArray,
    segments: List<ByteArray>,
): JsonObject {
    val sessionId = initSession(harness, bytes, segments.size)
    var last: JsonObject? = null
    for (i in segments.indices) {
        val isFinal = i == segments.size - 1
        last = callSegment(harness, sessionId, segments, i, isFinal)
    }
    return last!!
}

private fun initSession(
    harness: McpClientHarness,
    fullBytes: ByteArray,
    @Suppress("UNUSED_PARAMETER") segmentTotal: Int,
): String {
    val initArgs = JsonObject().apply {
        addProperty("uploadIntent", "schema_staging_readonly")
        addProperty("expectedSizeBytes", fullBytes.size.toLong())
        addProperty("checksumSha256", sha256Hex(fullBytes))
    }
    val initResp = parsePayload(harness.toolsCall("artifact_upload_init", initArgs).text())
    return initResp.get("uploadSessionId").asString
}

private fun callSegment(
    harness: McpClientHarness,
    sessionId: String,
    segments: List<ByteArray>,
    index: Int,
    isFinal: Boolean,
): JsonObject {
    val segmentBytes = segments[index]
    val segmentOffset = (0 until index).sumOf { segments[it].size.toLong() }
    val args = JsonObject().apply {
        addProperty("uploadSessionId", sessionId)
        addProperty("segmentIndex", index + 1) // 1-based per the wire schema
        addProperty("segmentOffset", segmentOffset)
        addProperty("segmentTotal", segments.size)
        addProperty("isFinalSegment", isFinal)
        addProperty("segmentSha256", sha256Hex(segmentBytes))
        addProperty("contentBase64", base64(segmentBytes))
    }
    return parsePayload(harness.toolsCall("artifact_upload", args).text())
}

/**
 * E4 reuses the E2/E3 transport-pair pattern. Each block runs against
 * fresh stdio + HTTP harnesses with their own state-dir; cleanup is
 * guaranteed via try/finally even when assertions throw.
 */
private fun withFreshTransports(
    limits: McpLimitsConfig = McpLimitsConfig(),
    block: (StdioHarness, HttpHarness) -> Unit,
) {
    val stdioDir = IntegrationFixtures.freshStateDir("dmigrate-it-stdio-")
    val httpDir = IntegrationFixtures.freshStateDir("dmigrate-it-http-")
    val stdio = StdioHarness.start(stdioDir, IntegrationFixtures.freshTransportPrincipal("stdio"), limits)
    val http = HttpHarness.start(httpDir, IntegrationFixtures.freshTransportPrincipal("http"), limits)
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
