package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import java.nio.file.Path

/**
 * Atomic-Preserve Phase C.4 (2026-06-01): CLI-side runner that
 * allocates a dedicated, owned [DatabaseConnection] for an
 * `AtomicPreserveSegment` and dispatches the work to the dialect-
 * specific [AtomicSequencePreserveExecutor].
 *
 * Plan-Doc: `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase C / Sub-Slice C.4. The runner owns the connection
 * allocation per the plan-doc (`Connection-Allokation … ausschließlich
 * über SchemaMigrateWiring`); the C.3 segment-aware execute-runner
 * becomes the only production consumer once that sub-slice lands.
 *
 * Pre-C.3 there is no production caller — the runner is unit-tested
 * via [dispatcher] and [acquireConnection] test seams that stand in
 * for the real per-dialect executor and the Hikari pool.
 *
 * Lock-timeout default mirrors the §4.0 lock-matrix budget (5 s).
 * Callers can override per request; the C.1 stage refactor will wire
 * the value from the request layer once a CLI / request field for
 * `lockTimeoutMillis` lands.
 */
internal object AtomicSequencePreserveRunner {

    /**
     * Default lock-acquisition budget (§4.0). Per-dialect executors
     * translate this into `SET LOCAL lock_timeout` (PG),
     * `SET SESSION innodb_lock_wait_timeout` (MySQL), or
     * `PRAGMA busy_timeout` (SQLite) — see plan-doc §4.1/§4.2/§4.3.
     */
    const val DEFAULT_LOCK_TIMEOUT_MILLIS: Long = 5000L

    /**
     * Result of [acquireConnection]: the resolved [DatabaseDialect]
     * the dispatcher needs plus the owned [ConnectionPool] the
     * runner closes on return.
     */
    internal data class AcquiredPool(
        val dialect: DatabaseDialect,
        val pool: ConnectionPool,
    )

    fun execute(
        target: CompareOperand.Database,
        configPath: Path?,
        batch: AtomicSequencePreserveBatch,
        executeProtectedOperations: (DatabaseConnection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
        lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
        cancellationToken: CancellationToken = CancellationToken.none(),
        dispatcher: (DatabaseDialect) -> AtomicSequencePreserveExecutor =
            AtomicSequencePreserveDispatcher::executorFor,
        acquireConnection: (CompareOperand.Database, Path?) -> AcquiredPool = ::defaultAcquireConnection,
    ): AtomicSequencePreserveResult {
        require(lockTimeoutMillis > 0) {
            "lockTimeoutMillis must be > 0, got $lockTimeoutMillis"
        }
        val acquired = acquireConnection(target, configPath)
        val executor = dispatcher(acquired.dialect)
        return acquired.pool.use { pool ->
            pool.borrow().use { handle ->
                executor.execute(handle, batch, lockTimeoutMillis, cancellationToken, executeProtectedOperations)
            }
        }
    }

    /**
     * Production [acquireConnection]: resolves the named connection
     * from CLI config, parses the JDBC URL, and opens a Hikari pool —
     * one pool per `execute(...)` call. Config-side failures surface
     * as [CompareConfigException] (CLI exit 7), identical to the
     * other CLI-side dispatcher runners (e.g.
     * `MysqlSequenceCanonicityProbeRunner`,
     * `SqliteCastPreflightProbeRunner`).
     */
    private fun defaultAcquireConnection(
        target: CompareOperand.Database,
        configPath: Path?,
    ): AcquiredPool {
        val url = try {
            NamedConnectionResolver(configPathFromCli = configPath).resolve(target.source)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
        val config = try {
            EnvCredentialFiller().fill(ConnectionUrlParser.parse(url))
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "URL parse failed", e)
        }
        return AcquiredPool(
            dialect = config.dialect,
            pool = HikariConnectionPoolFactory.create(config),
        )
    }
}
