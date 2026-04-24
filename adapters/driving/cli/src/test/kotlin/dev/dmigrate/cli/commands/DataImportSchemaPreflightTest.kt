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
import java.nio.file.Path
import java.sql.Types

class DataImportSchemaPreflightTest : FunSpec({

    fun writeSchemaFile(content: String): Path =
        Files.createTempFile("dmigrate-preflight-schema-", ".yaml").also {
            Files.writeString(it, content.trimIndent())
        }

    fun minimalSchemaYaml(vararg tableNames: String): String = buildString {
        appendLine("schema_format: \"1.0\"")
        appendLine("name: \"Preflight\"")
        appendLine("version: \"1.0.0\"")
        appendLine("tables:")
        tableNames.forEach { tableName ->
            appendLine("  \"$tableName\":")
            appendLine("    columns:")
            appendLine("      id:")
            appendLine("        type: identifier")
        }
    }

    fun writeImportFile(dir: Path, fileName: String, content: String = """[{"id":1}]""") {
        Files.writeString(dir.resolve(fileName), content)
    }

    fun cleanup(vararg paths: Path) {
        paths.forEach { path ->
            if (Files.isDirectory(path)) {
                path.toFile().deleteRecursively()
            } else {
                Files.deleteIfExists(path)
            }
        }
    }

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

    test("validateTargetTable rejects tables missing from the schema") {
        val schema = SchemaDefinition(
            name = "MissingTable",
            version = "1.0.0",
            tables = mapOf("users" to TableDefinition(columns = linkedMapOf())),
        )

        val ex = shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schema,
                table = "orders",
                targetColumns = emptyList(),
            )
        }

        ex.message!! shouldContain "Table 'orders' is not defined in the provided --schema file"
    }

    test("validateTargetTable rejects ambiguous unqualified schema table names") {
        val schema = SchemaDefinition(
            name = "AmbiguousTable",
            version = "1.0.0",
            tables = mapOf(
                "public.users" to TableDefinition(columns = linkedMapOf()),
                "archive.users" to TableDefinition(columns = linkedMapOf()),
            ),
        )

        val ex = shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schema,
                table = "users",
                targetColumns = emptyList(),
            )
        }

        ex.message!! shouldContain "Table 'users' matches multiple tables in the provided --schema"
        ex.message!! shouldContain "public.users"
        ex.message!! shouldContain "archive.users"
    }

    test("validateTargetTable rejects missing and unexpected columns") {
        val schema = SchemaDefinition(
            name = "ColumnMismatch",
            version = "1.0.0",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                    ),
                )
            ),
        )

        val ex = shouldThrow<ImportSchemaMismatchException> {
            DataImportSchemaPreflight.validateTargetTable(
                schema = schema,
                table = "users",
                targetColumns = listOf(
                    TargetColumn("extra", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                ),
            )
        }

        ex.message!! shouldContain "missing target columns: id"
        ex.message!! shouldContain "unexpected target columns: extra"
    }

    test("prepare rejects a schema path that does not exist") {
        val tempDir = Files.createTempDirectory("dmigrate-missing-schema-")
        val missingSchema = tempDir.resolve("missing.yaml")
        val dataFile = Files.createTempFile("dmigrate-missing-schema-data-", ".json")
        Files.writeString(dataFile, """[{"id":1}]""")

        val ex = shouldThrow<ImportPreflightException> {
            DataImportSchemaPreflight.prepare(
                schemaPath = missingSchema,
                input = ImportInput.SingleFile("users", dataFile),
                format = DataExportFormat.JSON,
            )
        }

        ex.message!! shouldContain "Schema path does not exist"
        cleanup(dataFile, tempDir)
    }

    test("prepare rejects a schema path that is not a file") {
        val schemaDir = Files.createTempDirectory("dmigrate-schema-dir-")
        val dataFile = Files.createTempFile("dmigrate-schema-dir-data-", ".json")
        Files.writeString(dataFile, """[{"id":1}]""")

        val ex = shouldThrow<ImportPreflightException> {
            DataImportSchemaPreflight.prepare(
                schemaPath = schemaDir,
                input = ImportInput.SingleFile("users", dataFile),
                format = DataExportFormat.JSON,
            )
        }

        ex.message!! shouldContain "Schema path is not a file"
        cleanup(dataFile, schemaDir)
    }

    test("prepare wraps schema parse errors") {
        val schemaFile = writeSchemaFile(
            """
            schema_format: "1.0"
            name: "Broken"
            version: "1.0.0"
            tables: [
            """
        )
        val dataFile = Files.createTempFile("dmigrate-broken-schema-data-", ".json")
        Files.writeString(dataFile, """[{"id":1}]""")

        val ex = shouldThrow<ImportPreflightException> {
            DataImportSchemaPreflight.prepare(
                schemaPath = schemaFile,
                input = ImportInput.SingleFile("users", dataFile),
                format = DataExportFormat.JSON,
            )
        }

        ex.message!! shouldContain "Failed to parse schema file"
        cleanup(dataFile, schemaFile)
    }

    test("prepare rejects directory tables that are missing from the schema") {
        val importDir = Files.createTempDirectory("dmigrate-missing-schema-table-")
        val schemaFile = writeSchemaFile(minimalSchemaYaml("users"))
        writeImportFile(importDir, "orders.json")

        val ex = shouldThrow<ImportPreflightException> {
            DataImportSchemaPreflight.prepare(
                schemaPath = schemaFile,
                input = ImportInput.Directory(importDir),
                format = DataExportFormat.JSON,
            )
        }

        ex.message!! shouldContain "does not define tables required for directory import"
        ex.message!! shouldContain "orders"
        cleanup(importDir, schemaFile)
    }

})
