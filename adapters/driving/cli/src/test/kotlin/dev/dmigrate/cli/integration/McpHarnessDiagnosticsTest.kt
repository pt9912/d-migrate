package dev.dmigrate.cli.integration

import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E8(C): proves the harness diagnostic dump per
 * `ImpPlan-0.9.6-C.md` §6.24 Z. 2040-2043 ("kompaktes
 * Diagnosepaket pro Transport bei Fehlschlag") works end-to-end.
 *
 * Pinned invariants:
 *  - the dump names the transport
 *  - it carries the supplied failure reason verbatim
 *  - it lists the recent JSON-RPC calls with method + outcome
 *    (and toolName for `tools/call`)
 *  - it includes the state-dir file listing — relative paths only,
 *    no content dump
 *  - oversize histories are bounded at
 *    [McpClientHarness.DIAGNOSTIC_RPC_HISTORY_SIZE]
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpHarnessDiagnosticsTest : FunSpec({

    tags(IntegrationTag)

    test("dumpDiagnostics on stdio + http names transport, reason, recent calls and stateDir files") {
        withFreshTransports { s, h ->
            // Drive a small variety of calls so the ring buffer
            // captures different methods + a tools/call with a
            // toolName tag. Both transports run the same script.
            for (harness in listOf(s, h)) {
                harness.toolsList()
                harness.toolsCall(
                    "schema_validate",
                    JsonParser.parseString("""{"schema":{"name":"x","version":"1.0","tables":{}}}""").asJsonObject,
                )
                harness.resourcesReadRaw("dmigrate://tenants/never/jobs/never") // will be err -32600/-32002
            }

            for (harness in listOf(s, h)) {
                val dump = harness.dumpDiagnostics("test-fixture: forced dump")
                withClue("$harness dump must name the transport") {
                    dump shouldContain "${harness.name} harness diagnostics"
                }
                withClue("$harness dump must echo the reason") {
                    dump shouldContain "test-fixture: forced dump"
                }
                withClue("$harness dump must include the stateDir path") {
                    dump shouldContain harness.stateDir.toString()
                }
                withClue("$harness dump must record the tools/list call") {
                    dump shouldContain "tools/list -> ok"
                }
                withClue("$harness dump must record the tools/call WITH toolName=schema_validate") {
                    dump shouldContain "tool=schema_validate"
                }
                withClue("$harness dump must record the resources/read err") {
                    dump shouldContain "resources/read"
                    // Either -32600 (tenant scope denied) or -32002 (not found)
                    // — exact code depends on dispatch order; the outcome
                    // tag must classify it as an err in either case.
                    dump shouldContain "err -32"
                }
                withClue("$harness dump must list the state-dir (even if empty)") {
                    dump shouldContain "stateDir files"
                }
            }
        }
    }

    test("dumpDiagnostics ring buffer is bounded at DIAGNOSTIC_RPC_HISTORY_SIZE") {
        // Drive more calls than the ring buffer fits and assert the
        // dump only carries the tail. The bound is an integer
        // contract — overflow risks ballooning a failure dump on a
        // stuck retry loop, so the cap matters.
        withFreshTransports { s, _ ->
            val cap = McpClientHarness.DIAGNOSTIC_RPC_HISTORY_SIZE
            // Initialize already added one entry. Drive cap+5
            // additional tools/list calls so the oldest must be
            // evicted regardless of pre-existing entries.
            repeat(cap + 5) { s.toolsList() }
            val dump = s.dumpDiagnostics("ring-buffer-bound-check")
            // Count "tools/list -> ok" occurrences as a proxy for
            // recorded entries — should be at most `cap`.
            val count = dump.split("tools/list -> ok").size - 1
            withClue("ring buffer must keep at most cap=$cap recorded entries (saw $count)") {
                (count <= cap) shouldBe true
            }
        }
    }
})

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
