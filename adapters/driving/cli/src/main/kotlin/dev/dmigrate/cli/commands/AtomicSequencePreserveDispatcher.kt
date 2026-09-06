package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SequenceCapabilityDefaults
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor
import dev.dmigrate.driver.mysql.MysqlAtomicSequencePreserveExecutor
import dev.dmigrate.driver.postgresql.PostgresAtomicSequencePreserveExecutor
import dev.dmigrate.driver.sqlite.SqliteAtomicSequencePreserveExecutor

/**
 * Atomic-Preserve Phase C.4 (2026-06-01): CLI-side dispatcher that
 * picks the per-dialect [AtomicSequencePreserveExecutor]
 * implementation from the Phase-B adapter modules.
 *
 * Plan-Doc: `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase C / Sub-Slice C.4. Executor implementations are stateless
 * and reusable across plan runs, so one instance per dialect lives
 * statically inside this object — a fresh JDBC [java.sql.Connection]
 * is the per-call requirement, not a fresh executor.
 *
 * Test seam: callers can pass `AtomicSequencePreserveDispatcher::executorFor`
 * directly or override it via [AtomicSequencePreserveRunner]'s
 * `dispatcher` parameter when unit-testing the runner without
 * exercising the dialect-specific executor implementations.
 */
internal object AtomicSequencePreserveDispatcher {

    private val postgres: AtomicSequencePreserveExecutor = PostgresAtomicSequencePreserveExecutor()
    private val mysql: AtomicSequencePreserveExecutor = MysqlAtomicSequencePreserveExecutor()
    private val sqlite: AtomicSequencePreserveExecutor = SqliteAtomicSequencePreserveExecutor()

    // Capability-gefuehrt statt hartcodierter Dialekt-Aufzaehlung: ein
    // Dialekt, der spaeter Atomic-Preserve bekommt (siehe
    // docs/planning/next/atomic-preserve-mssql-oracle.md), braucht hier
    // keine Anpassung -- nur einen echten `when`-Zweig weiter unten.
    fun executorFor(dialect: DatabaseDialect): AtomicSequencePreserveExecutor {
        check(SequenceCapabilityDefaults.forDialect(dialect).supportsAtomicPreserve) {
            "unreachable: SequenceCapabilityDefaults declares no atomic preserve for " +
                "${dialect.name.lowercase()}, der Atomic-Pfad waehlt den Dialekt also nie aus."
        }
        return when (dialect) {
            DatabaseDialect.POSTGRESQL -> postgres
            DatabaseDialect.MYSQL -> mysql
            DatabaseDialect.SQLITE -> sqlite
            DatabaseDialect.MSSQL, DatabaseDialect.ORACLE ->
                error("unreachable: guarded by the supportsAtomicPreserve check above")
        }
    }
}
