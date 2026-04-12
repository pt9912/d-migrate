package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.sql.Types

class DataImportSchemaPreflightTest : FunSpec({

    fun compatibilitySchema(): SchemaDefinition =
        SchemaDefinition(
            name = "Compatibility",
            version = "1.0.0",
            tables = mapOf(
                "compat" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                        "name" to ColumnDefinition(NeutralType.Text()),
                        "code" to ColumnDefinition(NeutralType.Char(2)),
                        "count_i" to ColumnDefinition(NeutralType.Integer),
                        "count_s" to ColumnDefinition(NeutralType.SmallInt),
                        "count_b" to ColumnDefinition(NeutralType.BigInteger),
                        "ratio" to ColumnDefinition(NeutralType.Float(FloatPrecision.SINGLE)),
                        "score" to ColumnDefinition(NeutralType.Float(FloatPrecision.DOUBLE)),
                        "price" to ColumnDefinition(NeutralType.Decimal(10, 2)),
                        "flag" to ColumnDefinition(NeutralType.BooleanType),
                        "created_at" to ColumnDefinition(NeutralType.DateTime()),
                        "birth_date" to ColumnDefinition(NeutralType.Date),
                        "wake_time" to ColumnDefinition(NeutralType.Time),
                        "uuid_col" to ColumnDefinition(NeutralType.Uuid),
                        "json_col" to ColumnDefinition(NeutralType.Json),
                        "xml_col" to ColumnDefinition(NeutralType.Xml),
                        "blob_col" to ColumnDefinition(NeutralType.Binary),
                        "status" to ColumnDefinition(NeutralType.Enum(values = listOf("A", "B"))),
                        "tags" to ColumnDefinition(NeutralType.Array("text")),
                        "email" to ColumnDefinition(NeutralType.Email, required = true),
                    ),
                )
            ),
        )

    test("prepare returns schema and preserves non-directory input") {
        val schemaFile = Files.createTempFile("dmigrate-prepare-single-", ".yaml")
        val dataFile = Files.createTempFile("dmigrate-prepare-single-", ".json")
        Files.writeString(dataFile, """[{"id":1}]""")
        Files.writeString(
            schemaFile,
            """
            schema_format: "1.0"
            name: "SingleFile"
            version: "1.0.0"
            tables:
              users:
                columns:
                  id:
                    type: identifier
            """.trimIndent()
        )

        val input = ImportInput.SingleFile("users", dataFile)
        val result = DataImportSchemaPreflight.prepare(
            schemaPath = schemaFile,
            input = input,
            format = DataExportFormat.JSON,
        )

        result.input shouldBe input
        result.schema!!.tables.keys shouldBe setOf("users")

        Files.deleteIfExists(dataFile)
        Files.deleteIfExists(schemaFile)
    }

    test("prepare detects circular dependencies declared via FOREIGN_KEY constraints") {
        val importDir = Files.createTempDirectory("dmigrate-constraint-cycle-")
        Files.writeString(importDir.resolve("a.json"), """[{"id":1}]""")
        Files.writeString(importDir.resolve("b.json"), """[{"id":1}]""")
        val schemaFile = Files.createTempFile("dmigrate-constraint-cycle-", ".yaml")
        Files.writeString(
            schemaFile,
            """
            schema_format: "1.0"
            name: "ConstraintCycle"
            version: "1.0.0"
            tables:
              a:
                columns:
                  id:
                    type: identifier
                  b_id:
                    type: integer
                constraints:
                  - name: fk_a_b
                    type: foreign_key
                    columns: [b_id]
                    references:
                      table: b
                      columns: [id]
              b:
                columns:
                  id:
                    type: identifier
                  a_id:
                    type: integer
                constraints:
                  - name: fk_b_a
                    type: foreign_key
                    columns: [a_id]
                    references:
                      table: a
                      columns: [id]
            """.trimIndent()
        )

        val ex = shouldThrow<ImportPreflightException> {
            DataImportSchemaPreflight.prepare(
                schemaPath = schemaFile,
                input = ImportInput.Directory(importDir),
                format = DataExportFormat.JSON,
            )
        }

        ex.message!! shouldContain "dependency cycle"
        ex.message!! shouldContain "a.b_id -> b.id"
        ex.message!! shouldContain "b.a_id -> a.id"

        Files.deleteIfExists(importDir.resolve("a.json"))
        Files.deleteIfExists(importDir.resolve("b.json"))
        Files.deleteIfExists(schemaFile)
        Files.deleteIfExists(importDir)
    }

    test("prepare wraps directory listing failures") {
        val schemaFile = Files.createTempFile("dmigrate-listing-schema-", ".yaml")
        val sourceFile = Files.createTempFile("dmigrate-not-a-dir-", ".json")
        Files.writeString(sourceFile, """[{"id":1}]""")
        Files.writeString(
            schemaFile,
            """
            schema_format: "1.0"
            name: "Listing"
            version: "1.0.0"
            tables:
              users:
                columns:
                  id:
                    type: identifier
            """.trimIndent()
        )

        val ex = shouldThrow<ImportPreflightException> {
            DataImportSchemaPreflight.prepare(
                schemaPath = schemaFile,
                input = ImportInput.Directory(sourceFile),
                format = DataExportFormat.JSON,
            )
        }

        ex.message!! shouldContain "Failed to list directory import source"

        Files.deleteIfExists(sourceFile)
        Files.deleteIfExists(schemaFile)
    }

    test("prepare rejects filtered tables without matching files") {
        val importDir = Files.createTempDirectory("dmigrate-filter-miss-")
        Files.writeString(importDir.resolve("users.json"), """[{"id":1}]""")
        val schemaFile = Files.createTempFile("dmigrate-filter-schema-", ".yaml")
        Files.writeString(
            schemaFile,
            """
            schema_format: "1.0"
            name: "Filter"
            version: "1.0.0"
            tables:
              users:
                columns:
                  id:
                    type: identifier
            """.trimIndent()
        )

        val ex = shouldThrow<ImportPreflightException> {
            DataImportSchemaPreflight.prepare(
                schemaPath = schemaFile,
                input = ImportInput.Directory(importDir, tableFilter = listOf("orders")),
                format = DataExportFormat.JSON,
            )
        }

        ex.message!! shouldContain "Directory import filter references tables without matching files"
        ex.message!! shouldContain "orders"

        Files.deleteIfExists(importDir.resolve("users.json"))
        Files.deleteIfExists(importDir)
        Files.deleteIfExists(schemaFile)
    }

    test("validateTargetTable rejects incompatible target types and nullability") {
        val schemaFile = Files.createTempFile("dmigrate-preflight-schema-", ".yaml")
        val dataFile = Files.createTempFile("dmigrate-users-", ".json")
        Files.writeString(dataFile, """[{"id":1}]""")
        Files.writeString(
            schemaFile,
            """
            schema_format: "1.0"
            name: "Mismatch"
            version: "1.0.0"
            tables:
              users:
                columns:
                  id:
                    type: identifier
                  email:
                    type: email
                    required: true
            """.trimIndent()
        )
        val schema = DataImportSchemaPreflight.prepare(
            schemaPath = schemaFile,
            input = ImportInput.SingleFile("users", dataFile),
            format = DataExportFormat.JSON,
        ).schema!!

        val ex = shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schema,
                table = "users",
                targetColumns = listOf(
                    TargetColumn("id", nullable = false, jdbcType = java.sql.Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("email", nullable = true, jdbcType = java.sql.Types.INTEGER, sqlTypeName = "INTEGER"),
                ),
            )
        }

        ex.message!! shouldContain "does not match the provided --schema"
        ex.message!! shouldContain "nullability mismatch"
        ex.message!! shouldContain "type mismatch"

        Files.deleteIfExists(dataFile)
        Files.deleteIfExists(schemaFile)
    }

    test("validateTargetTable accepts supported neutral type matrix") {
        shouldNotThrowAny {
            DataImportSchemaPreflight.validateTargetTable(
                schema = compatibilitySchema(),
                table = "compat",
                targetColumns = listOf(
                    TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("code", nullable = true, jdbcType = Types.CHAR, sqlTypeName = "CHAR"),
                    TargetColumn("count_i", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("count_s", nullable = true, jdbcType = Types.SMALLINT, sqlTypeName = "SMALLINT"),
                    TargetColumn("count_b", nullable = true, jdbcType = Types.BIGINT, sqlTypeName = "BIGINT"),
                    TargetColumn("ratio", nullable = true, jdbcType = Types.REAL, sqlTypeName = "REAL"),
                    TargetColumn("score", nullable = true, jdbcType = Types.DOUBLE, sqlTypeName = "DOUBLE"),
                    TargetColumn("price", nullable = true, jdbcType = Types.NUMERIC, sqlTypeName = "NUMERIC"),
                    TargetColumn("flag", nullable = true, jdbcType = Types.BIT, sqlTypeName = "BIT(1)"),
                    TargetColumn("created_at", nullable = true, jdbcType = Types.TIMESTAMP, sqlTypeName = "TIMESTAMP"),
                    TargetColumn("birth_date", nullable = true, jdbcType = Types.DATE, sqlTypeName = "DATE"),
                    TargetColumn("wake_time", nullable = true, jdbcType = Types.TIME, sqlTypeName = "TIME"),
                    TargetColumn("uuid_col", nullable = true, jdbcType = Types.OTHER, sqlTypeName = "UUID"),
                    TargetColumn("json_col", nullable = true, jdbcType = Types.OTHER, sqlTypeName = "JSONB"),
                    TargetColumn("xml_col", nullable = true, jdbcType = Types.SQLXML, sqlTypeName = "XML"),
                    TargetColumn("blob_col", nullable = true, jdbcType = Types.BLOB, sqlTypeName = "BLOB"),
                    TargetColumn("status", nullable = true, jdbcType = Types.OTHER, sqlTypeName = "ENUM"),
                    TargetColumn("tags", nullable = true, jdbcType = Types.ARRAY, sqlTypeName = "TEXT[]"),
                    TargetColumn("email", nullable = false, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                ),
            )
        }
    }

    test("validateTargetTable rejects Types.OTHER cross-contamination between UUID, JSON, XML, and Enum") {
        fun schemaWith(colName: String, type: NeutralType): SchemaDefinition =
            SchemaDefinition(
                name = "OtherCheck", version = "1.0.0",
                tables = mapOf(
                    "t" to TableDefinition(columns = linkedMapOf(colName to ColumnDefinition(type)))
                ),
            )

        // Schema=UUID, target=JSONB → must reject
        shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schemaWith("col", NeutralType.Uuid),
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "jsonb")),
            )
        }.message!! shouldContain "type mismatch"

        // Schema=UUID, target=custom enum → must reject
        shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schemaWith("col", NeutralType.Uuid),
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "mood")),
            )
        }.message!! shouldContain "type mismatch"

        // Schema=JSON, target=UUID → must reject
        shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schemaWith("col", NeutralType.Json),
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "uuid")),
            )
        }.message!! shouldContain "type mismatch"

        // Schema=Enum, target=UUID → must reject
        shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schemaWith("col", NeutralType.Enum(values = listOf("A", "B"))),
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "uuid")),
            )
        }.message!! shouldContain "type mismatch"

        // Schema=Enum, target=JSONB → must reject
        shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schemaWith("col", NeutralType.Enum(values = listOf("A", "B"))),
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "jsonb")),
            )
        }.message!! shouldContain "type mismatch"

        // Schema=XML, target=UUID → must reject
        shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schemaWith("col", NeutralType.Xml),
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "uuid")),
            )
        }.message!! shouldContain "type mismatch"

        // Schema=UUID, target=UUID (Types.OTHER) → must still accept
        shouldNotThrowAny {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schemaWith("col", NeutralType.Uuid),
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "uuid")),
            )
        }

        // Schema=JSON, target=JSONB (Types.OTHER) → must still accept
        shouldNotThrowAny {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schemaWith("col", NeutralType.Json),
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "jsonb")),
            )
        }

        // Schema=Enum (custom PG type), target=mood (Types.OTHER) → must accept
        shouldNotThrowAny {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schemaWith("col", NeutralType.Enum(values = listOf("happy", "sad"))),
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "mood")),
            )
        }
    }

    test("validateTargetTable rejects Enum with mismatched refType") {
        val schema = SchemaDefinition(
            name = "RefType", version = "1.0.0",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = linkedMapOf(
                        "col" to ColumnDefinition(NeutralType.Enum(values = listOf("A"), refType = "status"))
                    )
                ),
            ),
        )

        // refType=status, target sqlTypeName=mood → must reject
        shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schema,
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "mood")),
            )
        }.message!! shouldContain "type mismatch"

        // refType=status, target sqlTypeName=status → must accept
        shouldNotThrowAny {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schema,
                table = "t",
                targetColumns = listOf(TargetColumn("col", true, Types.OTHER, "status")),
            )
        }
    }

    test("validateTargetTable reports broad type mismatches across neutral types") {
        val ex = shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = compatibilitySchema(),
                table = "compat",
                targetColumns = listOf(
                    TargetColumn("id", nullable = false, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("name", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("code", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("count_i", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("count_s", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("count_b", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("ratio", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("score", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("price", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("flag", nullable = true, jdbcType = Types.BIT, sqlTypeName = "BIT(8)"),
                    TargetColumn("created_at", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("birth_date", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("wake_time", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("uuid_col", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("json_col", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("xml_col", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("blob_col", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("status", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("tags", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
                    TargetColumn("email", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                ),
            )
        }

        ex.message!! shouldContain "type mismatch"
        ex.message!! shouldContain "identifier-compatible integer"
        ex.message!! shouldContain "JSON-compatible type"
        ex.message!! shouldContain "array-compatible type"
    }
})
