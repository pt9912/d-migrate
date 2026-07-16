package dev.dmigrate.cli.commands

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.registry.AiMcpRegistries
import dev.dmigrate.mcp.registry.AiMcpWiring
import dev.dmigrate.mcp.registry.FileBackedApprovalGrantStore
import dev.dmigrate.mcp.registry.McpCoreJobWorkerFactory
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.registry.OperationalMcpWiring
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.adapter.storage.s3.ArtifactStorageConfig
import dev.dmigrate.server.application.artifact.ArtifactRetentionService
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.application.quota.OwnerAwareQuotaService
import dev.dmigrate.server.application.quota.QuotaReservationOwnerStore
import dev.dmigrate.server.application.upload.UploadSessionService
import dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.job.JdbcJobStartTransaction
import dev.dmigrate.server.persistence.jdbc.job.JdbcJobStore
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.persistence.jdbc.quota.JdbcOwnerAwareQuotaService
import dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaReservationOwnerStore
import dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaStore
import dev.dmigrate.server.ports.IdempotencyStore
import dev.dmigrate.server.ports.JobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Server-state bundle returned by [ServerStateFactory.build]. Holds the
 * stores plus the cleanup hook that closes the underlying DataSource
 * (or any other resource the factory took ownership of).
 */
internal data class ServerStateBundle(
    /** Phase-C runtime with the persistent stores swapped in. */
    val phaseCWithPersistence: McpRuntimeWiring,
    val idempotencyStore: IdempotencyStore,
    val jobStartTransaction: JobStartTransaction,
    val quotaReservationOwnerStore: QuotaReservationOwnerStore,
    val ownerAwareQuotaService: OwnerAwareQuotaService,
    /** Closes the DataSource and any other JDBC resources held by the bundle. */
    val cleanup: AutoCloseable,
)

/**
 * Builds the server-state bundle (job/quota/idempotency stores plus
 * any pool/migration resources) from a resolved [McpServerStateConfig].
 *
 * The default implementation [DefaultServerStateFactory] creates a
 * Hikari DataSource, applies Flyway migrations, and wires up the
 * Postgres-flavoured JDBC stores. Tests can substitute an in-memory
 * factory to exercise the JDBC-branch wiring without a real database.
 *
 * Future work: a SQLite-in-memory factory variant lets the unit tests
 * cover the migration path end-to-end on a process-local backend.
 */
internal fun interface ServerStateFactory {
    fun build(state: McpServerStateConfig, phaseC: McpRuntimeWiring): ServerStateBundle
}

/**
 * Default [ServerStateFactory]: Hikari + Flyway + Postgres-flavoured
 * JDBC stores. Mirrors the behaviour the runner had before this
 * factory was introduced.
 */
internal class DefaultServerStateFactory(
    private val stderr: (String) -> Unit,
) : ServerStateFactory {

    override fun build(state: McpServerStateConfig, phaseC: McpRuntimeWiring): ServerStateBundle {
        val dataSource = createServerStateDataSource(state)
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
            return ServerStateBundle(
                phaseCWithPersistence = phaseCWithJdbc,
                idempotencyStore = idempotencyStore,
                jobStartTransaction = JdbcJobStartTransaction(runner, idempotencyStore, jobStore),
                quotaReservationOwnerStore = ownerStore,
                ownerAwareQuotaService = JdbcOwnerAwareQuotaService(
                    transactionRunner = runner,
                    jdbcQuotaStore = quotaStore,
                    jdbcOwnerStore = ownerStore,
                    limitFor = { Long.MAX_VALUE },
                ),
                cleanup = dataSource,
            )
        } catch (failure: Throwable) {
            dataSource.close()
            throw failure
        }
    }

    fun createServerStateDataSource(state: McpServerStateConfig): HikariDataSource {
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

    fun applyOrValidateMigrations(
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
            stderr("MCP server configuration is invalid:")
            stderr(
                "  - server.state migration validation failed: " +
                    "${failure.message ?: failure::class.simpleName}",
            )
            throw McpServeExit(2)
        }
    }
}

/**
 * Builds the MCP runtime wiring stack (Phase C runtime + Phase E
 * operational + Phase G AI) plus the support loops (artifact retention,
 * finalisation-timeout sweeper) that the runner kicks off before
 * transport startup.
 *
 * Splitting this out of [McpServeRunner] keeps the runner focused on
 * Clikt-decoupled lifecycle orchestration. The JDBC server-state path
 * is delegated to a [ServerStateFactory] that defaults to the real
 * Hikari/Flyway/Postgres stack but is constructor-injectable so unit
 * tests can supply an in-memory variant.
 */
internal class McpServeWiring(
    private val effectiveConnectionConfigPath: Path?,
    private val approvalGrantsFile: Path?,
    private val stderr: (String) -> Unit,
    private val serverStateFactory: ServerStateFactory = DefaultServerStateFactory(stderr),
) {

    /**
     * @param artifacts byte-store selection from the `artifacts` YAML
     *  section, parsed once by [McpServeRunner.parseArtifactsConfigOrExit]
     *  (the runner also needs it for the startup-sweep skip and the
     *  start-state stderr line).
     */
    fun build(
        config: McpServerConfig,
        owner: StateDirOwner,
        cursorKeyring: CursorKeyring?,
        artifacts: ArtifactStorageConfig = ArtifactStorageConfig.File,
    ): McpCliServerWiring {
        val phaseC = McpCliRuntimeWiring.runtimeWiring(
            stateDir = owner.resolved.path,
            connectionConfigPath = effectiveConnectionConfigPath,
            cursorKeyring = cursorKeyring,
            operationTimeout = config.operationTimeout,
            artifacts = artifacts,
        )
        val state = resolveServerStateConfigOrExit() ?: return buildInMemory(config, owner, phaseC)

        val bundle = serverStateFactory.build(state, phaseC)
        var artifactRetention: AutoCloseable? = null
        var finalisationTimeout: AutoCloseable? = null
        try {
            val phaseCWithJdbc = bundle.phaseCWithPersistence
            artifactRetention = startArtifactRetentionLoop(phaseCWithJdbc)
            finalisationTimeout = startFinalisationTimeoutLoop(phaseCWithJdbc)
            val executor = McpJobExecutorConfigResolver(effectiveConnectionConfigPath).resolve()
            val executorBundle = dev.dmigrate.server.application.job.JobExecutorFactory.create(executor.config)
            val connectionSecretResolver = dev.dmigrate.connection.ProviderBackedConnectionSecretResolver(
                dev.dmigrate.connection.defaultCredentialProviderRegistry(),
            )
            val phaseE = OperationalMcpWiring(
                runtimeWiring = phaseCWithJdbc,
                idempotencyStore = bundle.idempotencyStore,
                jobStartTransaction = bundle.jobStartTransaction,
                workerHandleRegistry = InMemoryWorkerHandleRegistry(),
                approvalGrantStore = approvalGrantStore(),
                quotaReservationOwnerStore = bundle.quotaReservationOwnerStore,
                ownerAwareQuotaService = bundle.ownerAwareQuotaService,
                executorBundle = executorBundle,
                fallbackJobWorkerFactory = mcpCoreJobWorkerFactory(phaseCWithJdbc, connectionSecretResolver),
                connectionSecretResolver = connectionSecretResolver,
                dataRunnerTempDirectory = owner.resolved.path,
            )
            val phaseG = AiMcpWiring(operationalWiring = phaseE)
            val components = AiMcpRegistries.defaultComponents(phaseG, config.scopeMapping)
            stderr(
                "MCP server-state: persistent backend enabled " +
                    "(migrations.auto=${state.migrationsAuto}, " +
                    "executor=${if (executor.isAsync) "async" else "sync"}).",
            )
            val asyncCfg = executor.config as? dev.dmigrate.server.application.job.JobExecutorConfig.Async
            return McpCliServerWiring(
                runtimeWiring = phaseCWithJdbc,
                aiWiring = phaseG,
                components = components,
                // ownedResources (z. B. S3-Client-Buendel) zuletzt: erst
                // Loops/DataSource stoppen, dann Adapter-Ressourcen freigeben.
                closeable = CloseStack(
                    listOfNotNull(artifactRetention, finalisationTimeout, bundle.cleanup) +
                        phaseCWithJdbc.ownedResources,
                ),
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
                    try {
                        bundle.cleanup.close()
                    } finally {
                        runCatching { CloseStack(phaseC.ownedResources).close() }
                    }
                }
            }
            throw failure
        }
    }

    private fun buildInMemory(
        config: McpServerConfig,
        owner: StateDirOwner,
        phaseC: McpRuntimeWiring,
    ): McpCliServerWiring {
        val artifactRetention = startArtifactRetentionLoop(phaseC)
        val finalisationTimeout = startFinalisationTimeoutLoop(phaseC)
        val idempotencyStore = InMemoryIdempotencyStore()
        val connectionSecretResolver = dev.dmigrate.connection.ProviderBackedConnectionSecretResolver(
            dev.dmigrate.connection.defaultCredentialProviderRegistry(),
        )
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
            closeable = CloseStack(
                listOf(artifactRetention, finalisationTimeout) + phaseC.ownedResources,
            ),
        )
    }

    fun approvalGrantStore() =
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

    fun startArtifactRetentionLoop(phaseC: McpRuntimeWiring): AutoCloseable {
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
            stderr("MCP startup sweep: artifact retention failed: ${failure.message}")
            throw McpServeExit(2)
        }
        executor.scheduleWithFixedDelay(
            {
                try {
                    sweep.run()
                } catch (failure: RuntimeException) {
                    stderr("MCP artifact retention sweep failed: ${failure.message}")
                }
            },
            ARTIFACT_RETENTION_SWEEP_SECONDS,
            ARTIFACT_RETENTION_SWEEP_SECONDS,
            TimeUnit.SECONDS,
        )
        return AutoCloseable { executor.shutdownNow() }
    }

    fun startFinalisationTimeoutLoop(phaseC: McpRuntimeWiring): AutoCloseable {
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
            stderr("MCP startup sweep: finalisation timeout failed: ${failure.message}")
            throw McpServeExit(2)
        }
        val delaySeconds = maxOf(1L, phaseC.operationTimeout.toSeconds())
        executor.scheduleWithFixedDelay(
            {
                try {
                    sweep.run()
                } catch (failure: RuntimeException) {
                    stderr("MCP finalisation timeout sweep failed: ${failure.message}")
                }
            },
            delaySeconds,
            delaySeconds,
            TimeUnit.SECONDS,
        )
        return AutoCloseable { executor.shutdownNow() }
    }

    fun resolveServerStateConfigOrExit(): McpServerStateConfig? = try {
        McpServerStateConfigResolver(effectiveConnectionConfigPath).resolve()
    } catch (failure: McpServerStateConfigError) {
        stderr("MCP server configuration is invalid:")
        stderr("  - ${failure.message}")
        throw McpServeExit(2)
    }

    private companion object {
        private const val ARTIFACT_RETENTION_SWEEP_SECONDS: Long = 300
    }
}
