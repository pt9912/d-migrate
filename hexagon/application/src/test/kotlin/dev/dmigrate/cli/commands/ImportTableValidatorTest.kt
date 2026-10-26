package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
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

    fun schemaWithPk(
        table: String,
        columns: Map<String, ColumnDefinition>,
        primaryKey: List<String>,
    ): SchemaDefinition =
        SchemaDefinition(
            name = "S",
            version = "1",
            tables = mapOf(table to TableDefinition(columns = columns, primaryKey = primaryKey)),
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

    // Regression (Sample-DB Phase 3, Employees): reverse omits `required` for
    // PK columns, but the DB forces them NOT NULL via the PK clause. The
    // preflight must treat PK membership as effectively NOT NULL, so a schema
    // d-migrate reversed + generated itself round-trips on import.
    test("PK column without explicit required matches a NOT NULL target") {
        val schema = schemaWithPk(
            "employees",
            mapOf(
                "emp_no" to ColumnDefinition(NeutralType.Identifier(), required = false),
                "first_name" to ColumnDefinition(NeutralType.Text(), required = true),
            ),
            primaryKey = listOf("emp_no"),
        )
        val targets = listOf(
            TargetColumn("emp_no", nullable = false, jdbcType = Types.INTEGER),
            TargetColumn("first_name", nullable = false, jdbcType = Types.VARCHAR),
        )
        ImportTableValidator.validateTargetTable(schema, "employees", targets)
    }

    test("composite-PK columns without explicit required match NOT NULL targets") {
        val schema = schemaWithPk(
            "salaries",
            mapOf(
                "emp_no" to ColumnDefinition(NeutralType.Identifier(), required = false),
                "from_date" to ColumnDefinition(NeutralType.Date, required = false),
            ),
            primaryKey = listOf("emp_no", "from_date"),
        )
        val targets = listOf(
            TargetColumn("emp_no", nullable = false, jdbcType = Types.INTEGER),
            TargetColumn("from_date", nullable = false, jdbcType = Types.DATE),
        )
        ImportTableValidator.validateTargetTable(schema, "salaries", targets)
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

    // Issue #68: der JDBC-Treiber meldet an einer Domain-Spalte den Basistyp, nie den Namen der Domain.
    fun domainSchema(base: String, type: NeutralType = NeutralType.Enum(refType = "dd")) = SchemaDefinition(
        name = "S", version = "1",
        customTypes = mapOf("dd" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base)),
        tables = mapOf("t" to TableDefinition(columns = mapOf("d" to ColumnDefinition(type, required = false)))),
    )

    test("domain column: the schema type is the domain's base type (integer domain against int4)") {
        ImportTableValidator.validateTargetTable(
            domainSchema("integer"), "t", listOf(TargetColumn("d", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "int4")),
        )
        ImportTableValidator.validateTargetTable(
            domainSchema("text"), "t", listOf(TargetColumn("d", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "text")),
        )
    }

    test("domain column: a base type that does not match the target is still a mismatch, named by the base type") {
        val ex = shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(
                domainSchema("text"), "t",
                listOf(TargetColumn("d", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "int4")),
            )
        }
        ex.message shouldContain "type mismatch"
    }

    test("enum column (kind enum) keeps the enum rule: text-like target passes, integer target does not") {
        val enumSchema = SchemaDefinition(
            name = "S", version = "1",
            customTypes = mapOf("dd" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))),
            tables = mapOf("t" to TableDefinition(columns = mapOf("d" to ColumnDefinition(NeutralType.Enum(refType = "dd"))))),
        )
        ImportTableValidator.validateTargetTable(
            enumSchema, "t", listOf(TargetColumn("d", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "varchar")),
        )
        shouldThrow<ImportSchemaMismatchException> {
            ImportTableValidator.validateTargetTable(
                enumSchema, "t", listOf(TargetColumn("d", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "int4")),
            )
        }
    }
})
