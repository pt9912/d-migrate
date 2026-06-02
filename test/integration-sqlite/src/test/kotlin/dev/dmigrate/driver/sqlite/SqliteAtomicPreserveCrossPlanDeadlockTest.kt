package dev.dmigrate.driver.sqlite

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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

/**
 * Atomic-Preserve Phase D (2026-06-01): cross-plan-deadlock-proof for
 * the SQLite atomic-preserve executor.
 *
 * SQLite's `BEGIN IMMEDIATE` takes a **database-wide** `RESERVED` lock
 * — there is no per-row lock that could form a deadlock diamond.
 * Cross-plan deadlock is therefore impossible by construction on
 * SQLite; what Phase D verifies here is **serialisation**: two parallel
 * preserve runs over overlapping rows must both commit (one waits on
 * the other's RESERVED lock, then proceeds) within the budget.
 *
 * The negative-smoke half of the PG / MySQL companion tests
 * ([PostgresAtomicPreserveCrossPlanDeadlockTest],
 * [MysqlAtomicPreserveCrossPlanDeadlockTest]) does not apply to
 * SQLite — the lock is unconditional and there is no order to invert.
 * The equivalent stress is a `SQLITE_BUSY` timeout when a holder
 * outlasts the executor's budget, which is already covered by
 * [SqliteAtomicSequencePreserveExecutorIntegrationTest].
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase D ("Cross-Plan-Deadlock-Beweis pro Dialekt"); §4.3 für die
 * DB-weite Lock-Strategie.
 */
class SqliteAtomicPreserveCrossPlanDeadlockTest : FunSpec({

    val executor = SqliteAtomicSequencePreserveExecutor()
    val protectedOpId = ProtectedOperationId("AlterSequenceCurrentValue")

    fun sqliteRef(name: String) =
        SequenceObjectRef(name, null, RenameProjectionDialect.SQLITE)

    fun openConnection(path: Path): Connection =
        DriverManager.getConnection("jdbc:sqlite:${path.absolutePathString()}").apply {
            autoCommit = true
        }

    fun bootstrap(path: Path, seqNames: List<String>, initial: Long) {
        openConnection(path).use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS \"dmg_sequences\"")
                stmt.execute(
                    """
                    CREATE TABLE "dmg_sequences" (
                        "name" TEXT PRIMARY KEY NOT NULL,
                        "next_value" INTEGER NOT NULL,
                        "managed_by" TEXT NOT NULL,
                        "format_version" TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                for (name in seqNames) {
                    stmt.execute(
                        "INSERT INTO \"dmg_sequences\" (\"name\", \"next_value\", " +
                            "\"managed_by\", \"format_version\") " +
                            "VALUES ('$name', $initial, 'd-migrate', 'sqlite-sequence-v1')",
                    )
                }
            }
        }
    }

    test("Cross-plan: two parallel preserve runs serialise via BEGIN IMMEDIATE — both commit Applied") {
        val dbFile = Files.createTempFile("dmigrate-xplan-sqlite-", ".db")
        dbFile.deleteIfExists()
        Files.createFile(dbFile)
        try {
            bootstrap(dbFile, listOf("xplan_a", "xplan_b", "xplan_c"), initial = 100L)

            fun buildBatch(names: List<String>): AtomicSequencePreserveBatch =
                AtomicSequencePreserveBatch(
                    requests = names.map { name ->
                        AtomicSequencePreserveRequest(sqliteRef(name)) { probe ->
                            listOf(
                                "UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} " +
                                    "WHERE \"name\" = '$name'",
                            )
                        }
                    },
                    protectedOperationIds = listOf(protectedOpId),
                    internalFollowUpIds = listOf("op-xplan-sqlite-${names.joinToString("-")}"),
                )

            // Plan 1: [a, b]. Plan 2: [c, b]. Both name-sorted lock
            // sequences would diamond on a per-row primitive — on
            // SQLite the DB-wide RESERVED lock makes one wait until
            // the other commits, so both must finish Applied.
            val plan1Batch = buildBatch(listOf("xplan_a", "xplan_b"))
            val plan2Batch = buildBatch(listOf("xplan_c", "xplan_b"))

            val plan1Started = CountDownLatch(1)
            val plan2Started = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val plan1Result = AtomicReference<AtomicSequencePreserveResult?>()
            val plan2Result = AtomicReference<AtomicSequencePreserveResult?>()
            try {
                val f1 = pool.submit<Unit> {
                    openConnection(dbFile).use { c ->
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
                    openConnection(dbFile).use { c ->
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
        } finally {
            dbFile.deleteIfExists()
        }
    }
})
