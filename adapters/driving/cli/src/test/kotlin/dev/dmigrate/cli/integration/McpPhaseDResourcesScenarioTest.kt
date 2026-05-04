package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP D11 sub-commit 2: Plan-D §10.11 transport-neutral coverage of
 * the protocol-level resource methods (`resources/list`,
 * `resources/templates/list`).
 *
 * The discovery `*_list` tools have their own scenario test
 * (`McpPhaseDDiscoveryScenarioTest`); this spec focuses on:
 *
 * - the `resources/list` walker round-trips the HMAC-sealed cursor
 *   from AP D8 sub-commit 1 across both transports
 * - a tampered `resources/list` cursor surfaces as
 *   `-32602 InvalidParams` with `error.data.dmigrateCode=VALIDATION_ERROR`
 * - `resources/templates/list` keeps its static-7-templates pin
 *   alive on both transports
 *
 * Plan-D §10.11 acceptance bullets covered here:
 * - "resources/list und resources/templates/list" — yes, end-to-end
 * - "Templates-Golden-Test bleibt bei sieben Eintraegen" — yes
 * - "Negative Tests fuer manipulierte Cursor" — yes
 * - "JSON-Schema- und Golden-Outputs bleiben stabil" — implicit via
 *   the pinned counts and field expectations.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpPhaseDResourcesScenarioTest : FunSpec({

    tags(IntegrationTag)

    test("resources/list HMAC cursor round-trips across both transports") {
        // Seed 60 jobs so the default 50-page-size walker emits a
        // non-null sealed cursor on the first response. Pin that
        // the second call resumes mid-walk without re-emitting any
        // first-page id.
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val wiring = harness.testWiring()
                (1..60).forEach { i ->
                    IntegrationFixtures.stageJob(wiring, harness.principal, "job-rl-%03d".format(i))
                }
                val first = resourcesList(harness, cursor = null)
                val firstUris = first.getAsJsonArray("resources").map { it.asJsonObject.get("uri").asString }
                withClue("${harness.name}: first page must surface 50 jobs (default page size)") {
                    firstUris.size shouldBe 50
                }
                val nextCursor = first.get("nextCursor").asString
                withClue("${harness.name}: HMAC-sealed cursor wire format <payload>.<sig>") {
                    nextCursor.contains('.') shouldBe true
                }

                val second = resourcesList(harness, cursor = nextCursor)
                val secondUris = second.getAsJsonArray("resources")
                    .map { it.asJsonObject.get("uri").asString }
                withClue("${harness.name}: second page must carry the remaining 10 jobs") {
                    secondUris.size shouldBe 10
                }
                withClue("${harness.name}: second page MUST NOT re-emit any first-page id") {
                    secondUris.intersect(firstUris.toSet()).size shouldBe 0
                }
            }
        }
    }

    test("tampered resources/list cursor surfaces VALIDATION_ERROR on both transports") {
        // Plan-D §10.11 negative: a forged cursor MUST collapse to
        // `-32602 InvalidParams` with `error.data.dmigrateCode=VALIDATION_ERROR`,
        // NOT a silent restart at page 1.
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val response = harness.resourcesListRaw(cursor = "forged.signature")
                val err = response.error
                    ?: error("${harness.name}: tampered cursor must surface as JSON-RPC error")
                withClue("${harness.name}: must surface as -32602 InvalidParams") {
                    err.get("code").asInt shouldBe INVALID_PARAMS_CODE
                }
                val data = err.getAsJsonObject("data")
                    ?: error("${harness.name}: error must carry data.dmigrateCode")
                withClue("${harness.name}: dmigrateCode must equal VALIDATION_ERROR") {
                    data.get("dmigrateCode").asString shouldBe "VALIDATION_ERROR"
                }
            }
        }
    }

    test("resources/templates/list returns the static seven Phase-B templates on both transports") {
        // Plan-D §10.11 + AP 6.9 invariant: the template list is a
        // closed set of seven entries that does NOT include the
        // tenantless `dmigrate://capabilities` URI (per Plan-D §5.1
        // "Capability-Resource erscheint nicht in Templates").
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val raw = harness.resourcesTemplatesListRaw()
                val resultEl = raw.result
                    ?: error("${harness.name}: templates/list must return a result")
                val templates = resultEl.asJsonObject.getAsJsonArray("resourceTemplates")
                    ?: error("${harness.name}: templates/list missing resourceTemplates array")
                withClue("${harness.name}: Plan-D §10.11 acceptance — exactly 7 templates") {
                    templates.size() shouldBe TEMPLATE_COUNT
                }
                val uris = templates.map { it.asJsonObject.get("uriTemplate").asString }
                withClue("${harness.name}: capabilities URI must NOT appear in templates") {
                    uris.none { it.contains("dmigrate://capabilities") } shouldBe true
                }
            }
        }
    }
})

// ---- Helpers --------------------------------------------------------------

private const val INVALID_PARAMS_CODE: Int = -32602
private const val TEMPLATE_COUNT: Int = 7

private fun resourcesList(harness: McpClientHarness, cursor: String?): JsonObject {
    val raw = harness.resourcesListRaw(cursor)
    val resultEl = raw.result
        ?: error("${harness.name}: resources/list errored: ${raw.error}")
    return resultEl.asJsonObject
}

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
