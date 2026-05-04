package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E6: transport-neutral coverage of `resources/read` per
 * `ImpPlan-0.9.6-C.md` §6.24 + §7.3.
 *
 * Pflichtfluesse:
 *  - Happy path: stage a Phase-C resource (schema / artifact / job)
 *    on each transport's wiring, read the resource via
 *    `resources/read`, and pin that the projection carries the same
 *    `uri`/`tenantId` and the kind-specific identity field on stdio
 *    AND HTTP.
 *  - No-oracle: an unknown id within the principal's tenant, a
 *    foreign-tenant URI and the non-readable `upload-sessions` kind
 *    each surface the same JSON-RPC error class on both transports
 *    (same code, same scrubbed message), with no record-existence
 *    leak in either branch.
 *  - URI-grammar failures collapse to the constant
 *    `"invalid resource URI"` InvalidParams message — the same wire
 *    shape both transports must emit.
 *
 * Each transport gets its own state-dir / wiring; the staging
 * helpers in [IntegrationFixtures] populate the in-memory stores
 * directly so the read flow does NOT depend on the upload pipeline
 * tested in E4.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpResourcesReadScenarioTest : FunSpec({

    tags(IntegrationTag)

    test("resources/read on a staged schema returns identical projection on stdio + http") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val schemaUri = IntegrationFixtures.stageSchema(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    schemaId = SCHEMA_ID,
                    json = """{ "fields": [{"name":"id","type":"INT"}] }""",
                )
                val body = readContent(harness, schemaUri)
                withClue("${harness.name} schema projection must echo back the requested URI") {
                    body.get("uri").asString shouldBe schemaUri
                }
                withClue("${harness.name} schema projection must carry the schemaId") {
                    body.get("schemaId").asString shouldBe SCHEMA_ID
                }
                withClue("${harness.name} schema projection must carry the tenantId") {
                    body.get("tenantId").asString shouldBe harness.principal.effectiveTenantId.value
                }
            }
        }
    }

    test("resources/read on a staged job returns the job projection on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val tenant = harness.principal.effectiveTenantId.value
                IntegrationFixtures.stageJob(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    jobId = JOB_ID,
                    operation = "schema_generate",
                )
                val body = readContent(harness, "dmigrate://tenants/$tenant/jobs/$JOB_ID")
                withClue("${harness.name} job projection must carry the jobId") {
                    body.get("jobId").asString shouldBe JOB_ID
                }
                withClue("${harness.name} job projection must carry the operation") {
                    body.get("operation").asString shouldBe "schema_generate"
                }
                withClue("${harness.name} job projection must carry the tenantId") {
                    body.get("tenantId").asString shouldBe tenant
                }
            }
        }
    }

    test("resources/read on a staged artifact returns the artifact projection on both transports") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val tenant = harness.principal.effectiveTenantId.value
                IntegrationFixtures.stageArtifact(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    artifactId = ARTIFACT_ID,
                    content = "fixture-payload".toByteArray(),
                )
                val body = readContent(harness, "dmigrate://tenants/$tenant/artifacts/$ARTIFACT_ID")
                withClue("${harness.name} artifact projection must carry the artifactId") {
                    body.get("artifactId").asString shouldBe ARTIFACT_ID
                }
                withClue("${harness.name} artifact projection must carry the tenantId") {
                    body.get("tenantId").asString shouldBe tenant
                }
            }
        }
    }

    test("resources/read for an unknown id surfaces an identical -32002 error on both transports") {
        // Pin BOTH the wire code AND the scrubbed message string so a
        // future change that adds a "did you mean ...?" hint or echoes
        // the URI back into the message immediately fails the no-oracle
        // assertion. The transports MUST agree by the time the
        // server's responseError reaches the client.
        withFreshTransports { s, h ->
            val tenant = s.principal.effectiveTenantId.value
            val unknownUri = "dmigrate://tenants/$tenant/jobs/$NONEXISTENT_ID"
            val (stdioErr, httpErr) = bothErrors(s, h) { it.resourcesReadRaw(unknownUri) }
            assertNoOracleEqual(stdioErr, httpErr, expectedCode = MCP_RESOURCE_NOT_FOUND, expectedMessage = MSG_NOT_FOUND)
        }
    }

    test("resources/read for a foreign tenant URI surfaces an identical -32600 tenant-scope-denied on both transports") {
        // The principal is admin in INTEGRATION_TENANT but not in
        // "foreign" — TenantScopeChecker.isInScope is strict
        // equality, so the protocol layer rejects BEFORE any store
        // lookup happens. Both transports MUST return the exact same
        // wire shape.
        withFreshTransports { s, h ->
            val foreignUri = "dmigrate://tenants/$FOREIGN_TENANT/jobs/$NONEXISTENT_ID"
            val (stdioErr, httpErr) = bothErrors(s, h) { it.resourcesReadRaw(foreignUri) }
            assertNoOracleEqual(
                stdioErr,
                httpErr,
                expectedCode = JSONRPC_INVALID_REQUEST,
                expectedMessage = MSG_TENANT_DENIED,
            )
        }
    }

    test("resources/read on the upload-sessions kind collapses into the same -32002 no-oracle error") {
        // upload-sessions are not MCP-readable; the protocol must
        // return the SAME code and SAME message as a missing resource
        // so an attacker cannot probe the upload-session id space.
        withFreshTransports { s, h ->
            val tenant = s.principal.effectiveTenantId.value
            val sessionUri = "dmigrate://tenants/$tenant/upload-sessions/probe-id"
            val (stdioErr, httpErr) = bothErrors(s, h) { it.resourcesReadRaw(sessionUri) }
            assertNoOracleEqual(stdioErr, httpErr, expectedCode = MCP_RESOURCE_NOT_FOUND, expectedMessage = MSG_NOT_FOUND)
        }
    }

    test("resources/read with malformed URI returns the constant invalid-uri InvalidParams on both transports") {
        // Multiple grammar failure modes (bad scheme, unknown kind,
        // illegal segment chars) MUST collapse into the same constant
        // message — varying messages would let a caller probe URI
        // shape without ever touching a store.
        withFreshTransports { s, h ->
            val grammarVariants = listOf(
                "https://example.com/resource",
                "dmigrate://tenants/${s.principal.effectiveTenantId.value}/unknown-kind/x",
                "dmigrate://tenants/!!!/jobs/x",
            )
            for (input in grammarVariants) {
                val (stdioErr, httpErr) = bothErrors(s, h) { it.resourcesReadRaw(input) }
                assertNoOracleEqual(
                    stdioErr,
                    httpErr,
                    expectedCode = JSONRPC_INVALID_PARAMS,
                    expectedMessage = MSG_INVALID_URI,
                )
            }
        }
    }
})

// --- E6 fixtures + helpers --------------------------------------------------

private const val SCHEMA_ID: String = "schema-e6-fixture"
private const val JOB_ID: String = "job-e6-fixture"
private const val ARTIFACT_ID: String = "art-e6-fixture"
private const val NONEXISTENT_ID: String = "never-existed"
private const val FOREIGN_TENANT: String = "foreign"

/** MCP custom error code for `resources/read`-not-found per the 2025-11-25 spec. */
private const val MCP_RESOURCE_NOT_FOUND: Int = -32002
private const val JSONRPC_INVALID_REQUEST: Int = -32600
private const val JSONRPC_INVALID_PARAMS: Int = -32602

private const val MSG_NOT_FOUND: String = "Resource not found"
private const val MSG_TENANT_DENIED: String = "tenant scope denied for requested resource"
private const val MSG_INVALID_URI: String = "invalid resource URI"

/**
 * Scrubbed, transport-comparable view of a JSON-RPC error: the
 * `code` + `message` pair after dynamic-id normalisation. Per
 * §6.24 only these two fields cross the no-oracle equality bar;
 * `data` is allowed to vary if either transport ships it.
 */
private data class RpcErrorClass(val code: Int, val message: String)

private fun JsonRpcResponse.errorClassOrThrow(transportName: String): RpcErrorClass {
    val err = error ?: error("$transportName: expected JSON-RPC error, got result=$result")
    val code = err.get("code")?.asInt
        ?: error("$transportName: error envelope missing 'code': $err")
    val message = err.get("message")?.asString
        ?: error("$transportName: error envelope missing 'message': $err")
    return RpcErrorClass(code, message)
}

private fun bothErrors(
    stdio: StdioHarness,
    http: HttpHarness,
    call: (McpClientHarness) -> JsonRpcResponse,
): Pair<RpcErrorClass, RpcErrorClass> {
    val stdioErr = call(stdio).errorClassOrThrow(stdio.name)
    val httpErr = call(http).errorClassOrThrow(http.name)
    return stdioErr to httpErr
}

private fun assertNoOracleEqual(
    stdio: RpcErrorClass,
    http: RpcErrorClass,
    expectedCode: Int,
    expectedMessage: String,
) {
    withClue("stdio + http MUST return identical no-oracle wire shape") {
        stdio shouldBe http
    }
    withClue("error code must equal the documented MCP wire code") {
        stdio.code shouldBe expectedCode
    }
    withClue("error message must equal the constant scrubbed class") {
        stdio.message shouldBe expectedMessage
    }
}

private fun readContent(harness: McpClientHarness, uri: String): JsonObject {
    val result = harness.resourcesReadRaw(uri)
    val resultEl = result.result
        ?: error("${harness.name}: resources/read returned error: ${result.error}")
    val contents = resultEl.asJsonObject.getAsJsonArray("contents")
        ?: error("${harness.name}: resources/read result missing 'contents' array")
    require(contents.size() == 1) { "${harness.name}: expected 1 content slice, got ${contents.size()}" }
    val slice = contents.get(0).asJsonObject
    val text = slice.get("text")?.asString
        ?: error("${harness.name}: content slice missing 'text'")
    return com.google.gson.JsonParser.parseString(text).asJsonObject
}

/**
 * E6 reuses the E2/E3/E4/E5 transport-pair pattern. Block does its
 * own assertions; cleanup runs even on failure.
 */
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
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        try { stdioDir.deleteRecursively() } catch (_: Throwable) {}
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        try { httpDir.deleteRecursively() } catch (_: Throwable) {}
    }
}
