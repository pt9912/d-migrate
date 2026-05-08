package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import dev.dmigrate.cli.commands.McpStateDirLock
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E7: lock-/concurrency tests for `<stateDir>/.lock` per
 * `ImpPlan-0.9.6-C.md` §6.24 + §6.21.
 *
 * Pflichtfluesse:
 *  - while a transport's harness server is running on a stateDir,
 *    a second harness `tryStart` on the SAME stateDir fails fast
 *    with a `LockConflict` BEFORE any audit event, ktor connector
 *    or pipe is constructed
 *  - the running server's audit sink stays empty across the failed
 *    second-start attempt (proves the lock-rejected path never
 *    reaches the dispatcher)
 *  - after the running server stops, a fresh `tryStart` on the same
 *    stateDir succeeds and can serve a `tools/call`
 *  - a stale lockfile payload (file present, no active OS lock —
 *    e.g. a crashed previous process) does NOT block a fresh start;
 *    the new payload overwrites the old verbatim
 *
 * Both transports run identical scenarios — each transport's
 * harness wires the same [McpStateDirLock] under the bootstrap, so
 * the conflict surfaces symmetrically.
 *
 * The unit-level lock semantics are pinned in
 * `McpStateDirLockTest`; this spec covers the harness-level
 * end-to-end exercise that AP 6.24 §6.24 demands.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpStateDirLockScenarioTest : FunSpec({

    tags(IntegrationTag)

    test("stdio: second tryStart on the same stateDir while first runs is LockConflict, no audit event") {
        val dir = Files.createTempDirectory("dmigrate-it-lock-stdio-")
        val first = StdioHarness.start(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
        try {
            first.initialize()
            first.initializedNotification()
            // Drain any initialize-time audit noise (currently zero —
            // initialize is auth-exempt and not audited — but pinning
            // the empty starting state makes the post-attempt assert
            // unambiguous).
            first.auditSink.recorded().shouldBeEmpty()

            val second = StdioHarness.tryStart(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
            val conflict = second.shouldBeInstanceOf<StdioHarness.StartOutcome.LockConflict>()
            withClue("conflict diagnostic must reference the contended stateDir") {
                conflict.diagnostic shouldContain dir.toString()
            }
            withClue("the lock-rejected start MUST NOT touch the audited dispatcher") {
                first.auditSink.recorded().shouldBeEmpty()
            }
        } finally {
            first.close()
            dir.deleteRecursively()
        }
    }

    test("http: second tryStart on the same stateDir while first runs is LockConflict, no audit event") {
        val dir = Files.createTempDirectory("dmigrate-it-lock-http-")
        val first = HttpHarness.start(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
        try {
            first.initialize()
            first.initializedNotification()
            first.auditSink.recorded().shouldBeEmpty()

            val second = HttpHarness.tryStart(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
            val conflict = second.shouldBeInstanceOf<HttpHarness.StartOutcome.LockConflict>()
            conflict.diagnostic shouldContain dir.toString()
            first.auditSink.recorded().shouldBeEmpty()
        } finally {
            first.close()
            dir.deleteRecursively()
        }
    }

    test("first server keeps serving tools/call after a concurrent lock-rejected second start") {
        // Defends against a regression where the rejected second
        // tryStart could close pipes / ports the first server is
        // still using. Run a tools/call BEFORE and AFTER the
        // failed attempt and assert both succeed identically.
        val dir = Files.createTempDirectory("dmigrate-it-lock-noimpact-")
        val first = StdioHarness.start(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
        try {
            first.initialize()
            first.initializedNotification()

            val before = first.toolsCall("capabilities_list", null)
            before.isError shouldBe false
            val beforeText = before.content.firstOrNull()?.text
                ?: error("capabilities_list returned no text content (before)")

            val rejected = StdioHarness.tryStart(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
            rejected.shouldBeInstanceOf<StdioHarness.StartOutcome.LockConflict>()

            val after = first.toolsCall("capabilities_list", null)
            withClue("first server must still serve tools/call after a rejected concurrent start") {
                after.isError shouldBe false
                val afterText = after.content.firstOrNull()?.text
                    ?: error("capabilities_list returned no text content (after)")
                // Text-equality would be too strict — the projection
                // carries a fresh `instance` per server invocation
                // and may carry server-uptime / clock-derived fields.
                // Pin a stable subset (the protocol version) so we
                // prove the dispatcher path is intact, not that the
                // wire payload is byte-identical.
                afterText shouldContain "mcpProtocolVersion"
                beforeText shouldContain "mcpProtocolVersion"
            }
        } finally {
            first.close()
            dir.deleteRecursively()
        }
    }

    test("stdio: after first server stops, a fresh tryStart on the same stateDir succeeds and serves") {
        val dir = Files.createTempDirectory("dmigrate-it-lock-restart-stdio-")
        try {
            val first = StdioHarness.start(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
            first.initialize()
            first.initializedNotification()
            first.close()

            val secondOutcome = StdioHarness.tryStart(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
            val second = secondOutcome.shouldBeInstanceOf<StdioHarness.StartOutcome.Started>().harness
            try {
                second.initialize()
                second.initializedNotification()
                val result = second.toolsCall("capabilities_list", null)
                result.isError shouldBe false
            } finally {
                second.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("http: after first server stops, a fresh tryStart on the same stateDir succeeds and serves") {
        val dir = Files.createTempDirectory("dmigrate-it-lock-restart-http-")
        try {
            val first = HttpHarness.start(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
            first.initialize()
            first.initializedNotification()
            first.close()

            val secondOutcome = HttpHarness.tryStart(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
            val second = secondOutcome.shouldBeInstanceOf<HttpHarness.StartOutcome.Started>().harness
            try {
                second.initialize()
                second.initializedNotification()
                val result = second.toolsCall("capabilities_list", null)
                result.isError shouldBe false
            } finally {
                second.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("stale lockfile payload without an active OS lock does not block a fresh harness start") {
        // Mirrors the unit-level "stale lockfile" case in
        // McpStateDirLockTest, but exercises the harness-level path
        // so we pin that the full bootstrap pipeline (wiring +
        // audit sink + ktor / pipes) clears the stale payload too.
        val dir = Files.createTempDirectory("dmigrate-it-lock-stale-")
        try {
            val lockfile = dir.resolve(McpStateDirLock.LOCKFILE_NAME)
            // Hand-rolled payload from a hypothetical crashed
            // previous process — file exists, OS lock does not.
            Files.writeString(
                lockfile,
                """{"pid":99999,"version":"0.0.0-crashed","stale":true}""",
                StandardCharsets.UTF_8,
            )

            val outcome = StdioHarness.tryStart(dir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
            val harness = outcome.shouldBeInstanceOf<StdioHarness.StartOutcome.Started>().harness
            try {
                harness.initialize()
                harness.initializedNotification()
                // Wiring is alive: tools/call must dispatch
                // successfully — proves the bootstrap was not
                // tripped up by the stale payload, only the lock
                // mechanic was.
                harness.toolsCall("capabilities_list", null).isError shouldBe false

                val rewritten = Files.readString(lockfile, StandardCharsets.UTF_8)
                withClue("the new harness must overwrite the stale payload's pid/version") {
                    rewritten.contains("\"pid\":99999") shouldBe false
                    rewritten.contains("\"stale\":true") shouldBe false
                    rewritten shouldContain "\"version\":\"0.0.0-it-stdio\""
                }
            } finally {
                harness.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
})
