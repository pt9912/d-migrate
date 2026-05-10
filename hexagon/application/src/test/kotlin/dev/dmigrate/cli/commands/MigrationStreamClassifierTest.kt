package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit-level guard for [MigrationStreamClassifier]. The classifier
 * dispatches between "runner-owned tx" and "stream-owned tx" execution
 * strategies in `JdbcMigrationExecutor` and its test-fixture mirror;
 * Carve-out F.4-1 (`docs/planning/in-progress/diffresult-migration-plan.md
 * §11.2`) documents that this is a content-sniffing heuristic and lists
 * its bounded-but-real false-positive surface. These tests pin the
 * boundary cases so a "perf-cleanup" of the helper can't silently drop
 * a branch (which only the integration smokes would otherwise catch —
 * and only under `-PintegrationTests`).
 */
class MigrationStreamClassifierTest : FunSpec({

    fun stmt(sql: String) = MigrationDdlStatement(
        sql = sql,
        operationIds = emptySet(),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    test("empty stream → runner-owned") {
        MigrationStreamClassifier.streamOwnsTransaction(emptyList()) shouldBe false
    }

    // ── Stream-owned positives — every form SQLite's rebuild can emit ──

    test("BEGIN; → stream-owned") {
        MigrationStreamClassifier.isBeginStatement("BEGIN;") shouldBe true
    }

    test("BEGIN (no semicolon) → stream-owned") {
        MigrationStreamClassifier.isBeginStatement("BEGIN") shouldBe true
    }

    test("BEGIN IMMEDIATE; (SqliteRebuildRenderer's actual emit) → stream-owned") {
        MigrationStreamClassifier.isBeginStatement("BEGIN IMMEDIATE;") shouldBe true
    }

    test("BEGIN DEFERRED; → stream-owned") {
        MigrationStreamClassifier.isBeginStatement("BEGIN DEFERRED;") shouldBe true
    }

    test("BEGIN EXCLUSIVE; → stream-owned") {
        MigrationStreamClassifier.isBeginStatement("BEGIN EXCLUSIVE;") shouldBe true
    }

    test("BEGIN TRANSACTION; → stream-owned") {
        MigrationStreamClassifier.isBeginStatement("BEGIN TRANSACTION;") shouldBe true
    }

    test("lowercase begin transaction → stream-owned (case-insensitive)") {
        MigrationStreamClassifier.isBeginStatement("begin transaction;") shouldBe true
    }

    test("leading whitespace → stream-owned (trimStart applies)") {
        MigrationStreamClassifier.isBeginStatement("  \n\t  BEGIN IMMEDIATE;") shouldBe true
    }

    // ── Runner-owned negatives — typical PG/MySQL rendered DDL ──

    test("ALTER TABLE … ADD COLUMN → runner-owned") {
        MigrationStreamClassifier.isBeginStatement(
            "ALTER TABLE \"users\" ADD COLUMN \"email\" TEXT;",
        ) shouldBe false
    }

    test("CREATE TABLE → runner-owned") {
        MigrationStreamClassifier.isBeginStatement(
            "CREATE TABLE \"orders\" (\"id\" BIGINT PRIMARY KEY);",
        ) shouldBe false
    }

    test("DROP TABLE → runner-owned") {
        MigrationStreamClassifier.isBeginStatement("DROP TABLE \"orders\";") shouldBe false
    }

    test("PRAGMA foreign_keys = OFF (rebuild PREPARE statement, no leading BEGIN) → runner-owned") {
        MigrationStreamClassifier.isBeginStatement("PRAGMA foreign_keys = OFF;") shouldBe false
    }

    test("COMMIT (rebuild CLEANUP statement) → runner-owned alone") {
        MigrationStreamClassifier.isBeginStatement("COMMIT;") shouldBe false
    }

    // ── False-positive guards documented in Carve-out F.4-1 ──

    test("column named begin_time → runner-owned (column name is never the leading token)") {
        MigrationStreamClassifier.isBeginStatement(
            "ALTER TABLE \"events\" ADD COLUMN \"begin_time\" TIMESTAMPTZ;",
        ) shouldBe false
    }

    test("CREATE TABLE with begin_time column → runner-owned") {
        MigrationStreamClassifier.isBeginStatement(
            "CREATE TABLE \"slots\" (\"begin_time\" DATE, \"end_time\" DATE);",
        ) shouldBe false
    }

    test("BEGINNING (false BEGIN-prefix substring) → runner-owned") {
        // Ensures the check is BEGIN-token, not BEGIN-substring.
        MigrationStreamClassifier.isBeginStatement("BEGINNING_OF_TIME;") shouldBe false
    }

    // ── Stream-level dispatch ──

    test("any statement matching → whole stream is stream-owned") {
        val statements = listOf(
            stmt("PRAGMA foreign_keys = OFF;"),
            stmt("BEGIN IMMEDIATE;"),
            stmt("CREATE TABLE \"x\" (\"id\" INTEGER PRIMARY KEY);"),
            stmt("COMMIT;"),
            stmt("PRAGMA foreign_keys = ON;"),
        )
        MigrationStreamClassifier.streamOwnsTransaction(statements) shouldBe true
    }

    test("no statement matching → stream is runner-owned") {
        val statements = listOf(
            stmt("ALTER TABLE \"users\" ADD COLUMN \"email\" TEXT;"),
            stmt("ALTER TABLE \"users\" ADD COLUMN \"phone\" TEXT;"),
        )
        MigrationStreamClassifier.streamOwnsTransaction(statements) shouldBe false
    }
})
