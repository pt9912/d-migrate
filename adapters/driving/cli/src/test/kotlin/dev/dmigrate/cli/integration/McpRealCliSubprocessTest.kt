package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 §6.24 Z. 1839 + 1963 + final-review point 1: spawn the
 * REAL CLI binary as a child JVM and exercise the full
 * `mcp serve --transport stdio` lifecycle:
 *
 *   - `McpCommand.run()` parses Clikt args
 *   - `StateDirOwner.resolve(...)` materialises the state dir
 *   - `McpStateDirLock.tryAcquire(...)` acquires `.lock`
 *   - `McpCliPhaseCWiring.phaseCWiring(stateDir)` builds the
 *     production file-backed wiring (NOT the test-only
 *     `IntegrationFixtures.integrationWiring`)
 *   - `McpServerBootstrap.startStdio(...)` enters the NDJSON loop
 *   - `McpServerLifecycle.run(...)` blocks on `awaitTermination`,
 *     then runs the cleanup hook (idempotent stop + lock release +
 *     CLI-owned-tempdir deletion)
 *
 * The harness used by E1–E8 stops at `McpServerBootstrap.startStdio`
 * and bypasses everything above it; this spec is the only one that
 * proves those upper layers actually run end-to-end.
 *
 * The subprocess is launched via `java -cp <test runtime classpath>
 * dev.dmigrate.cli.MainKt` — no `installDist` build step needed.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpRealCliSubprocessTest : FunSpec({

    tags(IntegrationTag)

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

// --- subprocess plumbing ----------------------------------------------------

private const val STARTUP_TIMEOUT_MS: Long = 30_000
private const val EXIT_TIMEOUT_MS: Long = 10_000
private const val RESPONSE_TIMEOUT_MS: Long = 10_000

private class CliSubprocess(
    private val process: Process,
    private val stdoutLines: LinkedBlockingQueue<String>,
    private val stderrSink: StringBuilder,
    private val stdinWriter: PrintWriter,
    private val stderrReady: java.util.concurrent.atomic.AtomicReference<String?>,
) {

    fun send(line: String) {
        stdinWriter.println(line)
        stdinWriter.flush()
    }

    fun requestResponse(line: String): String {
        send(line)
        return stdoutLines.poll(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: error("real CLI subprocess: no response within ${RESPONSE_TIMEOUT_MS}ms; stderr=$stderrSink")
    }

    fun awaitStderrLine(contains: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val seen = stderrReady.get()
            if (seen != null && seen.contains(contains)) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    fun closeStdin() {
        stdinWriter.close()
    }

    fun awaitExit(timeoutMs: Long): Int {
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        return if (finished) process.exitValue() else -1
    }

    fun stderrSnapshot(): String = stderrSink.toString()

    fun killIfAlive() {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
        }
    }
}

private const val POLL_MS: Long = 50
private const val KILL_TIMEOUT_MS: Long = 5_000

private fun startRealCliSubprocess(stateDir: String): CliSubprocess {
    val javaBin = ProcessHandle.current().info().command().orElse("java")
    val classpath = System.getProperty("java.class.path")
        ?: error("test JVM has no java.class.path system property")

    val builder = ProcessBuilder(
        javaBin,
        "-cp", classpath,
        "dev.dmigrate.cli.MainKt",
        "mcp", "serve",
        "--transport", "stdio",
        "--mcp-state-dir", stateDir,
    ).redirectErrorStream(false)
    val process = builder.start()

    // Drain stdout into a blocking queue (one entry per JSON-RPC
    // response line). A daemon thread is the simplest pump and
    // ensures we never block the subprocess on a full pipe buffer.
    val stdoutLines = LinkedBlockingQueue<String>()
    val stdoutReader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
    Thread({
        try {
            while (true) {
                val line = stdoutReader.readLine() ?: break
                if (line.isNotBlank()) stdoutLines.put(line)
            }
        } catch (_: Throwable) { /* subprocess exited */ }
    }, "real-cli-stdout-pump").apply { isDaemon = true; start() }

    // Mirror stderr into a buffer + a "latest line" reference so
    // tests can poll for the readiness banner. `null` until the
    // first line arrives.
    val stderrSink = StringBuilder()
    val stderrReady = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val stderrReader = BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8))
    Thread({
        try {
            while (true) {
                val line = stderrReader.readLine() ?: break
                synchronized(stderrSink) {
                    stderrSink.appendLine(line)
                    stderrReady.set(stderrSink.toString())
                }
            }
        } catch (_: Throwable) { /* subprocess exited */ }
    }, "real-cli-stderr-pump").apply { isDaemon = true; start() }

    val stdinWriter = PrintWriter(process.outputStream, false, StandardCharsets.UTF_8)
    return CliSubprocess(process, stdoutLines, stderrSink, stdinWriter, stderrReady)
}
