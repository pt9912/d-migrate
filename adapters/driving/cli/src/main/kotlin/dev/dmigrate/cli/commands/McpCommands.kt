package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.cliVersion
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerBootstrap
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.server.McpStartOutcome
import dev.dmigrate.mcp.server.validate
import dev.dmigrate.mcp.server.validateForStdio
import dev.dmigrate.server.adapter.storage.file.FileBackedArtifactContentStore
import dev.dmigrate.server.adapter.storage.file.FileBackedUploadSegmentStore
import dev.dmigrate.server.adapter.storage.file.FileSpoolAssembledUploadPayload
import java.net.URI

/**
 * MCP-server entry point per `ImpPlan-0.9.6-B.md` §6.11 +
 * `ImpPlan-0.9.6-C.md` §6.14/§6.20/§6.21. The `serve` subcommand
 * activates the full Phase-C dispatch chain: every tool from the
 * Phase-C plan (`schema_validate`, `schema_generate`, `schema_compare`,
 * `artifact_upload*`, `artifact_chunk_get`, `job_status_get`,
 * plus `capabilities_list`) routes to its real handler, and every
 * `tools/call` records one structured audit event.
 *
 * §6.21: byte content (upload segments, artefact bodies) is file-backed
 * under the resolved state dir (`--mcp-state-dir`). Metadata stores
 * (sessions, artefacts, schemas, jobs, quotas) remain in-process for
 * now — durable metadata adapters land post-0.9.6.
 */
class McpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context) = "MCP-server commands (Phase C: stdio + Streamable HTTP)"

    init {
        subcommands(McpServeCommand(), McpCursorKeyCommand())
    }

    override fun run() = Unit
}

/**
 * Starts the MCP server in stdio or Streamable-HTTP mode. Wraps
 * [McpServerBootstrap]. Validation per §12.12 happens inside the
 * bootstrap; configuration errors come back as exit code 2 with one
 * line per violation.
 *
 * stdio: blocks until stdin closes (or SIGINT).
 * HTTP: blocks until SIGINT.
 *
 * §6.21 lifecycle: state dir is resolved + validated + locked before
 * any transport starts; CLI-owned tempdirs are deleted best-effort on
 * normal stop, SIGINT, and the start-error path.
 */
class McpServeCommand : CliktCommand(name = "serve") {
    override fun help(context: Context) =
        "Start the MCP server with the Phase-C dispatch chain. " +
            "Byte content is file-backed under --mcp-state-dir " +
            "(metadata is ephemeral — survives only the process)."

    private val transport by option(
        "--transport",
        help = "Transport: stdio (one process per client) or http (Streamable HTTP).",
    ).choice("stdio", "http").default("stdio")

    private val bind by option(
        "--bind",
        help = "HTTP bind address (default 127.0.0.1). Non-loopback requires --auth-mode != disabled.",
    ).default("127.0.0.1")

    private val port by option(
        "--port",
        help = "HTTP port (0 picks an ephemeral port).",
    ).int().default(0)

    private val publicBaseUrl by option(
        "--public-base-url",
        help = "Public base URL for HTTP. MUST be https. Required for non-loopback prod deployments.",
    )

    private val authMode by option(
        "--auth-mode",
        help = "HTTP auth mode (stdio ignores this). disabled is loopback-only.",
    ).choice("disabled", "jwt-jwks", "jwt-introspection").default("jwt-jwks")

    private val issuer by option(
        "--issuer",
        help = "OIDC issuer URI (required for jwt-jwks and jwt-introspection).",
    )

    private val jwksUrl by option(
        "--jwks-url",
        help = "JWKS URL (required for jwt-jwks).",
    )

    private val introspectionUrl by option(
        "--introspection-url",
        help = "RFC 7662 introspection endpoint (required for jwt-introspection).",
    )

    private val audience by option(
        "--audience",
        help = "Expected `aud` claim / OAuth Resource Indicator (required for jwt-* modes).",
    )

    private val stdioTokenFile by option(
        "--stdio-token-file",
        help = "JSON or YAML token-registry for the stdio transport (§12.10).",
    ).path()

    private val allowOrigin by option(
        "--allow-origin",
        help = "Origin allow-list entry (repeatable). Default loopback origins apply when the bind is loopback.",
    ).multiple()

    private val mcpStateDir by option(
        "--mcp-state-dir",
        help = "State dir for file-backed upload segments and artefact content. " +
            "Wins over \$DMIGRATE_MCP_STATE_DIR. Falls back to a CLI-owned tempdir " +
            "(deleted on stop). Operator-supplied dirs are single-writer (advisory " +
            ".lock) and survive the process; metadata stays in-process either way.",
    ).path()

    private val mcpStateOrphanRetention by option(
        "--mcp-state-orphan-retention",
        help = "Retention for orphaned byte files at startup. Wins over " +
            "\$DMIGRATE_MCP_STATE_ORPHAN_RETENTION; default 24h. Accepts " +
            "`never` (skip sweep — forensic mode), `0`/`0s` (delete every " +
            "store file at boot), <number><ms|s|m|h|d>, or ISO-8601 PT… . " +
            "Upload segments without surviving session metadata are always " +
            "swept under any non-`never` policy because they are " +
            "unreferenceable after restart.",
    )

    private val connectionConfigPath by option(
        "--connection-config",
        help = "Project/server YAML for Phase-D secret-free connection references. " +
            "Defaults to the root --config path when set.",
    ).path()

    private val cursorKeyringFile by option(
        "--cursor-keyring-file",
        help = "YAML keyring for HMAC-sealed MCP cursors. Required for deterministic multi-instance deployments.",
    ).path()

    override fun run() {
        val config = buildConfig()
        // §12.15: stdio ignores authMode entirely. Use the slimmer
        // validation so a default-config (authMode=JWT_JWKS, no
        // issuer) still starts the stdio server. Both startStdio and
        // startHttp re-validate at the bootstrap layer with the
        // matching helper, but the CLI surface produces clearer
        // error messages when it catches violations early.
        val errors = when (transport) {
            "stdio" -> config.validateForStdio()
            "http" -> config.validate()
            else -> error("transport check failed: $transport")
        }
        if (errors.isNotEmpty()) {
            echo("MCP server configuration is invalid:", err = true)
            errors.forEach { echo("  - $it", err = true) }
            throw ProgramResult(2)
        }

        val retention = parseRetentionOrExit()
        val cursorKeyring = parseCursorKeyringOrExit()
        val owner = resolveStateDirOrExit()
        try {
            try {
                StateDirValidator.validate(owner.resolved.path)
            } catch (failure: StateDirConfigError) {
                reportStateDirFailure(failure)
            }

            val lock = acquireLockOrExit(owner)
            // Both lock.close() and owner.cleanupIfOwned() are idempotent
            // (AtomicBoolean-guarded), so the outer try/finally below
            // can safely double-call them when McpServerLifecycle has
            // already cleaned up via its own shutdown-hook path.
            try {
                runStartupSweepOrExit(owner, retention)
                echoStartStateLine(owner)
                when (transport) {
                    "stdio" -> startStdio(config, owner, lock, cursorKeyring)
                    "http" -> startHttp(config, owner, lock, cursorKeyring)
                    else -> error("transport check failed: $transport")
                }
            } finally {
                lock.close()
            }
        } finally {
            owner.cleanupIfOwned()
        }
    }

    private fun parseRetentionOrExit(): RetentionPolicy {
        return try {
            RetentionParser.resolve(cliOption = mcpStateOrphanRetention)
        } catch (failure: StateDirConfigError) {
            echo("MCP server configuration is invalid:", err = true)
            echo("  - ${failure.message}", err = true)
            throw ProgramResult(2)
        }
    }

    private fun runStartupSweepOrExit(owner: StateDirOwner, retention: RetentionPolicy) {
        if (retention is RetentionPolicy.Never) {
            echo(
                "MCP startup sweep skipped (retention=never) for state dir ${owner.resolved.path}.",
                err = true,
            )
            return
        }
        val artefactRetention = when (retention) {
            is RetentionPolicy.Immediate -> null
            is RetentionPolicy.After -> retention.duration
            is RetentionPolicy.Never -> error("never branch handled above")
        }
        // §6.21 Z. 1156–1163: a sweep failure is a startup-error path,
        // because unbounded disk growth is exactly what the sweep is
        // there to prevent. The outer try/finally still cleans the
        // tempdir (CLI-owned) and releases the lock before exit.
        val segmentsRemoved = try {
            FileBackedUploadSegmentStore.cleanupOrphans(owner.resolved.path, emptySet())
        } catch (failure: java.io.IOException) {
            echo("MCP startup sweep: segment cleanup failed for state dir " +
                "${owner.resolved.path}: ${failure.message}", err = true)
            throw ProgramResult(2)
        }
        val artefactsRemoved = try {
            FileBackedArtifactContentStore.cleanupOrphans(owner.resolved.path, artefactRetention)
        } catch (failure: java.io.IOException) {
            echo("MCP startup sweep: artefact cleanup failed for state dir " +
                "${owner.resolved.path}: ${failure.message}", err = true)
            throw ProgramResult(2)
        }
        // AP 6.22: assembly spools left behind by a crashed
        // streaming-finalisation are bounded by the same orphan
        // retention. Layout-aware sweep over <stateDir>/assembly/...
        val spoolsRemoved = try {
            FileSpoolAssembledUploadPayload.cleanupOrphans(owner.resolved.path, artefactRetention)
        } catch (failure: java.io.IOException) {
            echo("MCP startup sweep: assembly cleanup failed for state dir " +
                "${owner.resolved.path}: ${failure.message}", err = true)
            throw ProgramResult(2)
        }
        echo(
            "MCP startup sweep (state dir ${owner.resolved.path}): " +
                "removed $segmentsRemoved upload-segment session(s), " +
                "$artefactsRemoved artefact file(s), " +
                "$spoolsRemoved assembly spool(s).",
            err = true,
        )
    }

    private fun buildConfig(): McpServerConfig {
        val origins = if (allowOrigin.isEmpty()) {
            McpServerConfig.DEFAULT_LOOPBACK_ORIGINS
        } else {
            allowOrigin.toSet()
        }
        return McpServerConfig(
            bindAddress = bind,
            port = port,
            publicBaseUrl = publicBaseUrl?.let(URI::create),
            allowedOrigins = origins,
            authMode = when (authMode) {
                "disabled" -> AuthMode.DISABLED
                "jwt-jwks" -> AuthMode.JWT_JWKS
                "jwt-introspection" -> AuthMode.JWT_INTROSPECTION
                else -> error("auth-mode check failed: $authMode")
            },
            issuer = issuer?.let(URI::create),
            jwksUrl = jwksUrl?.let(URI::create),
            introspectionUrl = introspectionUrl?.let(URI::create),
            audience = audience,
            stdioTokenFile = stdioTokenFile,
        )
    }

    private fun resolveStateDirOrExit(): StateDirOwner {
        val resolved = try {
            StateDirResolver.resolve(cliOption = mcpStateDir)
        } catch (failure: StateDirConfigError) {
            echo("MCP server configuration is invalid:", err = true)
            echo("  - ${failure.message}", err = true)
            throw ProgramResult(2)
        }
        return StateDirOwner.of(resolved)
    }

    private fun reportStateDirFailure(failure: StateDirConfigError): Nothing {
        echo("MCP server configuration is invalid:", err = true)
        echo("  - ${failure.message}", err = true)
        throw ProgramResult(2)
    }

    private fun acquireLockOrExit(owner: StateDirOwner): McpStateDirLock {
        return when (val outcome = McpStateDirLock.tryAcquire(owner.resolved.path, cliVersion())) {
            is McpStateDirLock.AcquireOutcome.Acquired -> outcome.lock
            is McpStateDirLock.AcquireOutcome.Conflict -> {
                echo("MCP server cannot start:", err = true)
                echo("  - ${outcome.diagnostic}", err = true)
                throw ProgramResult(2)
            }
            is McpStateDirLock.AcquireOutcome.Failed -> {
                echo("MCP server cannot start:", err = true)
                echo("  - ${outcome.message}", err = true)
                throw ProgramResult(2)
            }
        }
    }

    private fun echoStartStateLine(owner: StateDirOwner) {
        val tag = if (owner.resolved.owned) "CLI-owned temporary" else "operator-supplied"
        echo(
            "MCP state dir: ${owner.resolved.path} [$tag] — " +
                "metadata is ephemeral; only byte content is file-backed.",
            err = true,
        )
    }

    private fun effectiveConnectionConfigPath() =
        connectionConfigPath ?: (currentContext.parent?.parent?.command as? DMigrate)?.config

    private fun parseCursorKeyringOrExit(): CursorKeyring? {
        val path = cursorKeyringFile ?: return null
        return try {
            McpCursorKeyringConfig.load(path)
        } catch (failure: McpCursorKeyringConfigError) {
            echo("MCP server configuration is invalid:", err = true)
            echo("  - ${failure.message}", err = true)
            throw ProgramResult(2)
        }
    }

    private fun startStdio(
        config: McpServerConfig,
        owner: StateDirOwner,
        lock: McpStateDirLock,
        cursorKeyring: CursorKeyring?,
    ) {
        // AP 6.14 / 6.20 / 6.21: hand the bootstrap the file-backed
        // CLI wiring so every tools/call dispatches to its real
        // handler with byte content on disk under the locked state dir.
        val wiring = McpCliPhaseCWiring.phaseCWiring(
            stateDir = owner.resolved.path,
            connectionConfigPath = effectiveConnectionConfigPath(),
            cursorKeyring = cursorKeyring,
        )
        when (val outcome = McpServerBootstrap.startStdio(config = config, phaseCWiring = wiring)) {
            is McpStartOutcome.ConfigError -> reportConfigErrors(outcome.errors)
            is McpStartOutcome.Started -> {
                echo("MCP stdio server started; reading from stdin until EOF/SIGINT.", err = true)
                // §12.4: stdio terminates on EOF or IOException; the
                // shutdown hook covers SIGINT. McpServerLifecycle wires
                // both paths into a single idempotent cleanup so the
                // tempdir is removed even when SIGINT kills the JVM
                // before awaitTermination unblocks.
                McpServerLifecycle.run(outcome.handle, lock, owner)
            }
        }
    }

    private fun startHttp(
        config: McpServerConfig,
        owner: StateDirOwner,
        lock: McpStateDirLock,
        cursorKeyring: CursorKeyring?,
    ) {
        // AP 6.14 / 6.20 / 6.21: same file-backed Phase-C wiring for
        // the HTTP transport so both routes share dispatch shape and
        // on-disk layout.
        val wiring = McpCliPhaseCWiring.phaseCWiring(
            stateDir = owner.resolved.path,
            connectionConfigPath = effectiveConnectionConfigPath(),
            cursorKeyring = cursorKeyring,
        )
        when (val outcome = McpServerBootstrap.startHttp(config = config, phaseCWiring = wiring)) {
            is McpStartOutcome.ConfigError -> reportConfigErrors(outcome.errors)
            is McpStartOutcome.Started -> {
                echo("MCP HTTP server listening on $bind:${outcome.handle.boundPort}", err = true)
                // HTTP's awaitTermination defaults to Thread.sleep
                // which never wakes from KtorHandle.stop(); the
                // lifecycle wrap puts cleanup into the shutdown hook
                // itself so SIGINT actually removes CLI-owned tempdirs.
                McpServerLifecycle.run(outcome.handle, lock, owner)
            }
        }
    }

    private fun reportConfigErrors(errors: List<String>): Nothing {
        echo("MCP server configuration is invalid:", err = true)
        errors.forEach { echo("  - $it", err = true) }
        throw ProgramResult(2)
    }
}

class McpCursorKeyCommand : CliktCommand(name = "cursor-key") {
    override fun help(context: Context) = "Generate and validate MCP cursor keyring files"

    init {
        subcommands(McpCursorKeyGenerateCommand(), McpCursorKeyValidateCommand())
    }

    override fun run() = Unit
}

class McpCursorKeyGenerateCommand : CliktCommand(name = "generate") {
    override fun help(context: Context) = "Generate a YAML cursor keyring with one active signing key"

    private val kid by option(
        "--kid",
        help = "Stable key id to place into future cursor envelopes.",
    ).required()

    override fun run() {
        echo(McpCursorKeyringConfig.renderSingleKeyFile(kid))
    }
}

class McpCursorKeyValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a cursor keyring YAML file"

    private val keyringFile by option(
        "--cursor-keyring-file",
        help = "YAML keyring to validate.",
    ).path().required()

    override fun run() {
        try {
            McpCursorKeyringConfig.load(keyringFile)
            echo("cursor keyring valid: $keyringFile")
        } catch (failure: McpCursorKeyringConfigError) {
            echo("cursor keyring invalid: ${failure.message}", err = true)
            throw ProgramResult(2)
        }
    }
}
