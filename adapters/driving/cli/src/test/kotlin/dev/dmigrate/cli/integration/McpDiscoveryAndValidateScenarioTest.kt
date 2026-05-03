package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.io.path.deleteRecursively

/**
 * AP 6.24 E2: drift guard + transport-neutral smoke for the two
 * read-only discovery / validation tools per §7.3 (Pflichtflüsse):
 *
 *  - `tools/list` MUST advertise every tool from
 *    [PhaseCToolMatrix.PHASE_C_TOOLS] on both transports
 *  - `capabilities_list` MUST return a transport-equivalent payload
 *  - `schema_validate` MUST return a transport-equivalent payload
 *    for a small valid inline schema
 *  - `schema_validate` MUST return a transport-equivalent
 *    `truncated=true` + `artifactRef` shape when findings exceed
 *    `maxInlineFindings`
 *
 * Per §6.24 each transport runs in its own state-dir / wiring; we
 * normalise per-call dynamic fields (`requestId`, dynamic
 * resource-URI ids) before comparing payloads at the field level.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpDiscoveryAndValidateScenarioTest : FunSpec({

    tags(NamedTag("integration"))

    test("tools/list advertises the Phase-C tool matrix on both transports") {
        val (stdioTools, httpTools) = withFreshTransports { stdio, http ->
            stdio.toolsList() to http.toolsList()
        }
        val stdioNames = PhaseCToolMatrix.toolNames(stdioTools.tools)
        val httpNames = PhaseCToolMatrix.toolNames(httpTools.tools)
        val stdioDrift = PhaseCToolMatrix.drift(stdioNames, expectedSuperset = stdioNames + httpNames)
        val httpDrift = PhaseCToolMatrix.drift(httpNames, expectedSuperset = stdioNames + httpNames)
        if (!stdioDrift.isEmpty() || !httpDrift.isEmpty()) {
            fail(
                listOf(stdioDrift.render("stdio"), httpDrift.render("http")).joinToString("\n"),
            )
        }
        // Drift guard handled E1; here we only pin the matrix-side
        // contract: every Phase-C tool must be on each transport.
        for (tool in PhaseCToolMatrix.PHASE_C_TOOLS) {
            withClue("stdio missing $tool") { stdioNames.shouldContain(tool) }
            withClue("http missing $tool") { httpNames.shouldContain(tool) }
        }
    }

    test("capabilities_list payload is transport-equivalent") {
        val (stdio, http) = withFreshTransports { stdio, http ->
            parseCapabilities(stdio.toolsCall("capabilities_list", JsonObject()).text()) to
                parseCapabilities(http.toolsCall("capabilities_list", JsonObject()).text())
        }
        // Mask requestId across both payloads and compare at
        // structure-equality. Tools/scopeTable/limits/dialects/formats
        // are invariants per AP-6.24 §6.24 ("transport-neutral").
        val masked = setOf("requestId")
        TransportNormalisation.maskFields(stdio, masked) shouldBe
            TransportNormalisation.maskFields(http, masked)
    }

    test("schema_validate small inline schema is transport-equivalent") {
        val arguments = JsonParser.parseString(
            """{"schema":{"name":"orders","version":"1.0","tables":{}}}""",
        ).asJsonObject
        val (stdio, http) = withFreshTransports { s, h ->
            parsePayload(s.toolsCall("schema_validate", arguments).text()) to
                parsePayload(h.toolsCall("schema_validate", arguments).text())
        }
        val masked = setOf("requestId")
        // Sanity: payload still carries the business-level "valid".
        stdio.get("valid").asBoolean shouldBe true
        http.get("valid").asBoolean shouldBe true
        TransportNormalisation.maskFields(stdio, masked) shouldBe
            TransportNormalisation.maskFields(http, masked)
    }

    test("schema_validate truncation produces transport-equivalent artifactRef shape") {
        val tinyLimits = McpLimitsConfig(maxInlineFindings = 3)
        val tables = (1..10).joinToString(",") { """"t$it":{"columns":{}}""" }
        val arguments = JsonParser.parseString(
            """{"schema":{"name":"x","version":"1.0","tables":{$tables}}}""",
        ).asJsonObject

        val (stdio, http) = withFreshTransports(limits = tinyLimits) { s, h ->
            parsePayload(s.toolsCall("schema_validate", arguments).text()) to
                parsePayload(h.toolsCall("schema_validate", arguments).text())
        }
        // Both transports must produce the same truncated/findings/
        // artifactRef-shape projection.
        for ((name, payload) in listOf("stdio" to stdio, "http" to http)) {
            withClue("$name truncated=true expected") {
                payload.get("truncated").asBoolean shouldBe true
            }
            withClue("$name findings capped at $TRUNCATION_CAP") {
                payload.getAsJsonArray("findings").size() shouldBe TRUNCATION_CAP
            }
            withClue("$name artifactRef must be a Phase-C tenant artefact URI") {
                payload.get("artifactRef")?.asString
                    ?.startsWith("dmigrate://tenants/") shouldBe true
            }
        }
        // After masking requestId AND normalising the artifactRef
        // dynamic id, both transport payloads must be equal at the
        // field level.
        val maskedStdio = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(stdio),
            setOf("requestId"),
        )
        val maskedHttp = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(http),
            setOf("requestId"),
        )
        maskedStdio shouldBe maskedHttp
    }
})

private const val TRUNCATION_CAP: Int = 3

/**
 * Runs [block] against fresh [StdioHarness] + [HttpHarness] instances
 * with their own state-dirs. Cleanup is guaranteed via try/finally so
 * a failed assertion still tears down both bootstraps and removes
 * the temp dirs.
 */
private fun <T> withFreshTransports(
    limits: McpLimitsConfig = McpLimitsConfig(),
    block: (StdioHarness, HttpHarness) -> T,
): T {
    val stdioDir = IntegrationFixtures.freshStateDir("dmigrate-it-stdio-")
    val httpDir = IntegrationFixtures.freshStateDir("dmigrate-it-http-")
    val stdio = StdioHarness.start(stdioDir, IntegrationFixtures.INTEGRATION_PRINCIPAL, limits)
    val http = HttpHarness.start(httpDir, IntegrationFixtures.INTEGRATION_PRINCIPAL, limits)
    return try {
        // Always run the initialize / initialized handshake once per
        // harness so the scenario block sees a session-ready surface.
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

private fun dev.dmigrate.mcp.protocol.ToolsCallResult.text(): String =
    content.firstOrNull()?.text ?: error("tools/call response carried no text content")

private fun parsePayload(text: String): JsonObject =
    JsonParser.parseString(text).asJsonObject

private fun parseCapabilities(text: String): JsonObject = parsePayload(text)
