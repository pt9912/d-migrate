package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.actionRequiredOptions
import dev.dmigrate.driver.sqlite.SqliteSequenceTestFixtures.helperTableOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 0.9.7 Phase F1: pins the Plan §5.2 rollback-preflight contract
 * (E058 + E060). The full live-DB-execution behaviour against
 * `:memory:` SQLite lives in
 * `SqliteSequenceRollbackPreflightIntegrationTest`.
 */
class SqliteSequenceRollbackPreflightTest : FunSpec({

    fun schemaWith(): SchemaDefinition = SchemaDefinition(
        name = "rb",
        version = "1.0.0",
        sequences = mapOf("order_seq" to SequenceDefinition()),
        tables = mapOf(
            "orders" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "order_number" to ColumnDefinition(
                        type = NeutralType.BigInteger,
                        default = DefaultValue.SequenceNextVal("order_seq"),
                    ),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    test("HELPER_TABLE — rollback stream prefixes a CREATE TEMP TABLE preflight for E058") {
        val schema = schemaWith()
        val rollback = SqliteDdlGenerator().generateRollback(schema, helperTableOptions)

        val sqls = rollback.statements.map { it.sql }
        val createIdx = sqls.indexOfFirst { it.contains("CREATE TEMP TABLE \"_dmg_pf_e058\"") }
        val insertIdx = sqls.indexOfFirst { it.contains("INSERT INTO \"_dmg_pf_e058\"") }
        val dropIdx = sqls.indexOfFirst { it.contains("DROP TABLE \"_dmg_pf_e058\"") }
        (createIdx >= 0) shouldBe true
        (insertIdx > createIdx) shouldBe true
        (dropIdx > insertIdx) shouldBe true

        // The CHECK constraint carries the E058 code as its name so the
        // JDBC error message at runtime surfaces it.
        sqls[createIdx] shouldContain "E058_external_dmg_sequences_refs"
        // The user-trigger canonical pattern is explicitly excluded so
        // `dmg_seq_foo_bar_bi` user triggers don't bypass the scan.
        sqls[insertIdx] shouldContain "NOT GLOB 'dmg_seq_*_bi'"
        sqls[insertIdx] shouldContain "NOT GLOB 'dmg_seq_*_ai'"
    }

    test("HELPER_TABLE — rollback stream emits an E060 attached-DB check") {
        val schema = schemaWith()
        val rollback = SqliteDdlGenerator().generateRollback(schema, helperTableOptions)

        val sqls = rollback.statements.map { it.sql }
        sqls.any { it.contains("E060_attached_databases_detected") } shouldBe true
        sqls.any { it.contains("pragma_database_list") } shouldBe true
    }

    test("HELPER_TABLE — preflight stands BEFORE every DROP TRIGGER / DROP TABLE \"dmg_sequences\"") {
        val schema = schemaWith()
        val rollback = SqliteDdlGenerator().generateRollback(schema, helperTableOptions)

        val sqls = rollback.statements.map { it.sql }
        val firstDmgDrop = sqls.indexOfFirst {
            it.startsWith("DROP TRIGGER") || it.contains("DROP TABLE IF EXISTS \"dmg_sequences\"")
        }
        val firstE058 = sqls.indexOfFirst { it.contains("E058_external_dmg_sequences_refs") }
        val firstE060 = sqls.indexOfFirst { it.contains("E060_attached_databases_detected") }
        (firstE058 < firstDmgDrop) shouldBe true
        (firstE060 < firstDmgDrop) shouldBe true
    }

    test("ACTION_REQUIRED — no preflight emitted (rollback has nothing to drop)") {
        val schema = schemaWith()
        val rollback = SqliteDdlGenerator().generateRollback(schema, actionRequiredOptions)

        rollback.statements.none { it.sql.contains("E058_external_") } shouldBe true
        rollback.statements.none { it.sql.contains("E060_attached_") } shouldBe true
    }

    test("HELPER_TABLE — fully empty schema produces no preflight (no support objects emitted)") {
        val emptySchema = SchemaDefinition(name = "e", version = "1.0.0")
        val rollback = SqliteDdlGenerator().generateRollback(emptySchema, helperTableOptions)

        rollback.statements.map { it.sql }.none {
            it.contains("E058_") || it.contains("E060_")
        } shouldBe true
    }

    test("HELPER_TABLE — preflight does not pull in CREATE TRIGGER / BEGIN…END (SQLite rejects RAISE outside triggers)") {
        val schema = schemaWith()
        val rollback = SqliteDdlGenerator().generateRollback(schema, helperTableOptions)

        val preflightSqls = rollback.statements
            .map { it.sql }
            .filter { it.contains("_dmg_pf_e058") || it.contains("_dmg_pf_e060") }
        for (sql in preflightSqls) {
            sql shouldNotContain "RAISE("
            sql shouldNotContain "BEGIN"
        }
    }
})
