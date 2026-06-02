package dev.dmigrate.cli.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.io.path.deleteRecursively


/**
 * LF-012 / LN-027 / LN-028 / LN-038 E1 smoke test: starts both transports against the
 * LF-012 / LN-027 / LN-028 / LN-038 file-backed wiring, runs `initialize` + `tools/list`
 * via the [McpClientHarness] surface, and asserts the
 * advertised tool set is identical between stdio and HTTP.
 *
 * The full LF-012 / LN-038 scenario lands in LF-012 / LN-011 / LN-017 / LN-027-LF-017 / LF-024 / LN-030 / LN-031; E1 only pins the
 * harness plumbing.
 *
 * Lives in :test:e2e-cli — the sub-project's test task only runs
 * with `-PintegrationTests` (siehe Root build.gradle.kts).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpHarnessSmokeTest : FunSpec({

    test("stdio harness completes initialize and tools/list against the file-backed wiring") {
        val stateDir = IntegrationFixtures.freshStateDir("dmigrate-it-stdio-")
        try {
            val harness = StdioHarness.start(
                stateDir = stateDir,
                principal = IntegrationFixtures.INTEGRATION_PRINCIPAL,
            )
            harness.use {
                val init = it.initialize()
                init.protocolVersion shouldStartWith "20"
                it.initializedNotification()
                val tools = it.toolsList()
                tools.tools.shouldNotBeEmpty()
                tools.tools.any { d -> d.name == "capabilities_list" } shouldBe true
            }
        } finally {
            stateDir.deleteRecursively()
        }
    }

    test("http harness completes initialize and tools/list against the file-backed wiring") {
        val stateDir = IntegrationFixtures.freshStateDir("dmigrate-it-http-")
        try {
            val harness = HttpHarness.start(
                stateDir = stateDir,
                principal = IntegrationFixtures.INTEGRATION_PRINCIPAL,
            )
            harness.use {
                val init = it.initialize()
                init.protocolVersion shouldStartWith "20"
                it.initializedNotification()
                val tools = it.toolsList()
                tools.tools.shouldNotBeEmpty()
                tools.tools.any { d -> d.name == "capabilities_list" } shouldBe true
            }
        } finally {
            stateDir.deleteRecursively()
        }
    }

    test("stdio + http advertise the same LF-012 / LN-038 tool set (drift guard)") {
        val stdioDir = IntegrationFixtures.freshStateDir("dmigrate-it-stdio-")
        val httpDir = IntegrationFixtures.freshStateDir("dmigrate-it-http-")
        try {
            val stdioTools: Set<String>
            val httpTools: Set<String>
            StdioHarness.start(stdioDir, IntegrationFixtures.freshTransportPrincipal("stdio")).use {
                it.initialize()
                it.initializedNotification()
                stdioTools = it.toolsList().tools.map { d -> d.name }.toSet()
            }
            HttpHarness.start(httpDir, IntegrationFixtures.freshTransportPrincipal("http")).use {
                it.initialize()
                it.initializedNotification()
                httpTools = it.toolsList().tools.map { d -> d.name }.toSet()
            }
            stdioTools shouldBe httpTools
        } finally {
            stdioDir.deleteRecursively()
            httpDir.deleteRecursively()
        }
    }
})
