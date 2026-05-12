package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Unit-level guard for [MigrationStreamClassifier]. The classifier
 * dispatches between "runner-owned tx" and "stream-owned tx" execution
 * strategies in `JdbcMigrationExecutor` and its test-fixture mirror.
 *
 * Plan-2 §G.1: the classifier reads
 * [MigrationDdlStatement.transactionScope] set by the renderer. The
 * earlier SQL-content sniff (`sql.startsWith("BEGIN")`) is gone — its
 * bounded-but-real false-positive surface (Routine-Body `BEGIN ... END`
 * tokens, Plan-2 §E.1/§E.2) would have silently disabled the runner-
 * managed tx for PG/MySQL once routine bodies start rendering.
 */
class MigrationStreamClassifierTest : FunSpec({

    fun stmt(sql: String, scope: TransactionScope) = MigrationDdlStatement(
        sql = sql,
        operationIds = emptySet(),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
        transactionScope = scope,
    )

    test("empty stream is runner-owned") {
        MigrationStreamClassifier.streamOwnsTransaction(emptyList()) shouldBe false
    }

    test("all-RUNNER_OWNED stream is runner-owned") {
        val statements = listOf(
            stmt("ALTER TABLE \"users\" ADD COLUMN \"email\" TEXT;", TransactionScope.RUNNER_OWNED),
            stmt("ALTER TABLE \"users\" ADD COLUMN \"phone\" TEXT;", TransactionScope.RUNNER_OWNED),
        )
        MigrationStreamClassifier.streamOwnsTransaction(statements) shouldBe false
    }

    test("any STREAM_OWNED statement makes the whole stream stream-owned") {
        val statements = listOf(
            stmt("PRAGMA foreign_keys = OFF;", TransactionScope.STREAM_OWNED),
            stmt("BEGIN IMMEDIATE;", TransactionScope.STREAM_OWNED),
            stmt("CREATE TABLE \"x\" (\"id\" INTEGER PRIMARY KEY);", TransactionScope.STREAM_OWNED),
            stmt("COMMIT;", TransactionScope.STREAM_OWNED),
            stmt("PRAGMA foreign_keys = ON;", TransactionScope.STREAM_OWNED),
        )
        MigrationStreamClassifier.streamOwnsTransaction(statements) shouldBe true
    }

    test("§G.1 regression: SQL body starting with routine BEGIN ... END but scope=RUNNER_OWNED stays runner-owned") {
        // Plan-2 §G.1-Akzeptanz: "Regressionstest mit Routine-/Trigger-
        // artigem SQL-Body, der `BEGIN` enthaelt und trotzdem nicht als
        // stream-owned klassifiziert wird."
        //
        // Once Plan-2 §E.1/§E.2 (Routinen/Trigger) start rendering, a
        // PostgreSQL function body whose first token is `BEGIN` would
        // have tripped the old SQL-sniff heuristic. The classifier must
        // now honour the renderer's explicit RUNNER_OWNED scope.
        val routineBody = """
            CREATE OR REPLACE FUNCTION audit_change()
            RETURNS trigger AS $$
            BEGIN
              INSERT INTO audit_log (event) VALUES (NEW.id);
              RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
        """.trimIndent()
        val statements = listOf(stmt(routineBody, TransactionScope.RUNNER_OWNED))
        MigrationStreamClassifier.streamOwnsTransaction(statements) shouldBe false
    }

    test("§G.1 regression: bare BEGIN; SQL with explicit RUNNER_OWNED scope stays runner-owned") {
        // Defensive corollary: even when a future renderer ships a
        // literal `BEGIN;` as a runner-owned no-op (e.g. a SAVEPOINT
        // tagged differently in Plan-2 §G.3), the classifier must trust
        // the scope field over the SQL text.
        val statements = listOf(stmt("BEGIN;", TransactionScope.RUNNER_OWNED))
        MigrationStreamClassifier.streamOwnsTransaction(statements) shouldBe false
    }

    test("NO_TRANSACTION statement alone is not stream-owned") {
        // Plan-2 §G.1 reserves NO_TRANSACTION for statements that
        // cannot run inside a runner-managed tx (PG CREATE INDEX
        // CONCURRENTLY, MySQL implicit-commit DDL). The classifier
        // only flips to stream-owned for STREAM_OWNED; NO_TRANSACTION
        // dispatch is a separate concern (Plan-2 §A.1).
        val statements = listOf(stmt("CREATE INDEX CONCURRENTLY ix ON t (c);", TransactionScope.NO_TRANSACTION))
        MigrationStreamClassifier.streamOwnsTransaction(statements) shouldBe false
    }

    test("§G.3 mixed transaction scopes are unsupported before execute") {
        val statements = listOf(
            stmt("BEGIN IMMEDIATE;", TransactionScope.STREAM_OWNED),
            stmt("ALTER TABLE \"users\" ADD COLUMN \"email\" TEXT;", TransactionScope.RUNNER_OWNED),
        )

        MigrationStreamClassifier.unsupportedTransactionScopeReason(statements) shouldNotBe null
    }

    test("§G.3 NO_TRANSACTION requires an explicit execution strategy") {
        val statements = listOf(stmt("CREATE INDEX CONCURRENTLY ix ON t (c);", TransactionScope.NO_TRANSACTION))

        MigrationStreamClassifier.unsupportedTransactionScopeReason(statements) shouldNotBe null
    }
})
