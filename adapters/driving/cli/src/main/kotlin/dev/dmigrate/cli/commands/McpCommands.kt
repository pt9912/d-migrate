package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.cliVersion
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.registry.FileBackedApprovalGrantStore
import dev.dmigrate.mcp.prompts.DefaultPromptRegistry
import dev.dmigrate.mcp.registry.McpRuntimeRegistries
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.registry.OperationalMcpWiring
import dev.dmigrate.mcp.registry.AiMcpRegistries
import dev.dmigrate.mcp.registry.AiMcpWiring
import dev.dmigrate.mcp.registry.McpCoreJobWorkerFactory
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
import dev.dmigrate.server.application.artifact.ArtifactRetentionService
import dev.dmigrate.server.application.upload.UploadSessionService
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.job.JdbcJobStartTransaction
import dev.dmigrate.server.persistence.jdbc.job.JdbcJobStore
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.persistence.jdbc.quota.JdbcOwnerAwareQuotaService
import dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaReservationOwnerStore
import dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaStore
import dev.dmigrate.server.application.approval.ApprovalTokenFingerprint
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MCP-server entry point per LF-012 / LN-027 / LN-028 / LN-038
 * LF-012 / LN-027 / LN-028 / LN-038. The `serve` subcommand
 * activates the full LF-012 / LN-038 dispatch chain: every tool from the
 * LF-012 / LN-038 plan (`schema_validate`, `schema_generate`, `schema_compare`,
 * `artifact_upload*`, `artifact_chunk_get`, `job_status_get`,
 * plus `capabilities_list`) routes to its real handler, and every
 * `tools/call` records one structured audit event.
 *
 * §6.21: byte content (upload segments, artefact bodies) is file-backed
 * under the resolved state dir (`--mcp-state-dir`). LF-012 / LN-011 / LN-017 / LN-027 can also
 * persist the LF-012 / LN-011 / LN-017 / LN-027 server-state stores when `server.state.jdbcUrl`
 * or `D_MIGRATE_SERVER_STATE_JDBC_URL` is configured.
 */
class McpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context) = "MCP-server commands (LF-012 / LN-038: stdio + Streamable HTTP)"

    init {
        subcommands(McpServeCommand(), McpCursorKeyCommand(), McpApprovalGrantCommand())
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
        "Start the MCP server with the LF-012 / LN-038 dispatch chain. " +
            "Byte content is file-backed under --mcp-state-dir " +
            "(LF-012 / LN-011 / LN-017 / LN-027 server-state can be JDBC-backed via server.state)."

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
        help = "Project/server YAML for LF-012 / LN-038 secret-free connection references. " +
            "Defaults to the root --config path when set.",
    ).path()

    private val cursorKeyringFile by option(
        "--cursor-keyring-file",
        help = "YAML keyring for HMAC-sealed MCP cursors. Required for deterministic multi-instance deployments.",
    ).path()

    private val approvalGrantsFile by option(
        "--approval-grants-file",
        help = "JSON/YAML ApprovalGrant store. Use with 'd-migrate mcp approval-grant issue' to approve pending jobs.",
    ).path()

    private val operationTimeoutSeconds by option(
        "--operation-timeout-seconds",
        help = "Timeout in seconds for upload finalisation leases and the stale-finalisation sweeper.",
    ).long().default(McpServerConfig.DEFAULT_OPERATION_TIMEOUT.toSeconds())

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
        // LF-012 / LN-038 review: a deployment that runs HTTP with
        // any non-disabled auth-mode (i.e. jwt-jwks / jwt-
        // introspection — the production paths) MUST NOT silently
        // fall through to McpRuntimeWiring's `DEV_DEFAULT` keyring.
        // The DEV_DEFAULT secret is publicly known (`0x00..0x1F`);
        // signing production cursors with it is a security flaw.
        // stdio + HTTP-disabled (loopback dev) keep working with
        // the DEV_DEFAULT — those are explicit single-instance dev
        // surfaces.
        rejectDevKeyringInProductionOrExit(cursorKeyring)
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
        // LF-010 / LF-013 / LN-009 / LN-011: assembly spools left behind by a crashed
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
            operationTimeout = Duration.ofSeconds(operationTimeoutSeconds),
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
                "byte content is file-backed; LF-012 / LN-011 / LN-017 / LN-027 metadata uses server.state when configured.",
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

    /**
     * LF-012 / LN-038 fail-closed: when transport is HTTP and
     * `--auth-mode` is one of the production modes, the
     * deployment MUST supply a deterministic
     * `--cursor-keyring-file`. Otherwise the wiring would fall
     * through to `McpRuntimeWiring.DEV_DEFAULT` — a publicly-known
     * secret. Loopback / stdio dev paths keep working: those
     * surfaces are explicit single-instance dev contracts.
     */
    private fun rejectDevKeyringInProductionOrExit(cursorKeyring: CursorKeyring?) {
        if (cursorKeyring != null) return
        val isProductionAuth = transport == "http" && authMode != "disabled"
        if (!isProductionAuth) return
        echo("MCP server configuration is invalid:", err = true)
        echo(
            "  - --cursor-keyring-file is required for production HTTP deployments " +
                "(transport=http with --auth-mode=$authMode). The fallback dev keyring uses a " +
                "publicly-known secret and MUST NOT sign production cursors. " +
                "Generate one via 'd-migrate mcp cursor-key generate' and supply it via " +
                "--cursor-keyring-file <path>.",
            err = true,
        )
        throw ProgramResult(2)
    }

    private fun startStdio(
        config: McpServerConfig,
        owner: StateDirOwner,
        lock: McpStateDirLock,
        cursorKeyring: CursorKeyring?,
    ) {
        buildRuntimeWiringOrExit(config, owner, cursorKeyring).use { runtime ->
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
    }

    private fun startHttp(
        config: McpServerConfig,
        owner: StateDirOwner,
        lock: McpStateDirLock,
        cursorKeyring: CursorKeyring?,
    ) {
        buildRuntimeWiringOrExit(config, owner, cursorKeyring).use { runtime ->
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
                    echo("MCP HTTP server listening on $bind:${outcome.handle.boundPort}", err = true)
                    // HTTP's awaitTermination defaults to Thread.sleep
                    // which never wakes from KtorHandle.stop(); the
                    // lifecycle wrap puts cleanup into the shutdown hook
                    // itself so SIGINT actually removes CLI-owned tempdirs.
                    McpServerLifecycle.run(outcome.handle, lock, owner)
                }
            }
        }
    }

    private fun buildRuntimeWiringOrExit(
        config: McpServerConfig,
        owner: StateDirOwner,
        cursorKeyring: CursorKeyring?,
    ): McpCliServerWiring {
        // LF-012 / LN-027 / LN-028 / LN-038: the base wiring keeps byte content
        // file-backed under the locked state dir.
        val phaseC = McpCliRuntimeWiring.runtimeWiring(
            stateDir = owner.resolved.path,
            connectionConfigPath = effectiveConnectionConfigPath(),
            cursorKeyring = cursorKeyring,
            operationTimeout = config.operationTimeout,
        )
        val state = resolveServerStateConfigOrExit() ?: run {
            val artifactRetention = startArtifactRetentionLoop(phaseC)
            val finalisationTimeout = startFinalisationTimeoutLoop(phaseC)
            val idempotencyStore = InMemoryIdempotencyStore()
            val connectionSecretResolver = dev.dmigrate.connection.EnvConnectionSecretResolver()
            val phaseE = OperationalMcpWiring(
                runtimeWiring = phaseC,
                idempotencyStore = idempotencyStore,
                jobStartTransaction = InMemoryJobStartTransaction(phaseC.jobStore, idempotencyStore),
                workerHandleRegistry = InMemoryWorkerHandleRegistry(),
                approvalGrantStore = approvalGrantStore(),
                fallbackJobWorkerFactory = mcpCoreJobWorkerFactory(phaseC, connectionSecretResolver),
                connectionSecretResolver = connectionSecretResolver,
                dataRunnerTempDirectory = owner.resolved.path,
            )
            val phaseG = AiMcpWiring(operationalWiring = phaseE)
            return McpCliServerWiring(
                runtimeWiring = phaseC,
                aiWiring = phaseG,
                components = AiMcpRegistries.defaultComponents(phaseG, config.scopeMapping),
                closeable = CloseStack(listOf(artifactRetention, finalisationTimeout)),
            )
        }

        val dataSource = createServerStateDataSource(state)
        var artifactRetention: AutoCloseable? = null
        var finalisationTimeout: AutoCloseable? = null
        try {
            applyOrValidateMigrations(dataSource, state)
            val runner = JdbcTransactionRunner(dataSource)
            val jobStore = JdbcJobStore(runner)
            val quotaStore = JdbcQuotaStore(runner, phaseC.clock)
            val ownerStore = JdbcQuotaReservationOwnerStore(runner)
            val idempotencyStore = JdbcIdempotencyStore(runner)
            val quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE }
            val phaseCWithJdbc = phaseC.copy(
                jobStore = jobStore,
                quotaService = quotaService,
            )
            artifactRetention = startArtifactRetentionLoop(phaseCWithJdbc)
            finalisationTimeout = startFinalisationTimeoutLoop(phaseCWithJdbc)
            // LF-012 / LN-011 / LN-017 / LN-027: server.jobs.executor + Env-Overrides aufloesen,
            // Bundle bauen. Default ist Sync — Bestands-MVP.
            val executor = McpJobExecutorConfigResolver(effectiveConnectionConfigPath()).resolve()
            val executorBundle = dev.dmigrate.server.application.job.JobExecutorFactory.create(executor.config)
            val connectionSecretResolver = dev.dmigrate.connection.EnvConnectionSecretResolver()
            val phaseE = OperationalMcpWiring(
                runtimeWiring = phaseCWithJdbc,
                idempotencyStore = idempotencyStore,
                jobStartTransaction = JdbcJobStartTransaction(runner, idempotencyStore, jobStore),
                workerHandleRegistry = InMemoryWorkerHandleRegistry(),
                approvalGrantStore = approvalGrantStore(),
                quotaReservationOwnerStore = ownerStore,
                ownerAwareQuotaService = JdbcOwnerAwareQuotaService(
                    transactionRunner = runner,
                    jdbcQuotaStore = quotaStore,
                    jdbcOwnerStore = ownerStore,
                    limitFor = { Long.MAX_VALUE },
                ),
                executorBundle = executorBundle,
                fallbackJobWorkerFactory = mcpCoreJobWorkerFactory(phaseCWithJdbc, connectionSecretResolver),
                connectionSecretResolver = connectionSecretResolver,
                dataRunnerTempDirectory = owner.resolved.path,
            )
            val phaseG = AiMcpWiring(operationalWiring = phaseE)
            val components = AiMcpRegistries.defaultComponents(phaseG, config.scopeMapping)
            echo(
                "MCP server-state: JDBC/Postgres enabled " +
                    "(migrations.auto=${state.migrationsAuto}, " +
                    "executor=${if (executor.isAsync) "async" else "sync"}).",
                err = true,
            )
            val asyncCfg = executor.config as? dev.dmigrate.server.application.job.JobExecutorConfig.Async
            return McpCliServerWiring(
                runtimeWiring = phaseCWithJdbc,
                aiWiring = phaseG,
                components = components,
                closeable = CloseStack(listOfNotNull(artifactRetention, finalisationTimeout, dataSource)),
                executorLifecycle = if (executor.isAsync) executorBundle.lifecycle else null,
                executorShutdownTimeout = asyncCfg?.shutdownTimeout
                    ?: dev.dmigrate.server.application.job.JobExecutorConfig.Async.DEFAULT_SHUTDOWN_TIMEOUT,
            )
        } catch (failure: Throwable) {
            try {
                artifactRetention?.close()
            } finally {
                try {
                    finalisationTimeout?.close()
                } finally {
                    dataSource.close()
                }
            }
            throw failure
        }
    }

    private fun approvalGrantStore() =
        approvalGrantsFile?.let(::FileBackedApprovalGrantStore) ?: InMemoryApprovalGrantStore()

    private fun mcpCoreJobWorkerFactory(
        phaseC: McpRuntimeWiring,
        connectionSecretResolver: dev.dmigrate.server.ports.ConnectionSecretResolver,
    ) = McpCoreJobWorkerFactory(
        connectionStore = phaseC.connectionStore,
        connectionSecretResolver = connectionSecretResolver,
        artifactStore = phaseC.artifactStore,
        artifactContentStore = phaseC.artifactContentStore,
        schemaStore = phaseC.schemaStore,
        profileStore = phaseC.profileStore,
        diffStore = phaseC.diffStore,
        limits = phaseC.limits,
        clock = phaseC.clock,
    )

    private fun startArtifactRetentionLoop(phaseC: McpRuntimeWiring): AutoCloseable {
        val service = ArtifactRetentionService(
            artifactStore = phaseC.artifactStore,
            contentStore = phaseC.artifactContentStore,
            quotaService = phaseC.quotaService,
        )
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "dmigrate-mcp-artifact-retention").apply { isDaemon = true }
        }
        val sweep = Runnable {
            service.deleteExpired(phaseC.clock.instant())
        }
        try {
            service.deleteExpired(phaseC.clock.instant())
        } catch (failure: RuntimeException) {
            executor.shutdownNow()
            echo("MCP startup sweep: artifact retention failed: ${failure.message}", err = true)
            throw ProgramResult(2)
        }
        executor.scheduleWithFixedDelay(
            {
                try {
                    sweep.run()
                } catch (failure: RuntimeException) {
                    echo("MCP artifact retention sweep failed: ${failure.message}", err = true)
                }
            },
            ARTIFACT_RETENTION_SWEEP_SECONDS,
            ARTIFACT_RETENTION_SWEEP_SECONDS,
            TimeUnit.SECONDS,
        )
        return AutoCloseable { executor.shutdownNow() }
    }

    private fun startFinalisationTimeoutLoop(phaseC: McpRuntimeWiring): AutoCloseable {
        val service = UploadSessionService(
            sessions = phaseC.uploadSessionStore,
            segments = phaseC.uploadSegmentStore,
            artifacts = phaseC.artifactContentStore,
            quotaService = phaseC.quotaService,
        )
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "dmigrate-mcp-finalisation-timeout").apply { isDaemon = true }
        }
        val sweep = Runnable {
            service.timeoutStaleFinalizingSessions(phaseC.clock.instant())
        }
        try {
            sweep.run()
        } catch (failure: RuntimeException) {
            executor.shutdownNow()
            echo("MCP startup sweep: finalisation timeout failed: ${failure.message}", err = true)
            throw ProgramResult(2)
        }
        val delaySeconds = maxOf(1L, phaseC.operationTimeout.toSeconds())
        executor.scheduleWithFixedDelay(
            {
                try {
                    sweep.run()
                } catch (failure: RuntimeException) {
                    echo("MCP finalisation timeout sweep failed: ${failure.message}", err = true)
                }
            },
            delaySeconds,
            delaySeconds,
            TimeUnit.SECONDS,
        )
        return AutoCloseable { executor.shutdownNow() }
    }

    private fun resolveServerStateConfigOrExit(): McpServerStateConfig? = try {
        McpServerStateConfigResolver(effectiveConnectionConfigPath()).resolve()
    } catch (failure: McpServerStateConfigError) {
        echo("MCP server configuration is invalid:", err = true)
        echo("  - ${failure.message}", err = true)
        throw ProgramResult(2)
    }

    private fun createServerStateDataSource(state: McpServerStateConfig): HikariDataSource {
        val cfg = HikariConfig().apply {
            jdbcUrl = state.jdbcUrl
            state.username?.let { username = it }
            state.password?.let { password = it }
            maximumPoolSize = state.maximumPoolSize
            connectionTimeout = state.connectionTimeoutMs
            poolName = "dmigrate-server-state"
        }
        return HikariDataSource(cfg)
    }

    private fun applyOrValidateMigrations(
        dataSource: HikariDataSource,
        state: McpServerStateConfig,
    ) {
        try {
            val migrations = JdbcMigrationRunner(dataSource)
            if (state.migrationsAuto) {
                migrations.migrate()
            } else {
                migrations.validate()
            }
        } catch (failure: Throwable) {
            echo("MCP server configuration is invalid:", err = true)
            echo(
                "  - server.state migration validation failed: " +
                    "${failure.message ?: failure::class.simpleName}",
                err = true,
            )
            throw ProgramResult(2)
        }
    }

    private fun reportConfigErrors(errors: List<String>): Nothing {
        echo("MCP server configuration is invalid:", err = true)
        errors.forEach { echo("  - $it", err = true) }
        throw ProgramResult(2)
    }
}

class McpApprovalGrantCommand : CliktCommand(name = "approval-grant") {
    override fun help(context: Context) = "Approval grant administration"

    init {
        subcommands(McpApprovalGrantIssueCommand())
    }

    override fun run() = Unit
}

class McpApprovalGrantIssueCommand : CliktCommand(name = "issue") {
    override fun help(context: Context) =
        "Issue a token-bound approval grant for a pending POLICY_REQUIRED challenge."

    private val file by option(
        "--file",
        help = "JSON/YAML ApprovalGrant store used by 'mcp serve --approval-grants-file'.",
    ).path().required()

    private val tenant by option("--tenant", help = "Tenant id from the pending challenge.").required()

    private val caller by option("--caller", help = "Principal id that started the pending job.").required()

    private val tool by option("--tool", help = "Tool name, e.g. schema_reverse_start.").required()

    private val approvalRequestId by option(
        "--approval-request-id",
        help = "approvalRequestId returned by POLICY_REQUIRED.",
    ).required()

    private val idempotencyKey by option(
        "--idempotency-key",
        help = "idempotencyKey used for the pending start call.",
    )

    private val approvalKey by option(
        "--approval-key",
        help = "approvalKey used for a pending synchronous policy-required call.",
    )

    private val payloadFingerprint by option(
        "--payload-fingerprint",
        help = "payloadFingerprint returned by POLICY_REQUIRED.",
    ).required()

    private val scope by option(
        "--scope",
        help = "Approved scope. Repeat for every required scope.",
    ).multiple(required = true)

    private val issuerFingerprint by option(
        "--issuer-fingerprint",
        help = "Stable issuer identity stored in the grant.",
    ).default("cli-approval-grant")

    private val grantSource by option(
        "--grant-source",
        help = "Audit/source label stored in the grant.",
    ).default("cli-admin")

    private val expiresAt by option(
        "--expires-at",
        help = "RFC-3339 expiry instant. Overrides --ttl-seconds.",
    )

    private val ttlSeconds by option(
        "--ttl-seconds",
        help = "Grant lifetime when --expires-at is omitted.",
    ).long().default(300)

    private val token by option(
        "--token",
        help = "Raw token to issue. Defaults to a generated token; only its fingerprint is stored.",
    )

    override fun run() {
        val rawToken = token ?: generatedToken()
        val expiry = expiresAt?.let(Instant::parse) ?: Instant.now().plusSeconds(ttlSeconds)
        val correlation = resolveCorrelation()
        val grant = ApprovalGrant(
            approvalRequestId = approvalRequestId,
            correlationKind = correlation.kind,
            correlationKey = correlation.key,
            approvalTokenFingerprint = ApprovalTokenFingerprint.compute(rawToken),
            toolName = tool,
            tenantId = TenantId(tenant),
            callerId = PrincipalId(caller),
            payloadFingerprint = payloadFingerprint,
            issuerFingerprint = issuerFingerprint,
            issuedScopes = scope.toSet(),
            grantSource = grantSource,
            expiresAt = expiry,
        )
        FileBackedApprovalGrantStore(file).save(grant)
        echo("approvalToken=$rawToken")
        echo("expiresAt=${grant.expiresAt}")
    }

    private fun resolveCorrelation(): GrantCorrelation {
        val idempotency = idempotencyKey
        val approval = approvalKey
        return when {
            idempotency != null && approval != null ->
                throw UsageError("Use exactly one of --idempotency-key or --approval-key")
            idempotency != null -> GrantCorrelation(ApprovalCorrelationKind.IDEMPOTENCY_KEY, idempotency)
            approval != null -> GrantCorrelation(ApprovalCorrelationKind.APPROVAL_KEY, approval)
            else -> throw UsageError("One of --idempotency-key or --approval-key is required")
        }
    }

    private fun generatedToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return "appr_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val SECURE_RANDOM: SecureRandom = SecureRandom()
    }
}

private data class GrantCorrelation(
    val kind: ApprovalCorrelationKind,
    val key: String,
)

private const val ARTIFACT_RETENTION_SWEEP_SECONDS: Long = 300

private data class McpCliServerWiring(
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
     * LF-012 / LN-011 / LN-017 / LN-027 (E3.5): wenn der JDBC-Pfad einen Async-Bundle gebaut
     * hat, wird das Lifecycle hier gehalten — `close()` ruft
     * `shutdown(timeout)` vor `closeable.close()`. So drainen in-flight
     * Jobs sauber, bevor die DataSource zugemacht wird; bei Timeout
     * eskaliert der Executor-Lifecycle selbst per Interrupt.
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

private class CloseStack(private val closeables: List<AutoCloseable>) : AutoCloseable {
    override fun close() {
        var first: Throwable? = null
        for (closeable in closeables) {
            try {
                closeable.close()
            } catch (failure: Throwable) {
                if (first == null) {
                    first = failure
                } else {
                    first.addSuppressed(failure)
                }
            }
        }
        first?.let { throw it }
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
