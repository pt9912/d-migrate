package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TableMetadata
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.actionRequiredOptions
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.helperTableOptions
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.schemaWith
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.seqColumn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 0.9.7 SQLite-Sequence Phase B.3 trigger-pair coverage: canonical
 * `_bi`/`_ai` SQL bodies, NOT NULL / CHECK-IS-NOT-NULL suppression,
 * lossy-NULL warnings, WITHOUT ROWID gate. Split out from
 * [SqliteSequenceDdlSupportTest] to stay under detekt's
 * `LargeClass` threshold.
 */
class SqliteSequenceDdlSupportTriggerTest : FunSpec({

    // ── trigger pair canonical shape ───────────────────────────────

    test("HELPER_TABLE — sequence-backed column emits _bi/_ai trigger pair with canonical names") {
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

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val biName = SqliteSequenceNaming.beforeInsertTriggerName("orders", "order_number", "order_seq")
        val aiName = SqliteSequenceNaming.afterInsertTriggerName("orders", "order_number", "order_seq")

        biName shouldContain "dmg_seq_orders_order_number_"
        biName shouldContain "_bi"
        aiName shouldContain "_ai"
        biName.removeSuffix("_bi") shouldBe aiName.removeSuffix("_ai")

        val triggerSqls = result.statements.map { it.sql }.filter { it.contains("CREATE TRIGGER") }
        triggerSqls.any { it.contains("\"$biName\"") && it.contains("BEFORE INSERT ON \"orders\"") } shouldBe true
        triggerSqls.any { it.contains("\"$aiName\"") && it.contains("AFTER INSERT ON \"orders\"") } shouldBe true
    }

    test("HELPER_TABLE — _bi trigger body carries overflow-safe boundary check and exhausted flag") {
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

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val biSql = result.statements.map { it.sql }
            .first { it.contains("BEFORE INSERT ON \"orders\"") }
        biSql shouldContain "WHEN NEW.\"order_number\" IS NULL"
        biSql shouldContain "RAISE(ABORT, 'dmg_sequences: sequence row order_seq not found')"
        biSql shouldContain "RAISE(ABORT, 'dmg_sequences: sequence order_seq exhausted')"
        biSql shouldContain "COALESCE(\"max_value\", 9223372036854775807)"
        biSql shouldContain "COALESCE(\"min_value\", -9223372036854775808)"
        biSql shouldContain "\"last_returned_value\" = \"next_value\""
        biSql shouldContain "\"exhausted\" = CASE"
    }

    test("HELPER_TABLE — _ai trigger writes last_returned_value via ROWID") {
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

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val aiSql = result.statements.map { it.sql }
            .first { it.contains("AFTER INSERT ON \"orders\"") }
        aiSql shouldContain "UPDATE \"orders\""
        aiSql shouldContain "SET \"order_number\" = ("
        aiSql shouldContain "SELECT \"last_returned_value\" FROM \"dmg_sequences\""
        aiSql shouldContain "WHERE ROWID = NEW.ROWID"
    }

    test("HELPER_TABLE — sequence-backed column emits W115 lossy NULL semantics warning") {
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

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any {
            it.code == "W115" &&
                it.objectName == "orders.order_number" &&
                it.message.contains("lossy SQLite trigger semantics")
        } shouldBe true
    }

    // ── NOT NULL suppression (W119) ────────────────────────────────

    test("HELPER_TABLE — required SequenceNextVal column suppresses NOT NULL and emits W119") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(required = true),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val tableSql = result.statements.map { it.sql }
            .first { it.startsWith("CREATE TABLE \"orders\"") }
        tableSql shouldContain "\"order_number\" INTEGER"
        tableSql shouldNotContain "\"order_number\" INTEGER NOT NULL"

        result.notes.count {
            it.code == "W119" &&
                it.objectName == "orders.order_number" &&
                it.message.contains("NOT NULL constraint suppressed")
        } shouldBe 1
    }

    test("HELPER_TABLE — non-required SequenceNextVal column does NOT emit W119") {
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

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.none {
            it.code == "W119" && it.message.contains("NOT NULL constraint suppressed")
        } shouldBe true
    }

    test("ACTION_REQUIRED — required SequenceNextVal column keeps NOT NULL emission") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(required = true),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, actionRequiredOptions)

        val tableSql = result.statements.map { it.sql }
            .first { it.startsWith("CREATE TABLE \"orders\"") }
        tableSql shouldContain "\"order_number\" INTEGER NOT NULL"
        result.notes.none { it.code == "W119" } shouldBe true
    }

    // ── CHECK IS NOT NULL suppression ──────────────────────────────

    test("HELPER_TABLE — CHECK IS NOT NULL on sequence column is suppressed and emits W119") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(),
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "chk_order_number_not_null",
                            type = ConstraintType.CHECK,
                            expression = "\"order_number\" IS NOT NULL",
                        ),
                    ),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val tableSql = result.statements.map { it.sql }
            .first { it.startsWith("CREATE TABLE \"orders\"") }
        tableSql shouldNotContain "chk_order_number_not_null"
        tableSql shouldNotContain "\"order_number\" IS NOT NULL"

        result.notes.any {
            it.code == "W119" &&
                it.objectName == "orders.order_number" &&
                it.message.contains("CHECK constraint suppressed")
        } shouldBe true
    }

    test("HELPER_TABLE — CHECK 'NOT (col IS NULL)' on sequence column is also suppressed") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(),
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "chk_not_null_alt",
                            type = ConstraintType.CHECK,
                            expression = "NOT (\"order_number\" IS NULL)",
                        ),
                    ),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val tableSql = result.statements.map { it.sql }
            .first { it.startsWith("CREATE TABLE \"orders\"") }
        tableSql shouldNotContain "chk_not_null_alt"
        result.notes.any { it.code == "W119" && it.message.contains("CHECK constraint suppressed") } shouldBe true
    }

    test("HELPER_TABLE — combined CHECK with IS NOT NULL is NOT suppressed (preserves other predicate)") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(),
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "chk_positive_and_present",
                            type = ConstraintType.CHECK,
                            expression = "\"order_number\" > 0 AND \"order_number\" IS NOT NULL",
                        ),
                    ),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val tableSql = result.statements.map { it.sql }
            .first { it.startsWith("CREATE TABLE \"orders\"") }
        tableSql shouldContain "chk_positive_and_present"
        tableSql shouldContain "> 0"
        result.notes.none {
            it.code == "W119" && it.message.contains("CHECK constraint suppressed")
        } shouldBe true
    }

    test("HELPER_TABLE — unrelated CHECK on sequence-bearing table stays in the output") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(),
                        "amount" to ColumnDefinition(type = NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "chk_positive_amount",
                            type = ConstraintType.CHECK,
                            expression = "\"amount\" >= 0",
                        ),
                    ),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val tableSql = result.statements.map { it.sql }
            .first { it.startsWith("CREATE TABLE \"orders\"") }
        tableSql shouldContain "chk_positive_amount"
        tableSql shouldContain "\"amount\" >= 0"
    }

    // ── E057 WITHOUT ROWID ─────────────────────────────────────────

    test("HELPER_TABLE — sequence column on WITHOUT ROWID table emits E057 and no trigger pair") {
        val schema = schemaWith(
            tables = mapOf(
                "kv" to TableDefinition(
                    columns = mapOf(
                        "k" to ColumnDefinition(type = NeutralType.Text(), required = true),
                        "order_number" to seqColumn(),
                    ),
                    primaryKey = listOf("k"),
                    metadata = TableMetadata(withoutRowid = true),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any {
            it.code == "E057" &&
                it.objectName == "kv.order_number" &&
                it.message.contains("WITHOUT ROWID")
        } shouldBe true
        result.statements.none { it.sql.contains("BEFORE INSERT ON \"kv\"") } shouldBe true
        result.statements.none { it.sql.contains("AFTER INSERT ON \"kv\"") } shouldBe true
    }

    // ── multi-sequence on same table ───────────────────────────────

    test("HELPER_TABLE — two sequence-backed columns on the same table emit two trigger pairs") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn("order_seq", required = true),
                        "invoice_number" to seqColumn("invoice_seq"),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            sequences = mapOf(
                "order_seq" to SequenceDefinition(),
                "invoice_seq" to SequenceDefinition(),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val orderBi = SqliteSequenceNaming.beforeInsertTriggerName("orders", "order_number", "order_seq")
        val orderAi = SqliteSequenceNaming.afterInsertTriggerName("orders", "order_number", "order_seq")
        val invoiceBi = SqliteSequenceNaming.beforeInsertTriggerName("orders", "invoice_number", "invoice_seq")
        val invoiceAi = SqliteSequenceNaming.afterInsertTriggerName("orders", "invoice_number", "invoice_seq")
        setOf(orderBi, orderAi, invoiceBi, invoiceAi).size shouldBe 4

        val triggerSqls = result.statements.map { it.sql }.filter { it.contains("CREATE TRIGGER") }
        triggerSqls.any { it.contains("\"$orderBi\"") } shouldBe true
        triggerSqls.any { it.contains("\"$orderAi\"") } shouldBe true
        triggerSqls.any { it.contains("\"$invoiceBi\"") } shouldBe true
        triggerSqls.any { it.contains("\"$invoiceAi\"") } shouldBe true

        result.notes.count { it.code == "W115" } shouldBe 2
        result.notes.count { it.code == "W119" && it.objectName == "orders.order_number" } shouldBe 1
        result.notes.count { it.code == "W119" && it.objectName == "orders.invoice_number" } shouldBe 0

        result.statements.count { it.sql.startsWith("INSERT INTO \"dmg_sequences\"") } shouldBe 2
    }

    // ── W121 conflict-gap INFO ─────────────────────────────────────

    test("HELPER_TABLE — sequence-backed column emits W121 conflict-gap INFO") {
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

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val w121 = result.notes.singleOrNull {
            it.code == "W121" && it.objectName == "orders.order_number"
        }
        w121 shouldNotBe null
        w121!!.type shouldBe NoteType.INFO
        w121.message shouldContain "ON CONFLICT DO UPDATE/DO NOTHING"
        w121.message shouldContain "INSERT OR IGNORE"
        w121.message shouldContain "ABORT/ROLLBACK"
    }

    test("ACTION_REQUIRED — no W121 conflict-gap INFO emitted (no trigger pair)") {
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

        result.notes.none { it.code == "W121" } shouldBe true
    }

    // ── W122 UPDATE-trigger interference ──────────────────────────

    test("HELPER_TABLE — UPDATE trigger on host table raises W122 per sequence column") {
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
                "trg_audit_orders_update" to TriggerDefinition(
                    timing = TriggerTiming.AFTER,
                    event = TriggerEvent.UPDATE,
                    table = "orders",
                    body = "-- user UPDATE trigger",
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val w122 = result.notes.singleOrNull {
            it.code == "W122" && it.objectName == "orders.order_number"
        }
        w122 shouldNotBe null
        w122!!.type shouldBe NoteType.WARNING
        w122.message shouldContain "recursive_triggers"
    }

    test("HELPER_TABLE — no W122 when only INSERT/DELETE triggers exist on host table") {
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
                "trg_audit_insert" to TriggerDefinition(
                    timing = TriggerTiming.AFTER,
                    event = TriggerEvent.INSERT,
                    table = "orders",
                    body = "-- user INSERT trigger",
                ),
                "trg_audit_delete" to TriggerDefinition(
                    timing = TriggerTiming.AFTER,
                    event = TriggerEvent.DELETE,
                    table = "orders",
                    body = "-- user DELETE trigger",
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.none { it.code == "W122" } shouldBe true
    }

    test("HELPER_TABLE — BEFORE UPDATE trigger on host table raises W122 (timing-insensitive)") {
        // Plan §3.4 line 619: BEFORE/AFTER UPDATE-Trigger feuern beide
        // bei recursive_triggers=ON. Code-Detektion ist deshalb
        // timing-insensitive (event == UPDATE genuegt).
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
                "trg_pre_update" to TriggerDefinition(
                    timing = TriggerTiming.BEFORE,
                    event = TriggerEvent.UPDATE,
                    table = "orders",
                    body = "-- user BEFORE UPDATE trigger",
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.any { it.code == "W122" && it.objectName == "orders.order_number" } shouldBe true
    }

    test("HELPER_TABLE — two sequence columns + one UPDATE trigger emit two W122 notes") {
        // One W122 per sequence column, regardless of how many UPDATE
        // triggers there are on the table.
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn("order_seq"),
                        "invoice_number" to seqColumn("invoice_seq"),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            sequences = mapOf(
                "order_seq" to SequenceDefinition(),
                "invoice_seq" to SequenceDefinition(),
            ),
            triggers = mapOf(
                "trg_audit_update" to TriggerDefinition(
                    timing = TriggerTiming.AFTER,
                    event = TriggerEvent.UPDATE,
                    table = "orders",
                    body = "-- user UPDATE trigger",
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.count { it.code == "W122" } shouldBe 2
        result.notes.any { it.code == "W122" && it.objectName == "orders.order_number" } shouldBe true
        result.notes.any { it.code == "W122" && it.objectName == "orders.invoice_number" } shouldBe true
    }

    test("HELPER_TABLE — UPDATE trigger on a different table does not raise W122") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(),
                    ),
                    primaryKey = listOf("id"),
                ),
                "audit_log" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
            triggers = mapOf(
                "trg_audit_log_update" to TriggerDefinition(
                    timing = TriggerTiming.AFTER,
                    event = TriggerEvent.UPDATE,
                    table = "audit_log",
                    body = "-- UPDATE trigger on a different table",
                ),
            ),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        result.notes.none { it.code == "W122" } shouldBe true
    }

    // ── note type semantics ────────────────────────────────────────

    test("HELPER_TABLE — W117 is a WARNING, W115/W119 are WARNINGs") {
        val schema = schemaWith(
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "order_number" to seqColumn(required = true),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

        val result = SqliteDdlGenerator().generate(schema, helperTableOptions)

        val byCode = result.notes.groupBy { it.code }
        byCode["W115"]!!.first().type shouldBe NoteType.WARNING
        byCode["W117"]!!.first().type shouldBe NoteType.WARNING
        byCode["W119"]!!.first().type shouldBe NoteType.WARNING
    }
})
