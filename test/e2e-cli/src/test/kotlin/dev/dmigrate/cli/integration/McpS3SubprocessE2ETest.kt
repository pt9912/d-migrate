package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.server.adapter.storage.s3.S3ClientFactory
import dev.dmigrate.server.adapter.storage.s3.S3StorageConfig
import dev.dmigrate.server.adapter.storage.s3.SEAWEED_TEST_ACCESS_KEY
import dev.dmigrate.server.adapter.storage.s3.SEAWEED_TEST_SECRET_KEY
import dev.dmigrate.server.adapter.storage.s3.newSeaweedS3Container
import dev.dmigrate.server.adapter.storage.s3.s3Endpoint
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.deleteRecursively

/**
 * S3.4c (ImpPlan-0.9.8-object-storage-s3): MCP-Protokoll-E2E ueber die
 * ECHTE CLI als Kind-JVM — `mcp serve --transport stdio
 * --connection-config <yaml mit artifacts.store: s3>` gegen SeaweedFS:
 *
 *  - Credentials kommen NUR aus der Env (`AWS_*` via
 *    `DefaultCredentialsProviderChain`), die YAML traegt endpoint/bucket/
 *    prefix — der volle Produktionspfad inkl. `parseArtifactsConfigOrExit`,
 *    Sweep-Skip und Start-State-Zeile.
 *  - `artifact_upload_init` → `artifact_upload` (final) → Finalize:
 *    der Segment-Write landet in SeaweedFS (S3-Write-Pfad), die Finalize-
 *    Assembly liest ihn von dort zurueck (S3-Read-Pfad) und persistiert
 *    das Artefakt wieder nach S3. Dass der Round-Trip nur ueber S3
 *    gelaufen sein kann, beweisen die State-Dir-Asserts: lokale
 *    segment-/artifact-Verzeichnisse existieren nicht.
 *  - Replay des finalen Segments → `deduplicated=true` mit identischem
 *    `schemaRef` (idempotenter Pfad ueber die S3-Artefakt-Metadata).
 *  - stderr nennt endpoint/bucket, aber nie die Credentials.
 *
 * Hinweis Scope: Single-Segment, weil die echte CLI mit Default-Limits
 * laeuft und nicht-finale Segmente exakt `maxUploadSegmentBytes` (4 MiB)
 * gross sein muessten — Multi-Segment-Verhalten der S3-Stores deckt die
 * Vertragssuite ab, den Wiring-Pfad `McpCliRuntimeWiringSeaweedTest`.
 * `data_import` als MCP-Szenario existiert auch fuer den file-Store nicht
 * (eigener Scope); die S3-relevanten Byte-Pfade — Segment-Write,
 * Segment-Read (Finalize-Assembly), Artefakt-Write/Head — sind durch
 * diesen Flow vollstaendig abgedeckt.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpS3SubprocessE2ETest : FunSpec({

    val container = newSeaweedS3Container()

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    test("real CLI subprocess with artifacts.store=s3: upload flow lands bytes in SeaweedFS, not the state dir") {
        val stateDir = Files.createTempDirectory("dmigrate-e2e-s3-state-")
        val configDir = Files.createTempDirectory("dmigrate-e2e-s3-cfg-")
        val rawClient = S3ClientFactory.create(
            S3StorageConfig(
                bucket = BUCKET,
                endpoint = URI.create(container.s3Endpoint()),
                accessKey = SEAWEED_TEST_ACCESS_KEY,
                secretKey = SEAWEED_TEST_SECRET_KEY,
            ),
        )
        try {
            rawClient.createBucket { it.bucket(BUCKET) }
            val configPath = writeArtifactsYaml(configDir, container.s3Endpoint())
            val tokenFile = writeStdioTokenFile(configDir)

            val cli = startRealCliSubprocess(
                stateDir.toString(),
                extraArgs = listOf(
                    "--connection-config", configPath.toString(),
                    "--stdio-token-file", tokenFile.toString(),
                ),
                env = mapOf(
                    "AWS_ACCESS_KEY_ID" to SEAWEED_TEST_ACCESS_KEY,
                    "AWS_SECRET_ACCESS_KEY" to SEAWEED_TEST_SECRET_KEY,
                    "DMIGRATE_MCP_STDIO_TOKEN" to STDIO_TOKEN,
                ),
            )
            try {
                withClue("CLI subprocess must emit the startup line (stderr: ${cli.stderrSnapshot()})") {
                    cli.awaitStderrLine("MCP stdio server started", STARTUP_TIMEOUT_MS) shouldBe true
                }

                // Start-State-Zeile + Sweep-Skip nennen das S3-Backend;
                // Credentials erscheinen nirgends (S3.4-DoD).
                val stderr = cli.stderrSnapshot()
                stderr shouldContain "byte content is S3-backed"
                stderr shouldContain "bucket=$BUCKET"
                stderr shouldContain "segment/artefact sweeps skipped (artifacts.store=s3)"
                stderr shouldNotContain SEAWEED_TEST_SECRET_KEY
                stderr shouldNotContain SEAWEED_TEST_ACCESS_KEY

                initializeSession(cli)

                val bytes = buildSchemaBytes(tableCount = 12)
                val segments = listOf(bytes)
                // Bei einem INTERNAL_AGENT_ERROR (Native-Defekt 2026-07-20: SizeMismatch im
                // S3-ArtifactContentStore, JVM gruen) traegt die JSON-RPC-Antwort nur die generische
                // Huelle. Die Ursache steht im stderr des Kind-Prozesses — deshalb hier bei jedem
                // Fehlschlag der Upload-Kette mitgeliefert, statt ihn zu verschweigen.
                val sessionId = withClue({ "uploadInit failed; child stderr:\n${cli.stderrSnapshot()}" }) {
                    uploadInit(cli, bytes)
                }

                // Finales (einziges) Segment: Segment-Write nach S3, dann
                // liest die Finalize-Assembly es von dort zurueck und
                // persistiert das Artefakt nach S3.
                val final = try {
                    uploadSegment(cli, sessionId, segments, 0, isFinal = true)
                } catch (e: IllegalStateException) {
                    // Der Server hat die Ursache ins stderr geloggt; sie kommt asynchron ueber die
                    // Pipe, deshalb aktiv darauf warten statt den Puffer einmalig zu lesen.
                    cli.awaitStderrLine("size mismatch", 3000)
                    throw IllegalStateException("${e.message}\n--- child stderr ---\n${cli.stderrSnapshot()}", e)
                }
                final.get("uploadSessionState").asString shouldBe "COMPLETED"
                final.get("schemaRef").asString.startsWith("dmigrate://tenants/") shouldBe true

                // Replay des finalen Segments: idempotenter Pfad ueber die
                // in S3 persistierten Artefakt-/Session-Daten.
                val replay = uploadSegment(cli, sessionId, segments, 0, isFinal = true)
                replay.get("deduplicated").asBoolean shouldBe true
                replay.get("schemaRef").asString shouldBe final.get("schemaRef").asString

                val artifactKeys = listKeys(rawClient).filterNot { it.contains("/segments/") }
                withClue("exactly one artifact object must exist under the prefix (saw: $artifactKeys)") {
                    artifactKeys shouldHaveSize 1
                    artifactKeys.single().startsWith("$PREFIX/") shouldBe true
                }
                val artifactKey = artifactKeys.single()
                withClue("artifact object metadata must carry the client-side sha256") {
                    rawClient.headObject { it.bucket(BUCKET).key(artifactKey) }
                        .metadata()["sha256"] shouldBe sha256Hex(bytes)
                }
                withClue("artifact content in SeaweedFS must round-trip the uploaded bytes") {
                    rawClient.getObjectAsBytes { it.bucket(BUCKET).key(artifactKey) }
                        .asByteArray().contentEquals(bytes) shouldBe true
                }

                // Der lokale State-Dir traegt keine Byte-Stores: die
                // file-Adapter wurden nie konstruiert.
                Files.exists(stateDir.resolve("artifacts")) shouldBe false
                Files.exists(stateDir.resolve("segments")) shouldBe false

                cli.closeStdin()
                withClue("subprocess must exit cleanly (ownedResources close path); stderr=${cli.stderrSnapshot()}") {
                    cli.awaitExit(EXIT_TIMEOUT_MS) shouldBe 0
                }
            } finally {
                cli.killIfAlive()
            }
        } finally {
            runCatching { rawClient.close() }
            stateDir.deleteRecursively()
            configDir.deleteRecursively()
        }
    }
})

private const val BUCKET = "mcp-e2e"
private const val PREFIX = "e2e"
private const val STDIO_TOKEN = "tok_e2e_s3_subprocess"
private const val STARTUP_TIMEOUT_MS: Long = 30_000
private const val EXIT_TIMEOUT_MS: Long = 10_000
private const val FINALIZE_TIMEOUT_MS: Long = 30_000

private fun writeArtifactsYaml(dir: Path, endpoint: String): Path =
    dir.resolve(".d-migrate.yaml").also {
        Files.writeString(
            it,
            """
            artifacts:
              store: s3
              s3:
                endpoint: "$endpoint"
                bucket: "$BUCKET"
                prefix: "$PREFIX"
                pathStyle: true
            """.trimIndent(),
        )
    }

private fun writeStdioTokenFile(dir: Path): Path =
    dir.resolve("stdio-tokens.yaml").also {
        Files.writeString(
            it,
            """
            tokens:
              - fingerprint: "${sha256Hex(STDIO_TOKEN)}"
                principalId: "e2e-s3"
                tenantId: "default"
                scopes: ["dmigrate:admin"]
                isAdmin: true
                auditSubject: "e2e-s3@test"
                expiresAt: "2099-01-01T00:00:00Z"
            """.trimIndent(),
        )
    }

private fun listKeys(client: S3Client): List<String> =
    client.listObjectsV2 { it.bucket(BUCKET) }.contents().map { it.key() }

private var rpcId = 1

private fun initializeSession(cli: CliSubprocess) {
    val response = cli.requestResponse(
        """{"jsonrpc":"2.0","id":${rpcId++},"method":"initialize","params":""" +
            """{"protocolVersion":"2025-11-25",""" +
            """"clientInfo":{"name":"dmigrate-e2e-s3","version":"0.0.0"},""" +
            """"capabilities":{}}}""",
    )
    check(JsonParser.parseString(response).asJsonObject.has("result")) {
        "initialize failed: $response"
    }
    cli.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
}

private fun toolsCall(cli: CliSubprocess, tool: String, arguments: JsonObject, timeoutMs: Long): JsonObject {
    val request = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("id", rpcId++)
        addProperty("method", "tools/call")
        add(
            "params",
            JsonObject().apply {
                addProperty("name", tool)
                add("arguments", arguments)
            },
        )
    }
    val raw = cli.requestResponse(request.toString(), timeoutMs)
    val envelope = JsonParser.parseString(raw).asJsonObject
    val result = envelope.getAsJsonObject("result")
        ?: error("tools/call $tool returned no result: $raw")
    check(result.get("isError")?.asBoolean != true) { "tools/call $tool returned isError=true: $raw" }
    val text = result.getAsJsonArray("content").first().asJsonObject.get("text").asString
    return JsonParser.parseString(text).asJsonObject
}

private fun uploadInit(cli: CliSubprocess, fullBytes: ByteArray): String {
    val args = JsonObject().apply {
        addProperty("uploadIntent", "schema_staging_readonly")
        addProperty("expectedSizeBytes", fullBytes.size.toLong())
        addProperty("checksumSha256", sha256Hex(fullBytes))
    }
    return toolsCall(cli, "artifact_upload_init", args, CliSubprocess.RESPONSE_TIMEOUT_MS)
        .get("uploadSessionId").asString
}

private fun uploadSegment(
    cli: CliSubprocess,
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
        addProperty("contentBase64", Base64.getEncoder().encodeToString(segmentBytes))
    }
    // Das finale Segment stoesst die Finalize-Assembly an (S3-Reads +
    // Artefakt-Write) — grosszuegigeres Timeout als fuer reine Segment-Puts.
    val timeout = if (isFinal) FINALIZE_TIMEOUT_MS else CliSubprocess.RESPONSE_TIMEOUT_MS
    return toolsCall(cli, "artifact_upload", args, timeout)
}

private fun buildSchemaBytes(tableCount: Int): ByteArray {
    val tables = (1..tableCount).joinToString(",") { idx ->
        """"orders_$idx":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}"""
    }
    return """{"name":"orders","version":"1.0","tables":{$tables}}""".toByteArray(Charsets.UTF_8)
}
