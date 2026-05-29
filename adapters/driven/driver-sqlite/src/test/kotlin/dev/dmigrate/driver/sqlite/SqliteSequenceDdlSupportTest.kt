package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.actionRequiredOptions
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.helperTableOptions
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.schemaWith
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.seqColumn
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.textColumn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * 0.9.7 SQLite-Sequence Phase B.3 mode-gate, helper-table emission
 * and seed-INSERT coverage for [SqliteSequenceDdlSupport], wired
 * end-to-end via [SqliteDdlGenerator]. Trigger-body coverage lives
 * in [SqliteSequenceDdlSupportTriggerTest]; E124-collision coverage
 * lives in [SqliteSequenceDdlSupportCollisionTest]. The split keeps
 * each file under detekt's `LargeClass` threshold; shared fixtures
 * sit in [SqliteSequenceTestFixtures].
 */
class SqliteSequenceDdlSupportTest : FunSpec({

    // ── mode gate ──────────────────────────────────────────────────

    test("ACTION_REQUIRED — sequences are skipped with E056 as before") {
        val schema = schemaWith(
            sequences = mapOf("order_seq" to SequenceDefinition(start = 1000, increment = 1)),
        )

        val result = SqliteDdlGenerator().generate(schema, actionRequiredOptions)

        result.skippedObjects.any { it.type == "sequence" && it.name == "order_seq" } shouldBe true
        result.notes.any { it.code == "E056" && it.objectName == "order_seq" } shouldBe true
        result.statements.none { it.sql.contains("dmg_sequences", ignoreCase = true) } shouldBe true
    }

    test("ACTION_REQUIRED — SequenceNextVal column emits E056 per column, no triggers") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, actionRequiredOptions)

        result.notes.any { it.code == "E056" && it.objectName == "orders.order_number" } shouldBe true
        result.statements.none { it.sql.contains("CREATE TRIGGER", ignoreCase = true) } shouldBe true
    }

    // ── helper_table: support table + seeds ─────────────────────────

    test("HELPER_TABLE — emits dmg_sequences support table with canonical column shape") {
        val schema = schemaWith(
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val supportTableSql = result.statements
            .map { it.sql }
            .firstOrNull { it.contains("CREATE TABLE IF NOT EXISTS \"dmg_sequences\"") }
        supportTableSql shouldNotBe null
        supportTableSql!! shouldContain "\"managed_by\" TEXT NOT NULL"
        supportTableSql shouldContain "\"format_version\" TEXT NOT NULL"
        supportTableSql shouldContain "\"next_value\" INTEGER NOT NULL"
        supportTableSql shouldContain "\"last_returned_value\" INTEGER NULL"
        supportTableSql shouldContain "\"exhausted\" INTEGER NOT NULL DEFAULT 0"
        supportTableSql shouldContain "\"cycle_enabled\" INTEGER NOT NULL"
        supportTableSql shouldContain "PRIMARY KEY (\"name\")"
    }

    test("HELPER_TABLE — emits one seed INSERT per sequence with start/increment/cycle") {
        val schema = schemaWith(
            sequences = mapOf(
                "order_seq" to SequenceDefinition(
                    start = 1000,
                    increment = 2,
                    minValue = 1,
                    maxValue = 9999,
                    cycle = true,
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val seed = result.statements
            .map { it.sql }
            .first { it.contains("INSERT INTO \"dmg_sequences\"") }
        seed shouldContain "'d-migrate'"
        seed shouldContain "'sqlite-sequence-v1'"
        seed shouldContain "'order_seq'"
        seed shouldContain ", 1000,"
        seed shouldContain ", 2,"
        seed shouldContain ", 9999,"
        seed shouldContain ", 1,"
    }

    test("HELPER_TABLE — sequence without explicit start defaults next_value to 1") {
        val schema = schemaWith(
            sequences = mapOf("plain_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val seed = result.statements
            .map { it.sql }
            .first { it.contains("INSERT INTO \"dmg_sequences\"") }
        seed shouldContain "'plain_seq'"
        seed shouldContain ", 1, NULL, 0, 1, NULL, NULL, 0, NULL"
    }

    test("HELPER_TABLE — sequence with cache emits W114 metadata-only warning") {
        val schema = schemaWith(
            sequences = mapOf("cached_seq" to SequenceDefinition(cache = 20)),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any {
            it.code == "W114" &&
                it.objectName == "cached_seq" &&
                it.message.contains("does not emulate preallocation")
        } shouldBe true
    }

    // ── cross-cutting W117 ─────────────────────────────────────────

    test("HELPER_TABLE — emits cross-cutting W117 transaction-bound warning once per run") {
        val schema = schemaWith(
            sequences = mapOf("seq_a" to SequenceDefinition(), "seq_b" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.count { it.code == "W117" } shouldBe 1
        result.notes.single { it.code == "W117" }.message shouldContain "transaction-bound"
    }

    test("ACTION_REQUIRED — no W117 emitted (no support objects exist)") {
        val schema = schemaWith(
            sequences = mapOf("seq_a" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, actionRequiredOptions)

        result.notes.none { it.code == "W117" } shouldBe true
    }

    // ── option threading sanity ────────────────────────────────────

    test("default DdlGenerationOptions (DialectContext.None) keeps the action_required surface") {
        val schema = schemaWith(
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema)

        result.notes.any { it.code == "E056" } shouldBe true
        result.statements.none { it.sql.contains("dmg_sequences") } shouldBe true
    }

    test("pendingSupportTriggerSpecs reset between runs") {
        val schemaA = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )
        val schemaB = schemaWith(
            tables = mapOf(
                "items" to TableDefinition(
                    columns = mapOf("label" to textColumn(required = true)),
                    primaryKey = emptyList(),
                ),
            ),
        )

        val generator = SqliteDdlGenerator()
        val first = generator.generate(schemaA, helperTableOptions)
        val second = generator.generate(schemaB, helperTableOptions)

        first.statements.any { it.sql.contains("CREATE TRIGGER") } shouldBe true
        second.statements.none { it.sql.contains("CREATE TRIGGER") } shouldBe true
        second.notes.none { it.code == "W117" } shouldBe true
    }

    test("HELPER_TABLE — sequence-only schema with no tables still emits support table + W117") {
        val schema = schemaWith(
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.statements.any {
            it.sql.contains("CREATE TABLE IF NOT EXISTS \"dmg_sequences\"")
        } shouldBe true
        result.notes.count { it.code == "W117" } shouldBe 1
    }

    test("HELPER_TABLE — schema with sequence-backed column but no schema.sequences still emits support table") {
        // §3.2: helper-table emulation needs dmg_sequences for the triggers to read;
        // an orphan SequenceNextVal default (sequence not declared) still triggers emission
        // so the rendered DDL is internally consistent.
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn("orphan_seq"),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.statements.any {
            it.sql.contains("CREATE TABLE IF NOT EXISTS \"dmg_sequences\"")
        } shouldBe true
    }

    test("HELPER_TABLE — fully empty schema does not emit dmg_sequences") {
        val schema = schemaWith()

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.statements.none {
            it.sql.contains("CREATE TABLE IF NOT EXISTS \"dmg_sequences\"")
        } shouldBe true
        result.notes.none { it.code == "W117" } shouldBe true
    }

    test("recordNotNullSuppressionNote refuses to fire for a column that was not registered") {
        // Defensive: the helper must reject callers that bypass
        // shouldSuppressNotNull / resolveSequenceDefault, otherwise a
        // future caller could emit a stray W119 for a non-sequence
        // column.
        val support = SqliteSequenceDdlSupport()
        support.beginRun(
            schema = schemaWith(),
            options = helperTableOptions,
        )

        val ex = io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            support.recordNotNullSuppressionNote("orders", "missing_col")
        }
        ex.message!! shouldContain "before resolveSequenceDefault registered it"
    }

    test("ACTION_REQUIRED skipped entries are tracked with the canonical type+name tuple") {
        val schema = schemaWith(
            sequences = mapOf(
                "seq_a" to SequenceDefinition(),
                "seq_b" to SequenceDefinition(),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, actionRequiredOptions)

        result.skippedObjects.map { it.type to it.name }
            .filter { it.first == "sequence" }
            .shouldContainExactly(listOf("sequence" to "seq_a", "sequence" to "seq_b"))
    }
})
