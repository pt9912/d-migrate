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

    test("schema_generate via schemaRef (materialised) is transport-equivalent") {
        // §7.3: schema_generate with `schemaRef` (not inline schema) —
        // the source resolver must accept either form. Stage a small
        // schema first, then call schema_generate with the resulting
        // dmigrate://-URI as schemaRef.
        val (stdio, http) = withFreshTransports { s, h ->
            val sRef = IntegrationFixtures.stageSchema(
                s.wiring, s.principal, "gen-by-ref-stdio",
                schemaJson("orders", "t1"),
            )
            val hRef = IntegrationFixtures.stageSchema(
                h.wiring, h.principal, "gen-by-ref-http",
                schemaJson("orders", "t1"),
            )
            val sArgs = JsonParser.parseString(
                """{"schemaRef":"$sRef","targetDialect":"POSTGRESQL"}""",
            ).asJsonObject
            val hArgs = JsonParser.parseString(
                """{"schemaRef":"$hRef","targetDialect":"POSTGRESQL"}""",
            ).asJsonObject
            parsePayload(s.toolsCall("schema_generate", sArgs).text()) to
                parsePayload(h.toolsCall("schema_generate", hArgs).text())
        }
        for ((name, payload) in listOf("stdio" to stdio, "http" to http)) {
            withClue("$name schemaRef path must produce inline DDL for a small schema") {
                payload.get("dialect").asString shouldBe "POSTGRESQL"
                payload.get("truncated").asBoolean shouldBe false
                payload.has("ddl") shouldBe true
                payload.has("artifactRef") shouldBe false
            }
        }
        // Mask schemaId-bearing URIs and the time-stamp DDL header
        // so structural equality holds across the two transports.
        val maskedStdio = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(stdio),
            REQUEST_ID_MASK + "ddl",
        )
        val maskedHttp = TransportNormalisation.maskFields(
            TransportNormalisation.normaliseResourceUris(http),
            REQUEST_ID_MASK + "ddl",
        )
        maskedStdio shouldBe maskedHttp
    }

    // §7.3 line 1944-1945 mentions a "Findings-only-Overflow"
    // truncation case for `schema_generate` — i.e. the inline DDL
    // stays under budget but the `findings` array exceeds
    // `maxInlineFindings`. Triggering that case organically requires
    // a schema-shape that the PostgreSQL generator emits many
    // `TransformationNote`s or `SkippedObject`s for, which is
    // driver-specific and brittle to pin in an integration test.
    //
    // The truncated/artifactRef-coupling that the bullet protects is
    // already exercised end-to-end by E2's
    // `schema_validate truncation produces transport-equivalent
    // artifactRef shape` (same `maxInlineFindings` boundary, same
    // `allOf` enforced by the typed output schema). E8(A)'s
    // `schema_compare truncated=true output (diffArtifactRef
    // coupling) validates against the schema` covers the variant
    // for `schema_compare`. Re-deriving the same coupling for
    // `schema_generate` against the actual generator-finding stream
    // is a Phase-D follow-up — driver-supplied note shape is not
    // stable enough for a deterministic integration scenario today.
    //
    // Tracked under §7.3 final-review as a known-deferred carve-out.

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

    test("schema_compare of large diverging schemas surfaces diffArtifactRef + details.before/after coupling") {
        // §7.3: schema_compare must surface a `diffArtifactRef` when
        // findings overflow `maxInlineFindings`. The remaining
        // findings spill to the artefact; the inline tail keeps the
        // structured `details.before/after` on each finding so the
        // PhaseBToolSchemas compareDetailsSchema (`{ before, after }`,
        // additionalProperties=false) coupling holds end-to-end.
        // Size-based truncation triggers diffArtifactRef when the
        // serialised diff exceeds `maxToolResponseBytes/2`. Setting
        // a tiny response budget AND a small inline-cap exercises
        // both knobs at once: the inline list caps at
        // maxInlineFindings, the size threshold forces the artifact.
        val tinyFindings = McpLimitsConfig(
            maxInlineFindings = COMPARE_OVERFLOW_FINDINGS,
            maxToolResponseBytes = COMPARE_OVERFLOW_RESPONSE_BUDGET,
        )
        val (stdio, http) = withFreshTransports(limits = tinyFindings) { s, h ->
            // Two schemas that diverge in many tables AND in the
            // top-level schema version. The schema-version change
            // emits ONE finding with typed details.before/after
            // (per SchemaCompareHandler's `beforeAfter(...)` slot),
            // and the table-set divergence stuffs the inline list
            // with TABLE_ADDED/REMOVED findings so the size budget
            // overflows and `diffArtifactRef` lights up.
            val sLeft = IntegrationFixtures.stageSchema(
                s.wiring, s.principal, "left",
                schemaJsonVersioned(
                    "orders", "1.0",
                    *(1..COMPARE_OVERFLOW_TABLES_LEFT).map { "t$it" }.toTypedArray(),
                ),
            )
            val sRight = IntegrationFixtures.stageSchema(
                s.wiring, s.principal, "right",
                schemaJsonVersioned(
                    "orders", "2.0",
                    *(1..COMPARE_OVERFLOW_TABLES_RIGHT).map { "u$it" }.toTypedArray(),
                ),
            )
            val hLeft = IntegrationFixtures.stageSchema(
                h.wiring, h.principal, "left",
                schemaJsonVersioned(
                    "orders", "1.0",
                    *(1..COMPARE_OVERFLOW_TABLES_LEFT).map { "t$it" }.toTypedArray(),
                ),
            )
            val hRight = IntegrationFixtures.stageSchema(
                h.wiring, h.principal, "right",
                schemaJsonVersioned(
                    "orders", "2.0",
                    *(1..COMPARE_OVERFLOW_TABLES_RIGHT).map { "u$it" }.toTypedArray(),
                ),
            )
            parsePayload(s.toolsCall("schema_compare", compareArgs(sLeft, sRight)).text()) to
                parsePayload(h.toolsCall("schema_compare", compareArgs(hLeft, hRight)).text())
        }
        for ((name, payload) in listOf("stdio" to stdio, "http" to http)) {
            withClue("$name large compare must set truncated=true") {
                payload.get("truncated").asBoolean shouldBe true
            }
            withClue("$name large compare must surface diffArtifactRef") {
                payload.has("diffArtifactRef") shouldBe true
                payload.get("diffArtifactRef").asString
                    .startsWith("dmigrate://tenants/") shouldBe true
            }
            withClue("$name inline findings still cap at maxInlineFindings") {
                payload.getAsJsonArray("findings").size() shouldBe COMPARE_OVERFLOW_FINDINGS
            }
            // The SCHEMA_VERSION_CHANGED finding carries typed
            // `details.before/after` per the compareDetailsSchema
            // contract. Search the inline-cap window for it (it's
            // always emitted before TABLE_ADDED/REMOVED but the
            // ordering is per-handler not per-test).
            withClue("$name inline findings must include a typed details.before/after entry") {
                val findings = payload.getAsJsonArray("findings")
                val versionChange = (0 until findings.size())
                    .map { findings.get(it).asJsonObject }
                    .firstOrNull { it.get("code").asString == "SCHEMA_VERSION_CHANGED" }
                    ?: error("$name: SCHEMA_VERSION_CHANGED missing from inline findings: $findings")
                val details = versionChange.getAsJsonObject("details")
                details.get("before").asString shouldBe "1.0"
                details.get("after").asString shouldBe "2.0"
            }
        }
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

// §7.3 final-review additions: caps for the schema_compare
// findings-overflow path. Bigger than the cap so we always cross
// it deterministically, regardless of the comparator's
// per-finding granularity.
private const val COMPARE_OVERFLOW_FINDINGS: Int = 3
private const val COMPARE_OVERFLOW_TABLES_LEFT: Int = 30
private const val COMPARE_OVERFLOW_TABLES_RIGHT: Int = 30

/**
 * 1 KB response budget → 512 B inline-diff threshold. With 60 diff
 * findings (≈ 100 B each in serialised JSON) the threshold is
 * crossed and the schema_compare handler writes the full diff to
 * the artefact sink, surfacing `diffArtifactRef`.
 */
private const val COMPARE_OVERFLOW_RESPONSE_BUDGET: Int = 1024

private fun schemaJson(name: String, vararg tables: String): String =
    schemaJsonVersioned(name, "1.0", *tables)

private fun schemaJsonVersioned(name: String, version: String, vararg tables: String): String {
    val tablePairs = tables.joinToString(",") { table ->
        """"$table":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}"""
    }
    return """{"name":"$name","version":"$version","tables":{$tablePairs}}"""
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
