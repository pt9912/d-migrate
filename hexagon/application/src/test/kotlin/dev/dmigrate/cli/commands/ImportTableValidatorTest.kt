package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.data.TargetColumn
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.sql.Types

class ImportTableValidatorTest : FunSpec({

    fun schemaWith(table: String, columns: Map<String, ColumnDefinition>): SchemaDefinition =
        SchemaDefinition(
            name = "S",
            version = "1",
            tables = mapOf(table to TableDefinition(columns = columns)),
        )

    fun schemaWithMany(tables: Map<String, Map<String, ColumnDefinition>>): SchemaDefinition =
        SchemaDefinition(
            name = "S",
            version = "1",
            tables = tables.mapValues { (_, cols) -> TableDefinition(columns = cols) },
        )

    test("happy path: all columns match nullability and type") {
        val schema = schemaWith(
            "users",
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "name" to ColumnDefinition(NeutralType.Text(), required = false),
            ),
        )
        val targets = listOf(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
            TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR),
        )
        ImportTableValidator.validateTargetTable(schema, "users", targets)
    }

    test("table not in schema") {
        val schema = schemaWith("users", mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(schema, "orders", emptyList())
        }
        ex.message!! shouldContain "Table 'orders' is not defined"
    }

    test("ambiguous match across multiple schema tables") {
        val schema = schemaWithMany(
            mapOf(
                "public.users" to mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
                "audit.users" to mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
            ),
        )
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(schema, "users", emptyList())
        }
        ex.message!! shouldContain "matches multiple tables"
    }

    test("missing target columns reported") {
        val schema = schemaWith(
            "users",
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "name" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val targets = listOf(TargetColumn("id", nullable = false, jdbcType = Types.INTEGER))
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(schema, "users", targets)
        }
        ex.message!! shouldContain "missing target columns: name"
    }

    test("unexpected target columns reported") {
        val schema = schemaWith("users", mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val targets = listOf(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
            TargetColumn("extra", nullable = true, jdbcType = Types.VARCHAR),
        )
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(schema, "users", targets)
        }
        ex.message!! shouldContain "unexpected target columns: extra"
    }

    test("missing and unexpected reported together") {
        val schema = schemaWith(
            "users",
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "name" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val targets = listOf(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER),
            TargetColumn("nick", nullable = true, jdbcType = Types.VARCHAR),
        )
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(schema, "users", targets)
        }
        ex.message!! shouldContain "missing target columns: name"
        ex.message!! shouldContain "unexpected target columns: nick"
    }

    test("nullability mismatch: schema requires NOT NULL, target NULLABLE") {
        val schema = schemaWith(
            "users",
            mapOf("id" to ColumnDefinition(NeutralType.Identifier(), required = true)),
        )
        val targets = listOf(TargetColumn("id", nullable = true, jdbcType = Types.INTEGER))
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(schema, "users", targets)
        }
        ex.message!! shouldContain "nullability mismatch"
        ex.message!! shouldContain "schema requires NOT NULL"
        ex.message!! shouldContain "target is NULLABLE"
    }

    test("nullability mismatch: schema NULLABLE, target NOT NULL") {
        val schema = schemaWith(
            "users",
            mapOf("id" to ColumnDefinition(NeutralType.Identifier(), required = false)),
        )
        val targets = listOf(TargetColumn("id", nullable = false, jdbcType = Types.INTEGER))
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(schema, "users", targets)
        }
        ex.message!! shouldContain "schema requires NULLABLE"
        ex.message!! shouldContain "target is NOT NULL"
    }

    test("type mismatch reported with describe() label") {
        val schema = schemaWith(
            "users",
            mapOf("id" to ColumnDefinition(NeutralType.Identifier(), required = true)),
        )
        val targets = listOf(
            TargetColumn("id", nullable = false, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
        )
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(schema, "users", targets)
        }
        ex.message!! shouldContain "type mismatch"
        ex.message!! shouldContain "identifier-compatible integer"
        ex.message!! shouldContain "VARCHAR"
    }

    test("type mismatch falls back to jdbcType when sqlTypeName is null") {
        val schema = schemaWith(
            "users",
            mapOf("id" to ColumnDefinition(NeutralType.Identifier(), required = true)),
        )
        val targets = listOf(TargetColumn("id", nullable = false, jdbcType = Types.VARCHAR))
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(schema, "users", targets)
        }
        ex.message!! shouldContain "jdbcType=${Types.VARCHAR}"
    }
})
