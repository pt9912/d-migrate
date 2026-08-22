package dev.dmigrate.cli.integration

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Geteiltes Plumbing fuer Specs, die die ECHTE CLI als Kind-JVM starten
 * (`java -cp <test runtime classpath> dev.dmigrate.cli.MainKt mcp serve
 * --transport stdio ...`) und per NDJSON-JSON-RPC mit ihr sprechen.
 * Konsumenten: `McpRealCliSubprocessTest` (Lifecycle-Smoke) und
 * `McpS3SubprocessE2ETest` (S3.4c — artifacts.store=s3 gegen SeaweedFS).
 */
internal class CliSubprocess(
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

    fun requestResponse(line: String, timeoutMs: Long = RESPONSE_TIMEOUT_MS): String {
        send(line)
        return stdoutLines.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: error("real CLI subprocess: no response within ${timeoutMs}ms; stderr=$stderrSink")
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

    companion object {
        const val RESPONSE_TIMEOUT_MS: Long = 10_000
        private const val POLL_MS: Long = 50
        private const val KILL_TIMEOUT_MS: Long = 5_000
    }
}

/**
 * Launch-Praefix fuer die ECHTE CLI als Kind-Prozess. `DMIGRATE_CLI_BIN` schaltet
 * auf ein GraalVM-Native-Binary um, statt die CLI als Kind-JVM zu starten. Zweck:
 * die vorhandenen Subprozess-E2Es — darunter der volle S3-Round-Trip gegen
 * SeaweedFS — gegen das native Artefakt fahren, statt eine zweite, schwaechere
 * Sondensuite dafuer zu bauen. Ohne die Variable: `java -cp <test runtime
 * classpath> dev.dmigrate.cli.MainKt`. Das Binary versteht dieselbe
 * Kommandozeile wie MainKt, der Rest des Aufrufs ist identisch.
 */
internal fun cliLaunchPrefix(): List<String> {
    val nativeBin = System.getenv("DMIGRATE_CLI_BIN")?.takeIf { it.isNotBlank() }
    if (nativeBin != null) return listOf(nativeBin)
    val javaBin = ProcessHandle.current().info().command().orElse("java")
    val classpath = System.getProperty("java.class.path")
        ?: error("test JVM has no java.class.path system property")
    return listOf(javaBin, "-cp", classpath, "dev.dmigrate.cli.MainKt")
}

/**
 * Ergebnis eines einmaligen CLI-Laufs (kein Langlaeufer wie `mcp serve`):
 * Exit-Code plus vollstaendig eingesammeltes stdout/stderr.
 */
internal data class CliRunResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Startet die ECHTE CLI als Kind-Prozess mit [args] (z. B. `schema generate
 * --source …`), wartet auf ihr Ende und liefert Exit-Code + Ausgabe. Gleicher
 * Launch-Pfad wie [startRealCliSubprocess] ([cliLaunchPrefix]); stdin ist
 * geschlossen. Konsumenten: `MssqlCommandGateE2ETest`,
 * `MssqlSchemaReverseE2ETest` (MSSQL Slice 1a).
 */
internal fun runRealCli(
    args: List<String>,
    env: Map<String, String> = emptyMap(),
    timeoutMs: Long = CLI_RUN_TIMEOUT_MS,
): CliRunResult {
    val builder = ProcessBuilder(cliLaunchPrefix() + args).redirectErrorStream(false)
    builder.environment().putAll(env)
    val process = builder.start()
    process.outputStream.close()

    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val stdoutPump = pumpToBuffer(process.inputStream, stdout, "real-cli-run-stdout")
    val stderrPump = pumpToBuffer(process.errorStream, stderr, "real-cli-run-stderr")

    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) {
        process.destroyForcibly()
        error("real CLI subprocess did not finish within ${timeoutMs}ms (args=$args); stderr=$stderr")
    }
    stdoutPump.join(PUMP_JOIN_MS)
    stderrPump.join(PUMP_JOIN_MS)
    return CliRunResult(process.exitValue(), stdout.toString(), stderr.toString())
}

private fun pumpToBuffer(stream: java.io.InputStream, sink: StringBuilder, name: String): Thread =
    Thread({
        try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line -> synchronized(sink) { sink.appendLine(line) } }
            }
        } catch (_: Throwable) { /* subprocess exited */ }
    }, name).apply { isDaemon = true; start() }

private const val CLI_RUN_TIMEOUT_MS: Long = 120_000
private const val PUMP_JOIN_MS: Long = 5_000

/**
 * @param extraArgs zusaetzliche `mcp serve`-Argumente (z. B.
 *  `--connection-config`, `--stdio-token-file`).
 * @param env zusaetzliche Umgebungsvariablen fuer die Kind-JVM (z. B.
 *  `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, `DMIGRATE_MCP_STDIO_TOKEN`).
 */
internal fun startRealCliSubprocess(
    stateDir: String,
    extraArgs: List<String> = emptyList(),
    env: Map<String, String> = emptyMap(),
): CliSubprocess {
    val builder = ProcessBuilder(
        cliLaunchPrefix() + listOf(
            "mcp", "serve",
            "--transport", "stdio",
            "--mcp-state-dir", stateDir,
        ) + extraArgs,
    ).redirectErrorStream(false)
    builder.environment().putAll(env)
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
