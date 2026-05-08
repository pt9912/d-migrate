package dev.dmigrate.cli.integration

import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E1 smoke test: starts both transports against the
 * AP-6.21 file-backed wiring, runs `initialize` + `tools/list`
 * via the [McpClientHarness] surface, and asserts the
 * advertised tool set is identical between stdio and HTTP.
 *
 * The full Phase-C scenario lands in E2-E8; E1 only pins the
 * harness plumbing.
 *
 * Tagged `integration` so the default fast-test loop skips it
 * (see root `build.gradle.kts` — `kotest.tags=!integration & !perf`
 * by default; `-PintegrationTests` flips it on). Uses the same
 * file-level `IntegrationTag` constant as the other Phase-F E2E
 * tests in this module — Kotest 6's discovery filter has been
 * observed to ignore inline `tags(NamedTag("…"))` calls in CI,
 * even though they work locally.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpHarnessSmokeTest : FunSpec({

    tags(IntegrationTag)

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

    test("stdio + http advertise the same Phase-C tool set (drift guard)") {
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
