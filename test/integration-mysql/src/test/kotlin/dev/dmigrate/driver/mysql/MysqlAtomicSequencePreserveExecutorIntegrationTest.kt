package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Atomic-Preserve Phase B.3 (2026-05-31): live-MySQL coverage for
 * [MysqlAtomicSequencePreserveExecutor]. Pins the four canonical
 * paths plus the timeout-leak guard required by plan §6 Risk 6.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase B DoD.
 */
class MysqlAtomicSequencePreserveExecutorIntegrationTest : FunSpec({

    val container = MySQLContainer("mysql:8")
        .withDatabaseName("atomic_preserve_it")
        .withUsername("test")
        .withPassword("test")
        .withCommand("--log-bin-trust-function-creators=1")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun conn() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun exec(sql: String) {
        conn().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    val executor = MysqlAtomicSequencePreserveExecutor()
    val protectedOpId = ProtectedOperationId("AlterSequenceCurrentValue")

    fun mysqlRef(name: String) =
        SequenceObjectRef(name, null, RenameProjectionDialect.MYSQL)

    fun bootstrapCanonical(seqName: String) {
        exec("DROP FUNCTION IF EXISTS `dmg_nextval`")
        exec("DROP FUNCTION IF EXISTS `dmg_setval`")
        exec("DROP TABLE IF EXISTS `dmg_sequences`")
        val schema = SchemaDefinition(
            name = "AtomicIT", version = "1",
            sequences = mapOf(seqName to SequenceDefinition(start = 100L, increment = 1L)),
        )
        val result = MysqlDdlGenerator().generate(
            schema,
            DdlGenerationOptions(
                dialectContext = DdlDialectContext.MySql(namedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE),
            ),
        )
        for (block in splitMysqlStatements(result.render())) {
            if (block.isNotBlank()) {
                conn().use { c -> c.createStatement().use { it.execute(block) } }
            }
        }
    }

    fun queryNextValue(seqName: String): Long {
        conn().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT `next_value` FROM `dmg_sequences` WHERE `name` = '$seqName'",
                ).use { rs ->
                    rs.next() shouldBe true
                    return rs.getLong(1)
                }
            }
        }
    }

    test("Applied: single-sequence batch locks the row, runs protected ops, restores next_value") {
        bootstrapCanonical("atom_seq_a")
        // Advance the helper-table directly so the probe sees a
        // meaningful value (helper-table emulation starts at start =
        // 100; we bump it to 105 to make the restore visible).
        exec("UPDATE `dmg_sequences` SET `next_value` = 105 WHERE `name` = 'atom_seq_a'")

        val ref = mysqlRef("atom_seq_a")
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    listOf(
                        "UPDATE `dmg_sequences` SET `next_value` = ${probe.value} " +
                            "WHERE `name` = 'atom_seq_a' " +
                            "AND `managed_by` = '${probe.managedBy}'",
                    )
                },
            ),
            protectedOperationIds = listOf(protectedOpId),
            internalFollowUpIds = listOf("op-atom_seq_a"),
        )

        conn().use { c ->
            val result = executor.execute(c, batch, lockTimeoutMillis = 5_000) { protectedConn, _ ->
                // Protected op: advance the helper-table further so
                // the *restore* statement undoes the advance.
                protectedConn.createStatement().use { s ->
                    s.execute("UPDATE `dmg_sequences` SET `next_value` = 999 WHERE `name` = 'atom_seq_a'")
                }
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 1)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
            result.refs shouldBe listOf(ref)
        }

        // The restore overwrote the protected op's bump; next_value
        // is back to the probed snapshot.
        queryNextValue("atom_seq_a") shouldBe 105L
    }

    test("NotFound: missing helper-table row rolls back the batch") {
        bootstrapCanonical("atom_seq_present")
        // Probe asks for a *different* sequence that isn't seeded
        // in the helper table.
        val ref = mysqlRef("atom_seq_missing")
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    listOf(
                        "UPDATE `dmg_sequences` SET `next_value` = ${probe.value} " +
                            "WHERE `name` = 'atom_seq_missing'",
                    )
                },
            ),
            protectedOperationIds = emptyList(),
            internalFollowUpIds = emptyList(),
        )
        conn().use { c ->
            val result = executor.execute(c, batch, lockTimeoutMillis = 1_000) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.NotFound>()
            result.refs shouldBe listOf(ref)
        }
    }

    test("LockTimeout: concurrent FOR UPDATE holder triggers ER_LOCK_WAIT_TIMEOUT") {
        bootstrapCanonical("atom_seq_locked")
        val holderAcquired = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<Unit> {
                DriverManager.getConnection(
                    container.jdbcUrl, container.username, container.password,
                ).use { holderConn ->
                    holderConn.autoCommit = false
                    holderConn.createStatement().use { s ->
                        s.execute(
                            "SELECT `next_value` FROM `dmg_sequences` " +
                                "WHERE `name` = 'atom_seq_locked' FOR UPDATE",
                        )
                    }
                    holderAcquired.countDown()
                    holderRelease.await(20, TimeUnit.SECONDS)
                    holderConn.rollback()
                }
            }
            holderAcquired.await(10, TimeUnit.SECONDS) shouldBe true

            val ref = mysqlRef("atom_seq_locked")
            val batch = AtomicSequencePreserveBatch(
                requests = listOf(
                    AtomicSequencePreserveRequest(ref) { probe ->
                        listOf(
                            "UPDATE `dmg_sequences` SET `next_value` = ${probe.value} " +
                                "WHERE `name` = 'atom_seq_locked'",
                        )
                    },
                ),
                protectedOperationIds = emptyList(),
                internalFollowUpIds = emptyList(),
            )
            conn().use { c ->
                // 1 s is the MySQL minimum; the holder above blocks
                // us, so we time out and surface LockTimeout.
                val result = executor.execute(c, batch, lockTimeoutMillis = 1_000) { _, _ ->
                    AtomicProtectedExecutionResult.Succeeded(0)
                }
                result.shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
                result.refs shouldBe listOf(ref)
            }

            holderRelease.countDown()
            future.get(10, TimeUnit.SECONDS)
        } finally {
            holderRelease.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    test("Session lock_wait_timeout is restored to its borrow-time value (no pool leak)") {
        bootstrapCanonical("atom_seq_timeout_leak")
        val ref = mysqlRef("atom_seq_timeout_leak")
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    listOf(
                        "UPDATE `dmg_sequences` SET `next_value` = ${probe.value} " +
                            "WHERE `name` = 'atom_seq_timeout_leak'",
                    )
                },
            ),
            protectedOperationIds = emptyList(),
            internalFollowUpIds = emptyList(),
        )
        conn().use { c ->
            // MySQL default is 50; capture so we don't depend on it.
            val before = c.createStatement().use { s ->
                s.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout").use { rs ->
                    rs.next(); rs.getLong(1)
                }
            }
            val result = executor.execute(c, batch, lockTimeoutMillis = 1_000) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
            val after = c.createStatement().use { s ->
                s.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout").use { rs ->
                    rs.next(); rs.getLong(1)
                }
            }
            after shouldBe before
        }
    }

    // ─── Service-Mode Sub-Slice E: Cancellation ─────────────────────

    test("Cancelled: pre-BEGIN cancel short-circuits without opening a transaction") {
        bootstrapCanonical("atom_seq_cancel_pre")
        exec("UPDATE `dmg_sequences` SET `next_value` = 142 WHERE `name` = 'atom_seq_cancel_pre'")
        val initialNextValue = queryNextValue("atom_seq_cancel_pre")

        val ref = mysqlRef("atom_seq_cancel_pre")
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    listOf(
                        "UPDATE `dmg_sequences` SET `next_value` = ${probe.value} " +
                            "WHERE `name` = 'atom_seq_cancel_pre'",
                    )
                },
            ),
            protectedOperationIds = listOf(protectedOpId),
            internalFollowUpIds = emptyList(),
        )
        val tokenSource = dev.dmigrate.core.cancel.CancellationTokenSource.create()
        tokenSource.cancel("pre-BEGIN-test-cancel")

        conn().use { c ->
            val result = executor.execute(
                connection = c,
                batch = batch,
                lockTimeoutMillis = 5_000,
                cancellationToken = tokenSource.token,
                executeProtectedOperations = { _, _ ->
                    error("protected callback must not be reached after pre-BEGIN cancel")
                },
            )
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Cancelled>()
            result.reason shouldBe "pre-BEGIN-test-cancel"
            result.refs shouldBe listOf(ref)
        }
        // Sequence completely untouched (no BEGIN, no UPDATE).
        queryNextValue("atom_seq_cancel_pre") shouldBe initialNextValue
    }

    test("Cancelled: cancel inside protected callback rolls back; helper-table next_value at probed snapshot") {
        bootstrapCanonical("atom_seq_cancel_mid")
        exec("UPDATE `dmg_sequences` SET `next_value` = 142 WHERE `name` = 'atom_seq_cancel_mid'")
        val initialNextValue = queryNextValue("atom_seq_cancel_mid")

        val ref = mysqlRef("atom_seq_cancel_mid")
        val tokenSource = dev.dmigrate.core.cancel.CancellationTokenSource.create()
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { _ ->
                    error("renderRestore must not run after a post-protected cancel")
                },
            ),
            protectedOperationIds = listOf(protectedOpId),
            internalFollowUpIds = emptyList(),
        )

        conn().use { c ->
            val result = executor.execute(
                connection = c,
                batch = batch,
                lockTimeoutMillis = 5_000,
                cancellationToken = tokenSource.token,
                executeProtectedOperations = { protectedConn, _ ->
                    protectedConn.createStatement().use {
                        it.execute("UPDATE `dmg_sequences` SET `next_value` = 999 WHERE `name` = 'atom_seq_cancel_mid'")
                    }
                    tokenSource.cancel("post-protected-test-cancel")
                    AtomicProtectedExecutionResult.Succeeded(1)
                },
            )
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Cancelled>()
            result.reason shouldBe "post-protected-test-cancel"
            result.refs shouldBe listOf(ref)
        }
        // MySQL ROLLBACK undoes the protected UPDATE on dmg_sequences;
        // next_value returns to the probed snapshot, not the
        // protected-op bump value (999) and not the restored value
        // (also probed snapshot) — they coincide here because the
        // rollback obviates both.
        queryNextValue("atom_seq_cancel_mid") shouldBe initialNextValue
    }
})
