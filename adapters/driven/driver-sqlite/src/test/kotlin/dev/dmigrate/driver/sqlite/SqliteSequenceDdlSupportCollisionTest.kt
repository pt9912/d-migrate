package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.helperTableOptions
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.schemaWith
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.seqColumn
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.textColumn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * 0.9.7 SQLite-Sequence Phase B.3 E124-collision and naming-contract
 * coverage. Split out from [SqliteSequenceDdlSupportTest] to stay
 * under detekt's `LargeClass` threshold.
 */
class SqliteSequenceDdlSupportCollisionTest : FunSpec({

    // ── E124: reserved name collisions ─────────────────────────────

    test("HELPER_TABLE — user schema with dmg_sequences table emits E124 and blocks support emission") {
        val schema = schemaWith(
            tables = mapOf(
                "dmg_sequences" to TableDefinition(
                    columns = mapOf("custom" to textColumn(required = true)),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any { it.code == "E124" && it.objectName == "dmg_sequences" } shouldBe true
        result.statements.none {
            it.sql.contains("CREATE TABLE IF NOT EXISTS \"dmg_sequences\"")
        } shouldBe true
    }

    test("HELPER_TABLE — user-defined trigger name collision with support trigger emits E124") {
        val biName = SqliteSequenceNaming.beforeInsertTriggerName("orders", "order_number", "order_seq")
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
            triggers = mapOf(
                biName to TriggerDefinition(
                    timing = TriggerTiming.BEFORE,
                    event = TriggerEvent.INSERT,
                    table = "orders",
                    body = "-- user trigger",
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any { it.code == "E124" && it.objectName == biName } shouldBe true
    }

    test("HELPER_TABLE — user view named dmg_sequences emits E124 and blocks support emission") {
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
        ).copy(
            views = mapOf(
                "dmg_sequences" to ViewDefinition(query = "SELECT 1"),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any {
            it.code == "E124" &&
                it.objectName == "dmg_sequences" &&
                it.message.contains("(view)")
        } shouldBe true
        result.statements.none {
            it.sql.contains("CREATE TABLE IF NOT EXISTS \"dmg_sequences\"")
        } shouldBe true
    }

    test("HELPER_TABLE — user table with dmg_seq_*_bi name (canonical pattern) emits E124") {
        val collidingName = "dmg_seq_aaaaaaaaaaaaaaaa_bbbbbbbbbbbbbbbb_0123456789_bi"
        SqliteSequenceNaming.isCanonicalSupportTriggerName(collidingName) shouldBe true

        val schema = schemaWith(
            tables = mapOf(
                collidingName to TableDefinition(
                    columns = mapOf("x" to ColumnDefinition(type = NeutralType.Integer, required = true)),
                ),
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

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any {
            it.code == "E124" && it.objectName == collidingName && it.message.contains("(table)")
        } shouldBe true
    }

    test("HELPER_TABLE — user index with dmg_seq_*_ai name (canonical pattern) emits E124") {
        val collidingIndex = "dmg_seq_aaaaaaaaaaaaaaaa_bbbbbbbbbbbbbbbb_0123456789_ai"
        SqliteSequenceNaming.isCanonicalSupportTriggerName(collidingIndex) shouldBe true

        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(),
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = collidingIndex,
                            columns = listOf(IndexColumn("id")),
                        ),
                    ),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any {
            it.code == "E124" && it.objectName == collidingIndex && it.message.contains("(index)")
        } shouldBe true
    }

    test("HELPER_TABLE — user trigger with reserved support name emits E124") {
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
            triggers = mapOf(
                "dmg_sequences" to TriggerDefinition(
                    timing = TriggerTiming.BEFORE,
                    event = TriggerEvent.INSERT,
                    table = "orders",
                    body = "-- unrelated user trigger that just happens to be named like our support table",
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any {
            it.code == "E124" && it.objectName == "dmg_sequences" && it.message.contains("(trigger)")
        } shouldBe true
    }

    // ── naming canonical contract ──────────────────────────────────

    test("trigger naming — same hash across _bi/_ai variants and stable across runs") {
        val first = SqliteSequenceNaming.beforeInsertTriggerName("orders", "order_number", "order_seq")
        val second = SqliteSequenceNaming.beforeInsertTriggerName("orders", "order_number", "order_seq")
        first shouldBe second

        val biName = SqliteSequenceNaming.beforeInsertTriggerName("orders", "order_number", "order_seq")
        val aiName = SqliteSequenceNaming.afterInsertTriggerName("orders", "order_number", "order_seq")
        biName.removeSuffix("_bi") shouldBe aiName.removeSuffix("_ai")
    }

    test("trigger naming — sequence name participates in the hash so column-reuse stays disjoint") {
        val a = SqliteSequenceNaming.beforeInsertTriggerName("orders", "order_number", "seq_a")
        val b = SqliteSequenceNaming.beforeInsertTriggerName("orders", "order_number", "seq_b")
        a shouldNotBe b
    }

    test("trigger naming — long identifiers are truncated to a 55-char-max canonical name") {
        val longTable = "a_really_long_table_name_with_many_chars"
        val longColumn = "an_equally_long_column_name_for_seq"
        val name = SqliteSequenceNaming.beforeInsertTriggerName(longTable, longColumn, "seq")
        (name.length <= 55) shouldBe true
        name.startsWith("dmg_seq_") shouldBe true
    }

    test("isCanonicalSupportTriggerName — accepts both _bi and _ai variants") {
        SqliteSequenceNaming.isCanonicalSupportTriggerName(
            "dmg_seq_orders_order_number_a1b2c3d4e5_bi",
        ) shouldBe true
        SqliteSequenceNaming.isCanonicalSupportTriggerName(
            "dmg_seq_orders_order_number_a1b2c3d4e5_ai",
        ) shouldBe true
    }

    test("isCanonicalSupportTriggerName — rejects malformed shapes") {
        SqliteSequenceNaming.isCanonicalSupportTriggerName("dmg_seq_orders_x_short_bi") shouldBe false
        SqliteSequenceNaming.isCanonicalSupportTriggerName(
            "dmg_seq_orders_x_a1b2c3d4e5_xx",
        ) shouldBe false
        SqliteSequenceNaming.isCanonicalSupportTriggerName(
            "DMG_SEQ_ORDERS_X_A1B2C3D4E5_BI",
        ) shouldBe false
    }

    test("normalize — ASCII-only filter drops Unicode letters and digits") {
        // Plan §3.3 ASCII-only contract: non-ASCII characters collapse to
        // their ASCII residue so the resulting trigger name passes
        // CANONICAL_TRIGGER_PATTERN.
        SqliteSequenceNaming.normalize("Ä_orders") shouldBe "_orders"
        SqliteSequenceNaming.normalize("日付") shouldBe ""
        SqliteSequenceNaming.normalize("Ordër²_42") shouldBe "ordr_42"
        SqliteSequenceNaming.normalize("Plain_Name") shouldBe "plain_name"
    }

    test("trigger naming — non-ASCII identifiers produce canonical-shape names") {
        // The trigger name must still pass isCanonicalSupportTriggerName
        // even when one of the inputs collapses to an empty / minimal
        // ASCII residue, otherwise the collision scanner would miss
        // collisions against names the generator itself produced.
        val name = SqliteSequenceNaming.beforeInsertTriggerName("Ä_orders", "ordër", "seq")
        SqliteSequenceNaming.isCanonicalSupportTriggerName(name) shouldBe true
        name.startsWith("dmg_seq_") shouldBe true
        name.endsWith("_bi") shouldBe true
    }

    test("trigger naming — non-ASCII inputs whose normalised form collides still hash to distinct names") {
        // `Ä_orders` and `__orders` both normalise to `_orders`; the
        // hash deliberately uses the RAW identifiers so the two trigger
        // names remain distinct even when the ASCII residue collapses.
        val a = SqliteSequenceNaming.beforeInsertTriggerName("Ä_orders", "col", "seq")
        val b = SqliteSequenceNaming.beforeInsertTriggerName("__orders", "col", "seq")
        a shouldNotBe b
    }

    test("HELPER_TABLE — user-defined trigger with AFTER-collision (only aiName) emits E124") {
        // Defensive coverage of the aiName-only branch of the user-
        // trigger collision check in generateSupportTriggers.
        val aiName = SqliteSequenceNaming.afterInsertTriggerName("orders", "order_number", "order_seq")
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
            triggers = mapOf(
                aiName to TriggerDefinition(
                    timing = TriggerTiming.AFTER,
                    event = TriggerEvent.INSERT,
                    table = "orders",
                    body = "-- user-defined AFTER INSERT trigger",
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any {
            it.code == "E124" && it.objectName == aiName
        } shouldBe true
    }

    // ── rollback inversion ─────────────────────────────────────────

    test("HELPER_TABLE — rollback drops trigger pair and dmg_sequences in reverse order") {
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

        val rollback = SqliteDdlGenerator().generateRollback(schema, helperTableOptions)

        val sqls = rollback.statements.map { it.sql }
        sqls.any { it.startsWith("DROP TRIGGER IF EXISTS") && it.contains("_bi") } shouldBe true
        sqls.any { it.startsWith("DROP TRIGGER IF EXISTS") && it.contains("_ai") } shouldBe true
        sqls.any { it.startsWith("DROP TABLE IF EXISTS \"dmg_sequences\"") } shouldBe true

        val firstTriggerDrop = sqls.indexOfFirst { it.startsWith("DROP TRIGGER IF EXISTS") }
        val supportTableDrop = sqls.indexOfFirst { it.startsWith("DROP TABLE IF EXISTS \"dmg_sequences\"") }
        (firstTriggerDrop < supportTableDrop) shouldBe true
    }
})
