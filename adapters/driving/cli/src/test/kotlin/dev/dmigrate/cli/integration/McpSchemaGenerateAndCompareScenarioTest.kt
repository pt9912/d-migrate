package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E3: transport-neutral coverage of `schema_generate` and
 * `schema_compare` per §7.3 (Pflichtfluesse):
 *
 *  - `schema_generate` with a small inline schema: both transports
 *    return identical `dialect` / `statementCount` / `ddl` /
 *    `truncated=false` payloads
 *  - `schema_generate` with a large generated DDL: both transports
 *    surface the same artefact-fallback shape (`truncated=true` +
 *    `artifactRef` set to the Phase-C tenant artefact URI, no inline
 *    `ddl`)
 *  - `schema_compare` of two materialised schema refs (identical
 *    schemas): both transports return the same `identical=true`
 *    payload after URI normalisation
 *  - `schema_compare` of two diverging schemas (added table on one
 *    side): both transports return the same `findings`/`details`
 *    projection
 *
 * Each transport runs in its own state-dir / wiring (§6.24); the
 * test stages two schemas per transport via [IntegrationFixtures.stageSchema]
 * before calling `schema_compare`, so neither side depends on the
 * other's persisted state.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpSchemaGenerateAndCompareScenarioTest : FunSpec({

    tags(IntegrationTag)

    test("schema_generate small inline schema is transport-equivalent") {
        val arguments = JsonParser.parseString(
            """{"schema":{"name":"orders","version":"1.0",""" +
                """"tables":{"t1":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}}},""" +
                """"targetDialect":"POSTGRESQL"}""",
        ).asJsonObject

        val (stdio, http) = withFreshTransports { s, h ->
            parsePayload(s.toolsCall("schema_generate", arguments).text()) to
                parsePayload(h.toolsCall("schema_generate", arguments).text())
        }
        for ((name, payload) in listOf("stdio" to stdio, "http" to http)) {
            withClue("$name dialect must be POSTGRESQL") {
                payload.get("dialect").asString shouldBe "POSTGRESQL"
            }
            withClue("$name truncated must be false for small DDL") {
                payload.get("truncated").asBoolean shouldBe false
            }
            withClue("$name must surface inline ddl") {
                payload.has("ddl") shouldBe true
                payload.has("artifactRef") shouldBe false
            }
        }
        // The DDL header carries `Generated: <Instant.now()>` per
        // `AbstractDdlGenerator`, which differs between the two
        // transports' calls. Mask `ddl` for the structural equality
        // check — `dialect`, `statementCount`, `summary`, `findings`,
        // `truncated` already pin the business shape per-transport.
        TransportNormalisation.maskFields(stdio, REQUEST_ID_MASK + "ddl") shouldBe
            TransportNormalisation.maskFields(http, REQUEST_ID_MASK + "ddl")
    }

    test("schema_generate large DDL spills to artefact on both transports") {
        // maxToolResponseBytes=1024 → inlineThreshold=512. A four-table
        // schema with a handful of columns each generates well above
        // 512 bytes of DDL.
        val tinyLimits = McpLimitsConfig(maxToolResponseBytes = SMALL_RESPONSE_LIMIT)
        val tables = (1..GENERATE_TABLES).joinToString(",") { idx ->
            """"orders_$idx":{"columns":{"id":{"type":"identifier"},""" +
                """"customer_$idx":{"type":"text","max_length":255},""" +
                """"order_total_$idx":{"type":"decimal","precision":12,"scale":2}},""" +
                """"primary_key":["id"]}"""
        }
        val arguments = JsonParser.parseString(
            """{"schema":{"name":"orders","version":"1.0","tables":{$tables}},""" +
                """"targetDialect":"POSTGRESQL"}""",
        ).asJsonObject

        val (stdio, http) = withFreshTransports(limits = tinyLimits) { s, h ->
            parsePayload(s.toolsCall("schema_generate", arguments).text()) to
                parsePayload(h.toolsCall("schema_generate", arguments).text())
        }
        for ((name, payload) in listOf("stdio" to stdio, "http" to http)) {
            withClue("$name DDL overflow must set truncated=true") {
                payload.get("truncated").asBoolean shouldBe true
            }
            withClue("$name must spill DDL to artefact (no inline ddl)") {
                payload.has("ddl") shouldBe false
            }
            withClue("$name artifactRef must be a Phase-C tenant artefact URI") {
                payload.get("artifactRef")?.asString
                    ?.startsWith("dmigrate://tenants/") shouldBe true
            }
        }
        val maskedStdio = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(stdio),
            REQUEST_ID_MASK,
        )
        val maskedHttp = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(http),
            REQUEST_ID_MASK,
        )
        maskedStdio shouldBe maskedHttp
    }

    test("schema_compare of identical schemaRefs returns status=identical on both transports") {
        val (stdio, http) = withFreshTransports { s, h ->
            val sLeft = IntegrationFixtures.stageSchema(s.wiring, s.principal, "left", schemaJson("orders", "t1"))
            val sRight = IntegrationFixtures.stageSchema(s.wiring, s.principal, "right", schemaJson("orders", "t1"))
            val hLeft = IntegrationFixtures.stageSchema(h.wiring, h.principal, "left", schemaJson("orders", "t1"))
            val hRight = IntegrationFixtures.stageSchema(h.wiring, h.principal, "right", schemaJson("orders", "t1"))
            val sOut = parsePayload(
                s.toolsCall("schema_compare", compareArgs(sLeft, sRight)).text(),
            )
            val hOut = parsePayload(
                h.toolsCall("schema_compare", compareArgs(hLeft, hRight)).text(),
            )
            sOut to hOut
        }
        for ((name, payload) in listOf("stdio" to stdio, "http" to http)) {
            withClue("$name identical schemas must produce status=identical") {
                payload.get("status").asString shouldBe "identical"
            }
            withClue("$name truncated must be false for an empty diff") {
                payload.get("truncated").asBoolean shouldBe false
            }
            withClue("$name must NOT advertise diffArtifactRef for an identical compare") {
                payload.has("diffArtifactRef") shouldBe false
            }
        }
        val maskedStdio = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(stdio),
            REQUEST_ID_MASK,
        )
        val maskedHttp = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(http),
            REQUEST_ID_MASK,
        )
        maskedStdio shouldBe maskedHttp
    }

    test("schema_compare of diverging schemaRefs surfaces transport-equivalent findings") {
        val (stdio, http) = withFreshTransports { s, h ->
            val sLeft = IntegrationFixtures.stageSchema(s.wiring, s.principal, "left", schemaJson("orders", "t1"))
            val sRight = IntegrationFixtures.stageSchema(s.wiring, s.principal, "right", schemaJson("orders", "t1", "t2"))
            val hLeft = IntegrationFixtures.stageSchema(h.wiring, h.principal, "left", schemaJson("orders", "t1"))
            val hRight = IntegrationFixtures.stageSchema(h.wiring, h.principal, "right", schemaJson("orders", "t1", "t2"))
            val sOut = parsePayload(
                s.toolsCall("schema_compare", compareArgs(sLeft, sRight)).text(),
            )
            val hOut = parsePayload(
                h.toolsCall("schema_compare", compareArgs(hLeft, hRight)).text(),
            )
            sOut to hOut
        }
        for ((name, payload) in listOf("stdio" to stdio, "http" to http)) {
            withClue("$name diverging schemas must produce status=different") {
                payload.get("status").asString shouldBe "different"
            }
            withClue("$name findings must be non-empty for an added table") {
                payload.getAsJsonArray("findings").size() shouldBe 1
            }
            withClue("$name added-table finding must carry the canonical TABLE_ADDED code") {
                val first = payload.getAsJsonArray("findings").get(0).asJsonObject
                first.get("code").asString shouldBe "TABLE_ADDED"
            }
        }
        val maskedStdio = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(stdio),
            REQUEST_ID_MASK,
        )
        val maskedHttp = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(http),
            REQUEST_ID_MASK,
        )
        maskedStdio shouldBe maskedHttp
    }
})

private val REQUEST_ID_MASK: Set<String> = setOf("requestId")
private const val SMALL_RESPONSE_LIMIT: Int = 1024
private const val GENERATE_TABLES: Int = 4

private fun schemaJson(name: String, vararg tables: String): String {
    val tablePairs = tables.joinToString(",") { table ->
        """"$table":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}"""
    }
    return """{"name":"$name","version":"1.0","tables":{$tablePairs}}"""
}

private fun compareArgs(leftRef: String, rightRef: String): JsonObject =
    JsonParser.parseString(
        """{"left":{"schemaRef":"$leftRef"},"right":{"schemaRef":"$rightRef"}}""",
    ).asJsonObject

/**
 * E3 reuses the E2 transport-pair pattern. Kept private here so each
 * scenario file owns its own setup/teardown shape — they may diverge
 * in later APs (E4 Upload-Flow, E7 Lock-Konkurrenz).
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
        // Surface the wire envelope on the failure path — without it
        // a test assertion against a missing success field reads as an
        // opaque NPE.
        error("tools/call returned isError=true; body=$body")
    }
    return body
}

private fun parsePayload(text: String): JsonObject =
    JsonParser.parseString(text).asJsonObject
