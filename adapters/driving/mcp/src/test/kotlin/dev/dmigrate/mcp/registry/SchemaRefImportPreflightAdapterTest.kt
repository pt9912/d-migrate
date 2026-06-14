package dev.dmigrate.mcp.registry

import dev.dmigrate.cli.commands.ImportPreflightException
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Types

class SchemaRefImportPreflightAdapterTest : FunSpec({

    fun writeSchemaFile(content: String): Path =
        Files.createTempFile("dmigrate-mcp-schema-ref-", ".yaml").also {
            Files.writeString(it, content.trimIndent())
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

    test("prepare validates schemaRef payload and resolves directory table order") {
        val importDir = Files.createTempDirectory("dmigrate-mcp-schema-ref-import-")
        Files.writeString(importDir.resolve("users.json"), """[{"id":1}]""")
        Files.writeString(importDir.resolve("orders.json"), """[{"id":10,"user_id":1}]""")
        val schemaFile = writeSchemaFile(
            """
            schema_format: "1.0"
            name: "SchemaRefImport"
            version: "1.0.0"
            tables:
              users:
                columns:
                  id:
                    type: identifier
              orders:
                columns:
                  id:
                    type: identifier
                  user_id:
                    type: integer
                    references:
                      table: users
                      column: id
            """,
        )

        val result = SchemaRefImportPreflightAdapter.prepare(
            schemaPath = schemaFile,
            schemaFormat = "yaml",
            input = ImportInput.Directory(importDir),
            format = DataExportFormat.JSON,
        )

        (result.input as ImportInput.Directory).tableOrder shouldBe listOf("users", "orders")
        result.schema!!.tables.keys shouldBe setOf("users", "orders")
        cleanup(importDir, schemaFile)
    }

    test("prepare rejects invalid schemaRef payload before import execution") {
        val dataFile = Files.createTempFile("dmigrate-mcp-schema-ref-data-", ".json")
        val schemaFile = writeSchemaFile(
            """
            schema_format: "1.0"
            name: "InvalidSchemaRef"
            version: "1.0.0"
            tables:
              empty:
                columns: {}
            """,
        )

        val ex = shouldThrow<ImportPreflightException> {
            SchemaRefImportPreflightAdapter.prepare(
                schemaPath = schemaFile,
                schemaFormat = "yaml",
                input = ImportInput.SingleFile("users", dataFile),
                format = DataExportFormat.JSON,
            )
        }

        ex.message!! shouldContain "SchemaRef validation failed"
        cleanup(dataFile, schemaFile)
    }

    test("validateTargetTable rejects schemaRef target mismatch") {
        val schema = SchemaDefinition(
            name = "Mismatch",
            version = "1.0.0",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                        "email" to ColumnDefinition(NeutralType.Email, required = true),
                    ),
                ),
            ),
        )

        val ex = shouldThrow<ImportSchemaMismatchException> {
            SchemaRefImportPreflightAdapter.validateTargetTable(
                schema = schema,
                table = "users",
                targetColumns = listOf(
                    TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                    TargetColumn("email", nullable = true, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
                ),
            )
        }

        ex.message!! shouldContain "does not match the provided --schema"
        ex.message!! shouldContain "nullability mismatch"
        ex.message!! shouldContain "type mismatch"
    }

    test("prepare topo-sorts ResolvedBundle tables via schema FK") {
        val schemaFile = writeSchemaFile(
            """
            schema_format: "1.0"
            name: "SchemaRefImport"
            version: "1.0.0"
            tables:
              users:
                columns:
                  id:
                    type: identifier
              orders:
                columns:
                  id:
                    type: identifier
                  user_id:
                    type: integer
                    references:
                      table: users
                      column: id
            """,
        )
        val bundleRoot = Files.createTempDirectory("mcp-bundle-")
        val ordersPath = bundleRoot.resolve("orders.parquet").also { Files.writeString(it, "") }
        val usersPath = bundleRoot.resolve("users.parquet").also { Files.writeString(it, "") }
        try {
            // Manifest-Reihenfolge (orders -> users) ist alphabetisch und
            // bricht ohne Topo-Sort die FK-Reihenfolge.
            val bundle = ImportInput.ResolvedBundle(
                bundleRoot = bundleRoot,
                tables = listOf(
                    dev.dmigrate.streaming.ResolvedBundleTableBinding(
                        table = "orders",
                        path = ordersPath,
                        schema = dev.dmigrate.format.data.ChunkSchema(
                            table = "orders",
                            origin = dev.dmigrate.format.data.SchemaOrigin.MANIFEST_FALLBACK,
                            columns = emptyList(),
                        ),
                    ),
                    dev.dmigrate.streaming.ResolvedBundleTableBinding(
                        table = "users",
                        path = usersPath,
                        schema = dev.dmigrate.format.data.ChunkSchema(
                            table = "users",
                            origin = dev.dmigrate.format.data.SchemaOrigin.MANIFEST_FALLBACK,
                            columns = emptyList(),
                        ),
                    ),
                ),
                resumeFingerprint = dev.dmigrate.streaming.BundleResumeFingerprint(
                    manifestSha256 = "deadbeef",
                    formatVersion = "1.0",
                    producerVersion = "test",
                    tableOrder = listOf("orders", "users"),
                ),
            )

            val result = SchemaRefImportPreflightAdapter.prepare(
                schemaPath = schemaFile,
                schemaFormat = "yaml",
                input = bundle,
                format = DataExportFormat.PARQUET,
            )

            val resolved = result.input as ImportInput.ResolvedBundle
            // FK fordert users vor orders.
            resolved.tables.map { it.table } shouldBe listOf("users", "orders")
        } finally {
            cleanup(schemaFile, bundleRoot)
        }
    }
})
