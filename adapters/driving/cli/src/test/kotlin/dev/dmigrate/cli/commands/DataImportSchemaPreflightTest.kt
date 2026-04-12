package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class DataImportSchemaPreflightTest : FunSpec({

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
})
