package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E8(C): audit-event correlation per
 * `ImpPlan-0.9.6-C.md` §6.24 Akzeptanz Z. 2092-2099.
 *
 * Pinned invariants:
 *
 *  - Every `tools/call` that reaches the dispatcher produces
 *    EXACTLY ONE [AuditEvent] on the per-transport audit sink.
 *  - The event's `requestId` is non-empty (server-generated; clients
 *    don't set this), `toolName` matches the call's `params.name`,
 *    and `tenantId` / `principalId` match the bound principal.
 *  - SUCCESS outcomes carry `outcome=SUCCESS` and `errorCode=null`.
 *  - Tool-error envelopes (`isError=true` on the wire) carry
 *    `outcome=FAILURE` and a typed [ToolErrorCode].
 *  - Pre-dispatch JSON-RPC errors (`-32601 Method not found` for an
 *    unknown tool name) produce ZERO audit events — the dispatcher
 *    is never reached, so there is nothing to audit.
 *  - Audit sinks are per-transport and do not cross-pollinate: a
 *    call against the stdio harness MUST NOT show up on the http
 *    harness sink and vice versa.
 *
 * The matching wire-vs-audit shape coherence (a `RESOURCE_NOT_FOUND`
 * tool-error envelope mirrors `errorCode=RESOURCE_NOT_FOUND` on the
 * audit side) is also pinned here — it is the only signal an
 * operator has that the audit log and the wire response describe
 * the same dispatch.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpAuditCorrelationScenarioTest : FunSpec({

    tags(IntegrationTag)

    test("a successful tools/call records exactly one SUCCESS audit event with correct correlation") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val sinkBefore = harness.auditSinkRecorded()
                sinkBefore.shouldBeEmpty()

                val result = harness.toolsCall("capabilities_list", null)
                result.isError shouldBe false

                val events = harness.auditSinkRecorded()
                withClue("${harness.name} must produce exactly one audit event for a dispatched tools/call") {
                    events.size shouldBe 1
                }
                val event = events.single()
                assertSuccessCorrelation(harness, event, expectedToolName = "capabilities_list")
            }
        }
    }

    test("a tool-error envelope records exactly one FAILURE audit event with the typed errorCode") {
        // schema_compare with two unknown schemaRefs surfaces a typed
        // RESOURCE_NOT_FOUND tool-error envelope (no-oracle by §5.6).
        // Both surfaces — wire isError=true and audit
        // outcome=FAILURE/errorCode=RESOURCE_NOT_FOUND — must be
        // consistent for the same dispatch.
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val tenant = harness.principal.effectiveTenantId.value
                val args = JsonParser.parseString(
                    """{"left":{"schemaRef":"dmigrate://tenants/$tenant/schemas/never-existed-a"},""" +
                        """"right":{"schemaRef":"dmigrate://tenants/$tenant/schemas/never-existed-b"}}""",
                ).asJsonObject

                val result = harness.toolsCall("schema_compare", args)
                withClue("${harness.name} schema_compare on missing schemaRefs must surface as isError=true") {
                    result.isError shouldBe true
                }
                val envelope = parsePayload(
                    result.content.firstOrNull()?.text
                        ?: error("${harness.name}: tool-error envelope had no text content"),
                )
                envelope.get("code").asString shouldBe ToolErrorCode.RESOURCE_NOT_FOUND.name

                val events = harness.auditSinkRecorded()
                withClue("${harness.name} tool-error must still produce exactly one audit event") {
                    events.size shouldBe 1
                }
                val event = events.single()
                assertFailureCorrelation(
                    harness,
                    event,
                    expectedToolName = "schema_compare",
                    expectedErrorCode = ToolErrorCode.RESOURCE_NOT_FOUND,
                )
            }
        }
    }

    test("an unknown tool name produces a JSON-RPC -32601 and ZERO audit events on either transport") {
        // Pre-dispatch JSON-RPC errors are NOT auditable — the
        // dispatcher hasn't decided yet which tool runs. The audit
        // sink staying empty is the only sign that a malformed
        // tool name didn't slip an event onto the audit log.
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val sinkBefore = harness.auditSinkRecorded()
                sinkBefore.shouldBeEmpty()

                val ex = runCatching {
                    harness.toolsCall("definitely_not_a_tool_2026", null)
                }
                withClue("${harness.name} unknown tool must throw (-32601 Method not found, surfaces in harness)") {
                    ex.isFailure shouldBe true
                }

                val events = harness.auditSinkRecorded()
                withClue("${harness.name} pre-dispatch failure MUST NOT produce an audit event") {
                    events.shouldBeEmpty()
                }
            }
        }
    }

    test("per-transport audit sinks are isolated; events from stdio do not appear on http and vice versa") {
        withFreshTransports { s, h ->
            // Drive each transport with a different tool — any
            // cross-pollination would be obvious in the toolName
            // field below.
            s.toolsCall("capabilities_list", null).isError shouldBe false
            h.toolsCall(
                "schema_validate",
                JsonParser.parseString("""{"schema":{"name":"x","version":"1.0","tables":{}}}""").asJsonObject,
            ).isError shouldBe false

            val stdioEvents = s.auditSinkRecorded()
            val httpEvents = h.auditSinkRecorded()

            withClue("stdio sink must hold ONLY the stdio call") {
                stdioEvents.size shouldBe 1
                stdioEvents.single().toolName shouldBe "capabilities_list"
            }
            withClue("http sink must hold ONLY the http call") {
                httpEvents.size shouldBe 1
                httpEvents.single().toolName shouldBe "schema_validate"
            }
        }
    }
})

// --- E8(C) helpers ----------------------------------------------------------

private fun McpClientHarness.auditSinkRecorded(): List<AuditEvent> = when (this) {
    is StdioHarness -> auditSink.recorded()
    is HttpHarness -> auditSink.recorded()
    else -> error("unsupported harness type for audit-sink access: ${this::class}")
}

private fun assertSuccessCorrelation(
    harness: McpClientHarness,
    event: AuditEvent,
    expectedToolName: String,
) {
    withClue("${harness.name} success: requestId must be non-empty (server-generated)") {
        check(event.requestId.isNotEmpty()) { "empty requestId on success event: $event" }
    }
    event.toolName shouldBe expectedToolName
    event.outcome shouldBe AuditOutcome.SUCCESS
    event.errorCode shouldBe null
    event.tenantId shouldBe harness.principal.effectiveTenantId
    event.principalId shouldBe harness.principal.principalId
}

private fun assertFailureCorrelation(
    harness: McpClientHarness,
    event: AuditEvent,
    expectedToolName: String,
    expectedErrorCode: ToolErrorCode,
) {
    withClue("${harness.name} failure: requestId must be non-empty (server-generated)") {
        check(event.requestId.isNotEmpty()) { "empty requestId on failure event: $event" }
    }
    event.toolName shouldBe expectedToolName
    event.outcome shouldBe AuditOutcome.FAILURE
    event.errorCode shouldBe expectedErrorCode
    event.tenantId shouldBe harness.principal.effectiveTenantId
    event.principalId shouldBe harness.principal.principalId
}

private fun parsePayload(text: String): JsonObject =
    JsonParser.parseString(text).asJsonObject

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

