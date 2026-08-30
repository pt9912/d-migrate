package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.mysql.MysqlAtomicSequencePreserveExecutor
import dev.dmigrate.driver.postgresql.PostgresAtomicSequencePreserveExecutor
import dev.dmigrate.driver.sqlite.SqliteAtomicSequencePreserveExecutor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Atomic-Preserve Phase C.4: pin per-dialect resolution of the
 * dispatcher. The dispatcher is the single CLI-side place where the
 * dialect-specific Phase-B executor implementations are chosen; if a
 * future refactor breaks the routing the test ensures
 * `:adapters:driving:cli:test` fails before the integration suite.
 */
class AtomicSequencePreserveDispatcherTest : FunSpec({

    test("PostgreSQL resolves to PostgresAtomicSequencePreserveExecutor") {
        AtomicSequencePreserveDispatcher
            .executorFor(DatabaseDialect.POSTGRESQL)
            .shouldBeInstanceOf<PostgresAtomicSequencePreserveExecutor>()
    }

    test("MySQL resolves to MysqlAtomicSequencePreserveExecutor") {
        AtomicSequencePreserveDispatcher
            .executorFor(DatabaseDialect.MYSQL)
            .shouldBeInstanceOf<MysqlAtomicSequencePreserveExecutor>()
    }

    test("SQLite resolves to SqliteAtomicSequencePreserveExecutor") {
        AtomicSequencePreserveDispatcher
            .executorFor(DatabaseDialect.SQLITE)
            .shouldBeInstanceOf<SqliteAtomicSequencePreserveExecutor>()
    }

    test("dispatcher reuses the same executor instance across calls (stateless reuse contract)") {
        // MSSQL fehlt bewusst: kein Executor, der Migrate-Pfad weist mssql an
        listOf(DatabaseDialect.POSTGRESQL, DatabaseDialect.MYSQL, DatabaseDialect.SQLITE).forEach { dialect ->
            val first = AtomicSequencePreserveDispatcher.executorFor(dialect)
            val second = AtomicSequencePreserveDispatcher.executorFor(dialect)
            // Identity, not equality — the dispatcher caches one
            // instance per dialect; per-call construction would risk
            // per-request state surprises in future executors.
            (first === second) shouldBe true
        }
    }

    test("mssql has no atomic executor — the capability, not the gate, keeps it unreachable") {
        // Unerreichbar bleibt der Atomic-Pfad, weil
        // `SequenceCapabilityDefaults` fuer mssql kein Atomic-Preserve meldet;
        // die Sperrstrategie dafuer ist weder entworfen noch belegt.
        val ex = io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
            AtomicSequencePreserveDispatcher.executorFor(DatabaseDialect.MSSQL)
        }
        ex.message!!.contains("no atomic preserve") shouldBe true
    }
})
