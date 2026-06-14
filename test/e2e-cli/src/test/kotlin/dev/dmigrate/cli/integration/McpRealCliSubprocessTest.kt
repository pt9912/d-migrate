package dev.dmigrate.cli.integration

import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.deleteRecursively


/**
 * LF-012 / LN-027 / LN-028 / LN-038 Z. 1839 + 1963 + final-review point 1: spawn the
 * REAL CLI binary as a child JVM and exercise the full
 * `mcp serve --transport stdio` lifecycle:
 *
 *   - `McpCommand.run()` parses Clikt args
 *   - `StateDirOwner.resolve(...)` materialises the state dir
 *   - `McpStateDirLock.tryAcquire(...)` acquires `.lock`
 *   - `McpCliRuntimeWiring.runtimeWiring(stateDir)` builds the
 *     production file-backed wiring (NOT the test-only
 *     `IntegrationFixtures.integrationWiring`)
 *   - `McpServerBootstrap.startStdio(...)` enters the NDJSON loop
 *   - `McpServerLifecycle.run(...)` blocks on `awaitTermination`,
 *     then runs the cleanup hook (idempotent stop + lock release +
 *     CLI-owned-tempdir deletion)
 *
 * The harness used by E1–LF-017 / LF-024 / LN-030 / LN-031 stops at `McpServerBootstrap.startStdio`
 * and bypasses everything above it; this spec is the only one that
 * proves those upper layers actually run end-to-end.
 *
 * The subprocess is launched via `java -cp <test runtime classpath>
 * dev.dmigrate.cli.MainKt` — no `installDist` build step needed.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpRealCliSubprocessTest : FunSpec({


    test("real CLI subprocess: mcp serve --transport stdio initialises and exits cleanly on stdin EOF") {
        val stateDir = Files.createTempDirectory("dmigrate-it-real-cli-")
        try {
            val cli = startRealCliSubprocess(stateDir.toString())
            try {
                // Wait for the operator-facing startup line on stderr
                // — this is the signal McpCommand.echoStartStateLine
                // / startStdio emits AFTER lock-acquisition + bootstrap.
                val ready = cli.awaitStderrLine(
                    contains = "MCP stdio server started",
                    timeoutMs = STARTUP_TIMEOUT_MS,
                )
                withClue("CLI subprocess must emit the documented startup line on stderr (saw: ${cli.stderrSnapshot()})") {
                    ready shouldBe true
                }

                val response = cli.requestResponse(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
                        """{"protocolVersion":"2025-11-25",""" +
                        """"clientInfo":{"name":"dmigrate-it-real-cli","version":"0.0.0"},""" +
                        """"capabilities":{}}}""",
                )
                val parsed = JsonParser.parseString(response).asJsonObject
                withClue("initialize id must echo back as 1") {
                    parsed.get("id").asInt shouldBe 1
                }
                val result = parsed.getAsJsonObject("result")
                    ?: error("initialize response had no result; raw=$response")
                result.get("protocolVersion").asString shouldContain "20"

                // Send notifications/initialized — no response.
                cli.send(
                    """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                )

                // Close stdin → stdio loop sees EOF → server stops →
                // McpServerLifecycle cleanup runs → JVM exits 0.
                cli.closeStdin()
                val exit = cli.awaitExit(EXIT_TIMEOUT_MS)
                withClue("real CLI subprocess must exit cleanly on stdin close (stderr=${cli.stderrSnapshot()})") {
                    exit shouldBe 0
                }
            } finally {
                cli.killIfAlive()
            }
        } finally {
            stateDir.deleteRecursively()
        }
    }
})

// Subprocess-Plumbing (CliSubprocess + startRealCliSubprocess): RealCliSubprocess.kt

private const val STARTUP_TIMEOUT_MS: Long = 30_000
private const val EXIT_TIMEOUT_MS: Long = 10_000
