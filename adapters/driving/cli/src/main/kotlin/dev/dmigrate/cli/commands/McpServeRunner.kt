package dev.dmigrate.cli.commands

import dev.dmigrate.cli.cliVersion
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.prompts.DefaultPromptRegistry
import dev.dmigrate.mcp.registry.AiMcpWiring
import dev.dmigrate.mcp.registry.McpRuntimeRegistries
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.resources.ResourceStores
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerBootstrap
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.server.McpStartOutcome
import dev.dmigrate.mcp.server.validate
import dev.dmigrate.mcp.server.validateForStdio
import dev.dmigrate.server.adapter.storage.file.FileBackedArtifactContentStore
import dev.dmigrate.server.adapter.storage.file.FileBackedUploadSegmentStore
import dev.dmigrate.server.adapter.storage.file.FileSpoolAssembledUploadPayload
import dev.dmigrate.server.adapter.storage.s3.ArtifactStorageConfig
import dev.dmigrate.server.adapter.storage.s3.ArtifactsConfigException
import dev.dmigrate.server.adapter.storage.s3.ArtifactsConfigLoader
import java.net.URI
import java.nio.file.Path
import java.time.Duration

/**
 * Resolved option values for `mcp serve`. Mirrors the Clikt option set
 * of [McpServeCommand] but is decoupled from the framework so the
 * runner is unit-testable.
 */
internal data class McpServeOptions(
    val transport: String = "stdio",
    val bind: String = "127.0.0.1",
    val port: Int = 0,
    val publicBaseUrl: String? = null,
    val authMode: String = "jwt-jwks",
    val issuer: String? = null,
    val jwksUrl: String? = null,
    val introspectionUrl: String? = null,
    val introspectionClientId: String? = null,
    val introspectionClientSecret: String? = null,
    val audience: String? = null,
    val stdioTokenFile: Path? = null,
    val allowOrigin: List<String> = emptyList(),
    val mcpStateDir: Path? = null,
    val mcpStateOrphanRetention: String? = null,
    val cursorKeyringFile: Path? = null,
    val approvalGrantsFile: Path? = null,
    val operationTimeoutSeconds: Long = McpServerConfig.DEFAULT_OPERATION_TIMEOUT.toSeconds(),
)

/** Internal early-exit signal mapped to a CLI exit code by [McpServeRunner.execute]. */
internal class McpServeExit(val code: Int) : RuntimeException()

/**
 * Runtime entry point for `mcp serve`. Holds the option values plus
 * the side-effect bindings (stderr sink, resolved connection-config
 * path, version provider, wiring factory) and orchestrates startup
 * and shutdown.
 *
 * Decoupled from Clikt so the validation and lifecycle helpers can be
 * unit-tested. The wiring construction lives in [McpServeWiring] and
 * is injected here so tests can swap it for a fake. [McpServeCommand]
 * is the framework-side wrapper that parses options and forwards to
 * this runner.
 */
internal class McpServeRunner(
    private val options: McpServeOptions,
    private val stderr: (String) -> Unit,
    private val effectiveConnectionConfigPath: Path?,
    private val cliVersionProvider: () -> String = ::cliVersion,
    private val wiring: McpServeWiring = McpServeWiring(
        effectiveConnectionConfigPath = effectiveConnectionConfigPath,
        approvalGrantsFile = options.approvalGrantsFile,
        stderr = stderr,
    ),
) {

    fun execute(): Int = try {
        doExecute()
        0
    } catch (e: McpServeExit) {
        e.code
    }

    private fun doExecute() {
        val config = buildConfig()
        val errors = when (options.transport) {
            "stdio" -> config.validateForStdio()
            "http" -> config.validate()
            else -> error("transport check failed: ${options.transport}")
        }
        if (errors.isNotEmpty()) {
            stderr("MCP server configuration is invalid:")
            errors.forEach { stderr("  - $it") }
            throw McpServeExit(2)
        }

        val retention = parseRetentionOrExit()
        val artifacts = parseArtifactsConfigOrExit()
        val cursorKeyring = parseCursorKeyringOrExit()
        rejectDevKeyringInProductionOrExit(cursorKeyring)
        val owner = resolveStateDirOrExit()
        try {
            try {
                StateDirValidator.validate(owner.resolved.path)
            } catch (failure: StateDirConfigError) {
                reportStateDirFailure(failure)
            }

            val lock = acquireLockOrExit(owner)
            try {
                runStartupSweepOrExit(owner, retention, artifacts)
                echoStartStateLine(owner, artifacts)
                when (options.transport) {
                    "stdio" -> startStdio(config, owner, lock, cursorKeyring, artifacts)
                    "http" -> startHttp(config, owner, lock, cursorKeyring, artifacts)
                    else -> error("transport check failed: ${options.transport}")
                }
            } finally {
                lock.close()
            }
        } finally {
            owner.cleanupIfOwned()
        }
    }

    fun buildConfig(): McpServerConfig {
        val origins = if (options.allowOrigin.isEmpty()) {
            McpServerConfig.DEFAULT_LOOPBACK_ORIGINS
        } else {
            options.allowOrigin.toSet()
        }
        return McpServerConfig(
            bindAddress = options.bind,
            port = options.port,
            publicBaseUrl = options.publicBaseUrl?.let(URI::create),
            allowedOrigins = origins,
            authMode = when (options.authMode) {
                "disabled" -> AuthMode.DISABLED
                "jwt-jwks" -> AuthMode.JWT_JWKS
                "jwt-introspection" -> AuthMode.JWT_INTROSPECTION
                else -> error("auth-mode check failed: ${options.authMode}")
            },
            issuer = options.issuer?.let(URI::create),
            jwksUrl = options.jwksUrl?.let(URI::create),
            introspectionUrl = options.introspectionUrl?.let(URI::create),
            introspectionClientId = options.introspectionClientId,
            introspectionClientSecret = options.introspectionClientSecret,
            audience = options.audience,
            operationTimeout = Duration.ofSeconds(options.operationTimeoutSeconds),
            stdioTokenFile = options.stdioTokenFile,
        )
    }

    fun parseRetentionOrExit(): RetentionPolicy = try {
        RetentionParser.resolve(cliOption = options.mcpStateOrphanRetention)
    } catch (failure: StateDirConfigError) {
        stderr("MCP server configuration is invalid:")
        stderr("  - ${failure.message}")
        throw McpServeExit(2)
    }

    /**
     * Parses the optional `artifacts` section of the connection-config
     * YAML (ImpPlan-0.9.8-object-storage-s3 S3.4b). Single parse for the
     * whole runner: the result drives the startup-sweep skip, the
     * start-state stderr line and (via [McpServeWiring.build]) the
     * byte-store selection in the runtime wiring.
     */
    fun parseArtifactsConfigOrExit(): ArtifactStorageConfig = try {
        ArtifactsConfigLoader.load(effectiveConnectionConfigPath)
    } catch (failure: ArtifactsConfigException) {
        stderr("MCP server configuration is invalid:")
        stderr("  - ${failure.message}")
        throw McpServeExit(2)
    }

    fun parseCursorKeyringOrExit(): CursorKeyring? {
        val path = options.cursorKeyringFile ?: return null
        return try {
            McpCursorKeyringConfig.load(path)
        } catch (failure: McpCursorKeyringConfigError) {
            stderr("MCP server configuration is invalid:")
            stderr("  - ${failure.message}")
            throw McpServeExit(2)
        }
    }

    /**
     * LF-012 / LN-038 fail-closed: HTTP with a non-disabled auth-mode (the
     * production paths) MUST supply a deterministic `--cursor-keyring-file`.
     * Otherwise the wiring would fall through to `McpRuntimeWiring.DEV_DEFAULT`,
     * a publicly-known secret. Loopback / stdio dev paths keep working.
     */
    fun rejectDevKeyringInProductionOrExit(cursorKeyring: CursorKeyring?) {
        if (cursorKeyring != null) return
        val isProductionAuth = options.transport == "http" && options.authMode != "disabled"
        if (!isProductionAuth) return
        stderr("MCP server configuration is invalid:")
        stderr(
            "  - --cursor-keyring-file is required for production HTTP deployments " +
                "(transport=http with --auth-mode=${options.authMode}). The fallback dev keyring uses a " +
                "publicly-known secret and MUST NOT sign production cursors. " +
                "Generate one via 'd-migrate mcp cursor-key generate' and supply it via " +
                "--cursor-keyring-file <path>.",
        )
        throw McpServeExit(2)
    }

    fun resolveStateDirOrExit(): StateDirOwner {
        val resolved = try {
            StateDirResolver.resolve(cliOption = options.mcpStateDir)
        } catch (failure: StateDirConfigError) {
            stderr("MCP server configuration is invalid:")
            stderr("  - ${failure.message}")
            throw McpServeExit(2)
        }
        return StateDirOwner.of(resolved)
    }

    private fun reportStateDirFailure(failure: StateDirConfigError): Nothing {
        stderr("MCP server configuration is invalid:")
        stderr("  - ${failure.message}")
        throw McpServeExit(2)
    }

    fun acquireLockOrExit(owner: StateDirOwner): McpStateDirLock =
        when (val outcome = McpStateDirLock.tryAcquire(owner.resolved.path, cliVersionProvider())) {
            is McpStateDirLock.AcquireOutcome.Acquired -> outcome.lock
            is McpStateDirLock.AcquireOutcome.Conflict -> {
                stderr("MCP server cannot start:")
                stderr("  - ${outcome.diagnostic}")
                throw McpServeExit(2)
            }
            is McpStateDirLock.AcquireOutcome.Failed -> {
                stderr("MCP server cannot start:")
                stderr("  - ${outcome.message}")
                throw McpServeExit(2)
            }
        }

    fun runStartupSweepOrExit(
        owner: StateDirOwner,
        retention: RetentionPolicy,
        artifacts: ArtifactStorageConfig = ArtifactStorageConfig.File,
    ) {
        if (retention is RetentionPolicy.Never) {
            stderr("MCP startup sweep skipped (retention=never) for state dir ${owner.resolved.path}.")
            return
        }
        val artefactRetention = when (retention) {
            is RetentionPolicy.Immediate -> null
            is RetentionPolicy.After -> retention.duration
            is RetentionPolicy.Never -> error("never branch handled above")
        }
        // S3.4b: with `artifacts.store: s3` the segment/artefact bytes live
        // in S3, not under the local state dir — the file sweeps would walk
        // a directory that is not the byte source. The assembly spool stays
        // local in both modes, so its cleanup always runs.
        val sweepFileByteStores = artifacts is ArtifactStorageConfig.File
        val segmentsRemoved = if (!sweepFileByteStores) 0 else try {
            FileBackedUploadSegmentStore.cleanupOrphans(owner.resolved.path, emptySet())
        } catch (failure: java.io.IOException) {
            stderr(
                "MCP startup sweep: segment cleanup failed for state dir " +
                    "${owner.resolved.path}: ${failure.message}",
            )
            throw McpServeExit(2)
        }
        val artefactsRemoved = if (!sweepFileByteStores) 0 else try {
            FileBackedArtifactContentStore.cleanupOrphans(owner.resolved.path, artefactRetention)
        } catch (failure: java.io.IOException) {
            stderr(
                "MCP startup sweep: artefact cleanup failed for state dir " +
                    "${owner.resolved.path}: ${failure.message}",
            )
            throw McpServeExit(2)
        }
        val spoolsRemoved = try {
            FileSpoolAssembledUploadPayload.cleanupOrphans(owner.resolved.path, artefactRetention)
        } catch (failure: java.io.IOException) {
            stderr(
                "MCP startup sweep: assembly cleanup failed for state dir " +
                    "${owner.resolved.path}: ${failure.message}",
            )
            throw McpServeExit(2)
        }
        stderr(
            if (sweepFileByteStores) {
                "MCP startup sweep (state dir ${owner.resolved.path}): " +
                    "removed $segmentsRemoved upload-segment session(s), " +
                    "$artefactsRemoved artefact file(s), " +
                    "$spoolsRemoved assembly spool(s)."
            } else {
                "MCP startup sweep (state dir ${owner.resolved.path}): " +
                    "removed $spoolsRemoved assembly spool(s); " +
                    "segment/artefact sweeps skipped (artifacts.store=s3)."
            },
        )
    }

    fun echoStartStateLine(
        owner: StateDirOwner,
        artifacts: ArtifactStorageConfig = ArtifactStorageConfig.File,
    ) {
        val tag = if (owner.resolved.owned) "CLI-owned temporary" else "operator-supplied"
        // S3.4b: name endpoint/bucket so operators can verify the byte
        // target — never the credentials (they only exist in env anyway).
        val byteBackend = when (artifacts) {
            is ArtifactStorageConfig.File -> "byte content is file-backed"
            is ArtifactStorageConfig.S3 ->
                "byte content is S3-backed " +
                    "(endpoint=${artifacts.config.endpoint ?: "aws"}, bucket=${artifacts.config.bucket})"
        }
        stderr(
            "MCP state dir: ${owner.resolved.path} [$tag] — " +
                "$byteBackend; LF-012 / LN-011 / LN-017 / LN-027 metadata uses server.state when configured.",
        )
    }

    private fun startStdio(
        config: McpServerConfig,
        owner: StateDirOwner,
        lock: McpStateDirLock,
        cursorKeyring: CursorKeyring?,
        artifacts: ArtifactStorageConfig,
    ) {
        wiring.build(config, owner, cursorKeyring, artifacts).use { runtime ->
            when (val outcome = McpServerBootstrap.startStdio(
                config = config,
                runtimeWiring = runtime.runtimeWiring,
                components = runtime.components,
                resourceStores = runtime.resourceStores,
                promptRegistry = runtime.promptRegistry,
                promptHygieneService = runtime.aiWiring.promptHygieneService,
            )) {
                is McpStartOutcome.ConfigError -> reportConfigErrors(outcome.errors)
                is McpStartOutcome.Started -> {
                    stderr("MCP stdio server started; reading from stdin until EOF/SIGINT.")
                    McpServerLifecycle.run(outcome.handle, lock, owner)
                }
            }
        }
    }

    private fun startHttp(
        config: McpServerConfig,
        owner: StateDirOwner,
        lock: McpStateDirLock,
        cursorKeyring: CursorKeyring?,
        artifacts: ArtifactStorageConfig,
    ) {
        wiring.build(config, owner, cursorKeyring, artifacts).use { runtime ->
            when (val outcome = McpServerBootstrap.startHttp(
                config = config,
                runtimeWiring = runtime.runtimeWiring,
                components = runtime.components,
                resourceStores = runtime.resourceStores,
                promptRegistry = runtime.promptRegistry,
                promptHygieneService = runtime.aiWiring.promptHygieneService,
            )) {
                is McpStartOutcome.ConfigError -> reportConfigErrors(outcome.errors)
                is McpStartOutcome.Started -> {
                    stderr("MCP HTTP server listening on ${options.bind}:${outcome.handle.boundPort}")
                    McpServerLifecycle.run(outcome.handle, lock, owner)
                }
            }
        }
    }

    private fun reportConfigErrors(errors: List<String>): Nothing {
        stderr("MCP server configuration is invalid:")
        errors.forEach { stderr("  - $it") }
        throw McpServeExit(2)
    }
}

internal data class McpCliServerWiring(
    val runtimeWiring: McpRuntimeWiring,
    val aiWiring: AiMcpWiring,
    val components: McpRuntimeRegistries.McpServiceComponents,
    private val closeable: AutoCloseable?,
    val resourceStores: ResourceStores = ResourceStores.fromMcpRuntimeWiring(
        runtimeWiring,
        aiWiring.aiArtifactMetadataStore,
    ),
    val promptRegistry: DefaultPromptRegistry = DefaultPromptRegistry.mandatory(),
    /**
     * LF-012 / LN-011 / LN-017 / LN-027: when the JDBC path produced an async bundle,
     * the lifecycle is held here — `close()` calls `shutdown(timeout)` before
     * `closeable.close()`. In-flight jobs drain cleanly before the DataSource
     * is closed; on timeout the executor lifecycle escalates via interrupt.
     */
    private val executorLifecycle: dev.dmigrate.server.application.job.JobExecutorLifecycle? = null,
    private val executorShutdownTimeout: java.time.Duration =
        dev.dmigrate.server.application.job.JobExecutorConfig.Async.DEFAULT_SHUTDOWN_TIMEOUT,
) : AutoCloseable {
    override fun close() {
        executorLifecycle?.shutdown(executorShutdownTimeout)
        closeable?.close()
    }
}

internal class CloseStack(private val closeables: List<AutoCloseable>) : AutoCloseable {
    override fun close() {
        var first: Throwable? = null
        for (c in closeables) {
            try {
                c.close()
            } catch (t: Throwable) {
                if (first == null) first = t else first.addSuppressed(t)
            }
        }
        first?.let { throw it }
    }
}
