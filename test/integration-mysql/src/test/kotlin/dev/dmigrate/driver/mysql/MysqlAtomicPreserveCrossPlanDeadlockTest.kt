package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Atomic-Preserve Phase D (2026-06-01): cross-plan-deadlock-proof for
 * the MySQL atomic-preserve executor. Two parallel `schema migrate`
 * runs that touch overlapping `dmg_sequences` rows must serialise on
 * the per-row `FOR UPDATE` lock without diamond-deadlocking.
 *
 * Companion to [PostgresAtomicPreserveCrossPlanDeadlockTest]. Both
 * positive and negative smoke follow the same shape; MySQL's
 * `innodb_lock_wait_timeout` plays the role PG's `lock_timeout` plays.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase D ("Cross-Plan-Deadlock-Beweis pro Dialekt").
 */
class MysqlAtomicPreserveCrossPlanDeadlockTest : FunSpec({

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("atomic_xplan_it")
        .withUsername("test")
        .withPassword("test")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun openConn() = DriverManager.getConnection(
        container.jdbcUrl, container.username, container.password,
    ).apply { autoCommit = true }

    fun exec(sql: String) {
        openConn().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun bootstrap(seqNames: List<String>, initial: Long) {
        exec(
            """
            CREATE TABLE IF NOT EXISTS `dmg_sequences` (
                `name` VARCHAR(64) PRIMARY KEY,
                `next_value` BIGINT NOT NULL,
                `managed_by` VARCHAR(32) NOT NULL DEFAULT 'd-migrate',
                `format_version` VARCHAR(32) NOT NULL DEFAULT 'mysql-sequence-v1'
            ) ENGINE=InnoDB
            """.trimIndent(),
        )
        for (name in seqNames) {
            exec("DELETE FROM `dmg_sequences` WHERE `name` = '$name'")
            exec("INSERT INTO `dmg_sequences` (`name`, `next_value`) VALUES ('$name', $initial)")
        }
    }

    fun mysqlRef(name: String) =
        SequenceObjectRef(name, null, RenameProjectionDialect.MYSQL)

    val executor = MysqlAtomicSequencePreserveExecutor()
    val protectedOpId = ProtectedOperationId("AlterSequenceCurrentValue")

    test("Cross-plan: two parallel preserve runs with overlapping rows commit without deadlock") {
        bootstrap(listOf("xplan_a", "xplan_b", "xplan_c"), initial = 100L)

        fun buildBatch(names: List<String>): AtomicSequencePreserveBatch =
            AtomicSequencePreserveBatch(
                requests = names.map { name ->
                    AtomicSequencePreserveRequest(mysqlRef(name)) { probe ->
                        listOf(
                            "UPDATE `dmg_sequences` SET `next_value` = ${probe.value} " +
                                "WHERE `name` = '$name' AND `managed_by` = 'd-migrate' " +
                                "AND `format_version` = 'mysql-sequence-v1'",
                        )
                    }
                },
                protectedOperationIds = listOf(protectedOpId),
                internalFollowUpIds = listOf("op-xplan-${names.joinToString("-")}"),
            )

        // Plan 1: [a, b]. Plan 2: [c, b] — both name-sorted lock
        // exactly when reaching `b`; the second one waits, no diamond.
        val plan1Batch = buildBatch(listOf("xplan_a", "xplan_b"))
        val plan2Batch = buildBatch(listOf("xplan_c", "xplan_b"))

        val plan1Started = CountDownLatch(1)
        val plan2Started = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val plan1Result = AtomicReference<AtomicSequencePreserveResult?>()
        val plan2Result = AtomicReference<AtomicSequencePreserveResult?>()
        try {
            val f1 = pool.submit<Unit> {
                openConn().use { c ->
                    plan1Started.countDown()
                    plan2Started.await(10, TimeUnit.SECONDS)
                    plan1Result.set(
                        executor.execute(c, plan1Batch, lockTimeoutMillis = 15_000) { _, _ ->
                            AtomicProtectedExecutionResult.Succeeded(0)
                        },
                    )
                }
            }
            val f2 = pool.submit<Unit> {
                openConn().use { c ->
                    plan2Started.countDown()
                    plan1Started.await(10, TimeUnit.SECONDS)
                    plan2Result.set(
                        executor.execute(c, plan2Batch, lockTimeoutMillis = 15_000) { _, _ ->
                            AtomicProtectedExecutionResult.Succeeded(0)
                        },
                    )
                }
            }
            f1.get(30, TimeUnit.SECONDS)
            f2.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        plan1Result.get().shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        plan2Result.get().shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
    }

    test("Negative smoke: two threads take FOR UPDATE in inverted order — innodb_lock_wait_timeout / deadlock fires") {
        // Bypass the executor: hand-craft the deadlocking lock order.
        // Thread A: FOR UPDATE on `seq_x`, then `seq_y`.
        // Thread B: FOR UPDATE on `seq_y`, then `seq_x`.
        // MySQL detects the deadlock and rolls back one transaction
        // with ER_LOCK_DEADLOCK (1213); under heavier contention the
        // wait-timeout (1205) can fire instead. Either is proof the
        // primitive deadlocks without sort.
        bootstrap(listOf("seq_x", "seq_y"), initial = 1L)

        val aFirstAcquired = CountDownLatch(1)
        val bFirstAcquired = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val aErr = AtomicReference<Int?>()
        val bErr = AtomicReference<Int?>()
        try {
            val fA = pool.submit<Unit> {
                openConn().use { c ->
                    c.autoCommit = false
                    c.createStatement().use { it.execute("SET innodb_lock_wait_timeout = 2") }
                    runCatching {
                        c.prepareStatement(
                            "SELECT `next_value` FROM `dmg_sequences` WHERE `name` = ? FOR UPDATE",
                        ).use { ps ->
                            ps.setString(1, "seq_x")
                            ps.executeQuery().use { rs -> rs.next() }
                        }
                        aFirstAcquired.countDown()
                        bFirstAcquired.await(10, TimeUnit.SECONDS)
                        c.prepareStatement(
                            "SELECT `next_value` FROM `dmg_sequences` WHERE `name` = ? FOR UPDATE",
                        ).use { ps ->
                            ps.setString(1, "seq_y")
                            ps.executeQuery().use { rs -> rs.next() }
                        }
                    }.onFailure { t -> aErr.set((t as? SQLException)?.errorCode ?: -1) }
                    runCatching { c.rollback() }
                }
            }
            val fB = pool.submit<Unit> {
                openConn().use { c ->
                    c.autoCommit = false
                    c.createStatement().use { it.execute("SET innodb_lock_wait_timeout = 2") }
                    runCatching {
                        c.prepareStatement(
                            "SELECT `next_value` FROM `dmg_sequences` WHERE `name` = ? FOR UPDATE",
                        ).use { ps ->
                            ps.setString(1, "seq_y")
                            ps.executeQuery().use { rs -> rs.next() }
                        }
                        bFirstAcquired.countDown()
                        aFirstAcquired.await(10, TimeUnit.SECONDS)
                        c.prepareStatement(
                            "SELECT `next_value` FROM `dmg_sequences` WHERE `name` = ? FOR UPDATE",
                        ).use { ps ->
                            ps.setString(1, "seq_x")
                            ps.executeQuery().use { rs -> rs.next() }
                        }
                    }.onFailure { t -> bErr.set((t as? SQLException)?.errorCode ?: -1) }
                    runCatching { c.rollback() }
                }
            }
            fA.get(30, TimeUnit.SECONDS)
            fB.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        // ER_LOCK_DEADLOCK = 1213, ER_LOCK_WAIT_TIMEOUT = 1205. Either
        // proves the inverted acquisition order doesn't survive on
        // its own — the executor's name-sort is what makes it safe.
        val outcomes = listOf(aErr.get(), bErr.get())
        outcomes.any { it == 1213 || it == 1205 } shouldBe true
    }
})
