package dev.dmigrate.cli.commands

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * State-dir helpers for `mcp serve` per `ImpPlan-0.9.6-C.md` §6.21.
 *
 * Three concerns live here, intentionally kept off [McpServeCommand]:
 *
 * - [StateDirResolver]: turns the operator-facing inputs (CLI option,
 *   environment variable, fallback temp-dir factory) into a single
 *   [ResolvedStateDir] with a clear `owned` flag.
 * - [StateDirValidator]: fail-fast directory pre-conditions (exists or
 *   creatable, is a directory, is readable + writable). Failures
 *   surface as [StateDirConfigError] which the CLI maps to exit 2.
 * - [StateDirOwner]: idempotent best-effort recursive delete of
 *   CLI-owned temp dirs; operator-supplied dirs are never touched.
 *
 * Locking is handled by [McpStateDirLock] (separate file).
 */

/**
 * Result of resolving the operator's state-dir inputs.
 *
 * @property path the absolute, normalized state-dir path. The byte
 *   adapters create their own canonical sub-dirs (`segments`,
 *   `artifacts`) directly under this root.
 * @property owned `true` if the CLI itself created the directory via
 *   [java.nio.file.Files.createTempDirectory] and therefore also owns
 *   its deletion at shutdown; `false` for operator-supplied dirs that
 *   survive the process.
 */
internal data class ResolvedStateDir(
    val path: Path,
    val owned: Boolean,
)

/**
 * Thrown by [StateDirResolver] / [StateDirValidator] when an operator-
 * facing pre-condition fails. The CLI maps this to a single stderr line
 * + exit 2 before any transport is started.
 */
internal class StateDirConfigError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal object StateDirResolver {
    const val ENV_STATE_DIR: String = "DMIGRATE_MCP_STATE_DIR"
    private const val TEMP_PREFIX: String = "dmigrate-mcp-"

    /**
     * Resolution order per §6.21:
     *  1. [cliOption] — `--mcp-state-dir`
     *  2. environment variable [ENV_STATE_DIR]
     *  3. `Files.createTempDirectory("dmigrate-mcp-")`
     *
     * The CLI option wins over the environment variable. Relative
     * paths are resolved against [workingDir] (default: current process
     * working directory) and then [Path.normalize]'d.
     */
    fun resolve(
        cliOption: Path?,
        env: (String) -> String? = System::getenv,
        workingDir: Path = Path.of("").toAbsolutePath(),
        tempDirFactory: () -> Path = { Files.createTempDirectory(TEMP_PREFIX) },
    ): ResolvedStateDir {
        val operatorSupplied = cliOption ?: env(ENV_STATE_DIR)?.takeUnless { it.isBlank() }?.let(Path::of)
        if (operatorSupplied != null) {
            val absolute = if (operatorSupplied.isAbsolute) operatorSupplied else workingDir.resolve(operatorSupplied)
            return ResolvedStateDir(absolute.normalize(), owned = false)
        }
        val tempDir = try {
            tempDirFactory()
        } catch (failure: IOException) {
            throw StateDirConfigError(
                "could not create temporary state dir for `mcp serve`: ${failure.message}",
                cause = failure,
            )
        }
        return ResolvedStateDir(tempDir.toAbsolutePath().normalize(), owned = true)
    }
}

internal object StateDirValidator {
    /**
     * Fail-fast directory pre-conditions per §6.21:
     * - path exists or can be created
     * - is a directory
     * - is readable and writable
     *
     * Throws [StateDirConfigError] on any violation.
     */
    fun validate(path: Path) {
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path)
            } catch (failure: IOException) {
                throw StateDirConfigError(
                    "state dir $path does not exist and could not be created: ${failure.message}",
                    cause = failure,
                )
            }
        }
        if (!Files.isDirectory(path)) {
            throw StateDirConfigError("state dir $path is not a directory")
        }
        if (!Files.isReadable(path)) {
            throw StateDirConfigError("state dir $path is not readable")
        }
        if (!Files.isWritable(path)) {
            throw StateDirConfigError("state dir $path is not writable")
        }
    }
}

/**
 * Wraps a [ResolvedStateDir] with an idempotent best-effort deletion
 * for the CLI-owned tempdir case. Operator-supplied dirs are never
 * touched.
 */
internal class StateDirOwner private constructor(
    val resolved: ResolvedStateDir,
) {

    @Volatile
    private var cleaned: Boolean = false

    @OptIn(ExperimentalPathApi::class)
    fun cleanupIfOwned() {
        if (!resolved.owned) return
        synchronized(this) {
            if (cleaned) return
            cleaned = true
            try {
                resolved.path.deleteRecursively()
            } catch (_: IOException) {
                // best-effort — the operator can recover the temp dir
                // manually if cleanup fails. Avoid a noisy stack trace
                // on shutdown paths.
            }
        }
    }

    companion object {
        fun of(resolved: ResolvedStateDir): StateDirOwner = StateDirOwner(resolved)
    }
}

/**
 * Best-effort write of `<stateDir>/.lock` payload metadata used by
 * [McpStateDirLock] for diagnostic purposes only. Public callers go
 * through [McpStateDirLock.tryAcquire].
 */
internal object LockPayloadIo {
    /**
     * Hand-rolled JSON keeps the helper free of jackson/kotlinx-
     * serialization deps. PID/UUID/Instant are constrained shapes;
     * `version` comes from `cliVersion()` which can in principle pick
     * up a git-describe string with `"`/`\` characters, so all string
     * fields are escaped defensively to keep the lockfile parseable
     * by external diagnostic tooling.
     */
    fun render(pid: Long, startedAt: String, instance: String, version: String): String =
        """{"pid":$pid,"startedAt":"${escapeJsonString(startedAt)}",""" +
            """"instance":"${escapeJsonString(instance)}",""" +
            """"version":"${escapeJsonString(version)}"}"""

    /**
     * Writes [payload] to [path] in-place via `CREATE | TRUNCATE_EXISTING |
     * WRITE`. Not atomic — a crash mid-write leaves a partial file. That
     * is acceptable here because the lockfile payload is purely
     * diagnostic: the OS-level [java.nio.channels.FileLock] (held on the
     * same path by [McpStateDirLock]) is what gates concurrent starts,
     * and [readBestEffort] tolerates malformed bytes by returning the
     * raw content for the operator-facing diagnostic.
     */
    fun write(path: Path, payload: String) {
        Files.write(
            path,
            payload.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    fun readBestEffort(path: Path): String? = try {
        Files.readString(path, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }

    private fun escapeJsonString(value: String): String {
        val out = StringBuilder(value.length + 4)
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append("\\u").append(String.format(Locale.ROOT, "%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        return out.toString()
    }
}
