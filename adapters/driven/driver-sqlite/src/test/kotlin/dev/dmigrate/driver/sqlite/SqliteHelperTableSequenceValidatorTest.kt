package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TableMetadata
import dev.dmigrate.driver.SqliteNamedSequenceMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SqliteHelperTableSequenceValidatorTest : FunSpec({

    fun schemaWith(tables: Map<String, TableDefinition>): SchemaDefinition =
        SchemaDefinition(
            name = "T",
            version = "1",
            tables = tables,
            sequences = mapOf("order_seq" to SequenceDefinition()),
        )

    fun seqColumn() = ColumnDefinition(
        type = NeutralType.BigInteger,
        default = DefaultValue.SequenceNextVal("order_seq"),
    )

    fun plainColumn() = ColumnDefinition(type = NeutralType.Text())

    // ── mode gate ──────────────────────────────────────────────────

    test("ACTION_REQUIRED mode → validator is a no-op even with PK + SequenceNextVal") {
        val schema = schemaWith(
            mapOf(
                "orders" to TableDefinition(
                    columns = mapOf("id" to seqColumn()),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.ACTION_REQUIRED)
            .shouldBeEmpty()
    }

    // ── E059: PK + SequenceNextVal ─────────────────────────────────

    test("HELPER_TABLE mode → single-column PK with SequenceNextVal default emits E059") {
        val schema = schemaWith(
            mapOf(
                "orders" to TableDefinition(
                    columns = mapOf("id" to seqColumn()),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        val errors = SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.HELPER_TABLE)

        errors.size shouldBe 1
        errors[0].code shouldBe "E059"
        errors[0].objectPath shouldBe "tables.orders.columns.id"
    }

    test("HELPER_TABLE mode → composite PK with one SequenceNextVal column emits exactly one E059") {
        val schema = schemaWith(
            mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "tenant_id" to plainColumn(),
                        "order_no" to seqColumn(),
                    ),
                    primaryKey = listOf("tenant_id", "order_no"),
                ),
            ),
        )

        val errors = SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.HELPER_TABLE)

        errors.map { it.objectPath }.shouldContainExactly("tables.orders.columns.order_no")
    }

    test("HELPER_TABLE mode → composite PK with multiple SequenceNextVal columns emits one E059 per offender") {
        val schema = schemaWith(
            mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "seq_a" to seqColumn(),
                        "seq_b" to seqColumn(),
                    ),
                    primaryKey = listOf("seq_a", "seq_b"),
                ),
            ),
        )

        val errors = SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.HELPER_TABLE)

        errors.size shouldBe 2
        errors.all { it.code == "E059" } shouldBe true
        errors.map { it.objectPath }.toSet() shouldBe setOf(
            "tables.orders.columns.seq_a",
            "tables.orders.columns.seq_b",
        )
    }

    // ── no-op paths ────────────────────────────────────────────────

    test("HELPER_TABLE mode → SequenceNextVal on a non-PK column is allowed") {
        val schema = schemaWith(
            mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to plainColumn(),
                        "order_no" to seqColumn(),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.HELPER_TABLE).shouldBeEmpty()
    }

    test("HELPER_TABLE mode → PK column without SequenceNextVal default is allowed") {
        val schema = schemaWith(
            mapOf(
                "orders" to TableDefinition(
                    columns = mapOf("id" to plainColumn()),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.HELPER_TABLE).shouldBeEmpty()
    }

    test("HELPER_TABLE mode → table without a primary key is skipped") {
        val schema = schemaWith(
            mapOf(
                "orders" to TableDefinition(
                    columns = mapOf("order_no" to seqColumn()),
                    primaryKey = emptyList(),
                ),
            ),
        )

        SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.HELPER_TABLE).shouldBeEmpty()
    }

    test("HELPER_TABLE mode → empty schema yields no errors") {
        SqliteHelperTableSequenceValidator.validate(
            SchemaDefinition(name = "T", version = "1"),
            SqliteNamedSequenceMode.HELPER_TABLE,
        ).shouldBeEmpty()
    }

    test("HELPER_TABLE mode → PK references a non-existent column → no false positive") {
        // The structure validator owns the "PK column not in columns map"
        // diagnostic; this validator must not trip on the missing entry.
        val schema = schemaWith(
            mapOf(
                "orders" to TableDefinition(
                    columns = mapOf("real_col" to plainColumn()),
                    primaryKey = listOf("phantom_col"),
                ),
            ),
        )

        SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.HELPER_TABLE).shouldBeEmpty()
    }

    // ── isolation across tables ────────────────────────────────────

    test("HELPER_TABLE mode → errors are localized per table; clean tables stay clean") {
        val schema = schemaWith(
            mapOf(
                "good" to TableDefinition(
                    columns = mapOf("id" to plainColumn()),
                    primaryKey = listOf("id"),
                ),
                "bad" to TableDefinition(
                    columns = mapOf("id" to seqColumn()),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        val errors = SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.HELPER_TABLE)

        errors.map { it.objectPath }.shouldContainExactly("tables.bad.columns.id")
    }

    // ── WITHOUT ROWID coexistence with PK rule (E057 stays in the generator) ──

    test("HELPER_TABLE mode → WITHOUT ROWID table still surfaces E059 for PK + SequenceNextVal") {
        // E057 (WITHOUT ROWID + SequenceNextVal) is generator-time per
        // plan-doc §3.5; the PK rule here is independent and must fire
        // regardless of the rowid policy.
        val schema = schemaWith(
            mapOf(
                "orders" to TableDefinition(
                    columns = mapOf("id" to seqColumn()),
                    primaryKey = listOf("id"),
                    metadata = TableMetadata(withoutRowid = true),
                ),
            ),
        )

        val errors = SqliteHelperTableSequenceValidator.validate(schema, SqliteNamedSequenceMode.HELPER_TABLE)
        errors.size shouldBe 1
        errors[0].code shouldBe "E059"
    }
})
