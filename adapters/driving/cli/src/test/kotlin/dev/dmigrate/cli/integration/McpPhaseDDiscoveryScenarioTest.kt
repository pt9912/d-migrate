package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP D11: Plan-D §10.11 transport-neutral coverage of the Phase-D
 * discovery surface — five `*_list` tools, the connection-resource
 * read path, and the HMAC-cursor tampering negative.
 *
 * The suite seeds three records per family directly into both
 * harnesses' in-memory stores (no upload-flow round-trip), then
 * iterates each tool over stdio + HTTP. The acceptance bullets
 * Plan-D §10.11 calls out:
 *
 * - Test-Seeds für Jobs / Artefakte / Schemas / Profile / Diffs /
 *   Connections — covered via the [IntegrationFixtures.stage*]
 *   helpers extended in this commit.
 * - Transport-Suites laufen gegen dieselben Fixtures — every
 *   scenario uses [withFreshTransports] which builds an identical
 *   wiring shape per transport.
 * - Negative: manipulierte Cursor → VALIDATION_ERROR.
 * - Secret-Scrubbing: connection-resource projections never
 *   surface `credentialRef` / `providerRef`.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpPhaseDDiscoveryScenarioTest : FunSpec({

    tags(IntegrationTag)

    test("job_list seeded with 3 records returns 3 jobs on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val wiring = harness.testWiring()
                IntegrationFixtures.stageJob(wiring, harness.principal, "job-d11-1")
                IntegrationFixtures.stageJob(wiring, harness.principal, "job-d11-2")
                IntegrationFixtures.stageJob(wiring, harness.principal, "job-d11-3")
                val payload = callListTool(harness, "job_list", "jobs")
                payload.size() shouldBe 3
            }
        }
    }

    test("artifact_list returns the seeded records on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val wiring = harness.testWiring()
                IntegrationFixtures.stageArtifact(wiring, harness.principal, "art-d11-a", "alpha".toByteArray())
                IntegrationFixtures.stageArtifact(wiring, harness.principal, "art-d11-b", "beta".toByteArray())
                callListTool(harness, "artifact_list", "artifacts").size() shouldBe 2
            }
        }
    }

    test("schema_list returns the seeded records on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val wiring = harness.testWiring()
                IntegrationFixtures.stageSchema(wiring, harness.principal, "schema-d11-1", "{}")
                IntegrationFixtures.stageSchema(wiring, harness.principal, "schema-d11-2", "{}")
                callListTool(harness, "schema_list", "schemas").size() shouldBe 2
            }
        }
    }

    test("profile_list returns the seeded records on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val wiring = harness.testWiring()
                IntegrationFixtures.stageProfile(wiring, harness.principal, "profile-d11-1")
                IntegrationFixtures.stageProfile(wiring, harness.principal, "profile-d11-2")
                callListTool(harness, "profile_list", "profiles").size() shouldBe 2
            }
        }
    }

    test("diff_list returns the seeded records on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val wiring = harness.testWiring()
                IntegrationFixtures.stageDiff(wiring, harness.principal, "diff-d11-1")
                IntegrationFixtures.stageDiff(wiring, harness.principal, "diff-d11-2")
                callListTool(harness, "diff_list", "diffs").size() shouldBe 2
            }
        }
    }

    test("connection-resource projection drops credentialRef and providerRef on both transports") {
        // Plan-D §10.10 + §10.11 acceptance: the connection
        // discovery surface NEVER materialises a credentialRef /
        // providerRef value. Pin both per-transport so a future
        // regression that lifts a seed into the wire shape fails
        // immediately.
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val wiring = harness.testWiring()
                IntegrationFixtures.stageConnection(
                    wiring,
                    harness.principal,
                    "conn-d11",
                    credentialRef = "env:SHOULD_NEVER_LEAK",
                )
                val tenant = harness.principal.effectiveTenantId.value
                val body = readResource(harness, "dmigrate://tenants/$tenant/connections/conn-d11")
                body.get("connectionId").asString shouldBe "conn-d11"
                withClue("${harness.name}: credentialRef MUST NOT appear on the wire") {
                    body.has("credentialRef") shouldBe false
                }
                withClue("${harness.name}: providerRef MUST NOT appear on the wire") {
                    body.has("providerRef") shouldBe false
                }
                // Defense-in-depth: a literal `env:SHOULD_NEVER_LEAK`
                // must NOT appear anywhere in the response, not even
                // accidentally inside a description / name field.
                withClue("${harness.name}: response body must not echo the credentialRef value") {
                    body.toString().contains("SHOULD_NEVER_LEAK") shouldBe false
                }
            }
        }
    }

    test("tampered HMAC cursor on a list-tool surfaces a tool-error envelope on both transports") {
        // Plan-D §10.11 negative acceptance: a forged cursor MUST
        // NOT silently restart at page 1. The handler maps
        // `ValidationErrorException` to a `tools/call` error
        // envelope (NOT a JSON-RPC error — list-tools dispatch as
        // tools), with `dmigrateCode=VALIDATION_ERROR`.
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val wiring = harness.testWiring()
                IntegrationFixtures.stageJob(wiring, harness.principal, "job-d11-cursor")
                val args = JsonParser.parseString("""{"cursor":"forged.signature"}""")
                val outcome = harness.toolsCall("job_list", args)
                withClue("${harness.name}: tampered cursor must surface as tool-error envelope") {
                    outcome.isError shouldBe true
                }
                val errorText = outcome.content.firstOrNull()?.text
                    ?: error("${harness.name}: tool-error envelope had no text")
                val errorJson = JsonParser.parseString(errorText).asJsonObject
                errorJson.get("code").asString shouldBe "VALIDATION_ERROR"
            }
        }
    }
})

// ---- Helpers --------------------------------------------------------------

private fun callListTool(
    harness: McpClientHarness,
    toolName: String,
    collectionField: String,
): com.google.gson.JsonArray {
    val outcome = harness.toolsCall(toolName, null)
    val text = outcome.content.firstOrNull()?.text
        ?: error("${harness.name}: $toolName response had no text content")
    val payload = JsonParser.parseString(text).asJsonObject
    return payload.getAsJsonArray(collectionField)
        ?: error("${harness.name}: $toolName response missing '$collectionField' array — got $payload")
}

private fun readResource(harness: McpClientHarness, uri: String): JsonObject {
    val raw = harness.resourcesReadRaw(uri)
    val resultEl = raw.result
        ?: error("${harness.name}: resources/read errored: ${raw.error}")
    val contents = resultEl.asJsonObject.getAsJsonArray("contents")
        ?: error("${harness.name}: missing 'contents' array")
    require(contents.size() == 1) { "${harness.name}: expected 1 content slice, got ${contents.size()}" }
    val text = contents.get(0).asJsonObject.get("text")?.asString
        ?: error("${harness.name}: content slice missing 'text'")
    return JsonParser.parseString(text).asJsonObject
}

/**
 * D11 reuses the E2 / E6 transport-pair scaffold. Cleanup runs even
 * on a failed assertion via the try/finally in [runWithTransports].
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun withFreshTransports(
    block: (StdioHarness, HttpHarness) -> Unit,
) {
    val stdioDir = IntegrationFixtures.freshStateDir("dmigrate-it-stdio-")
    val httpDir = IntegrationFixtures.freshStateDir("dmigrate-it-http-")
    val stdio = StdioHarness.start(stdioDir, IntegrationFixtures.freshTransportPrincipal("stdio"))
    val http = HttpHarness.start(httpDir, IntegrationFixtures.freshTransportPrincipal("http"))
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
