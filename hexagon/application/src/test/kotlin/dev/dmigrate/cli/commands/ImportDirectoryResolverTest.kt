package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class ImportDirectoryResolverTest : FunSpec({

    fun tempDir(): Path = Files.createTempDirectory("dmigrate-resolver-")

    fun makeFile(dir: Path, name: String): Path {
        val p = dir.resolve(name)
        Files.writeString(p, "[]")
        return p
    }

    fun simpleTable(refs: Map<String, ReferenceDefinition> = emptyMap()): TableDefinition =
        TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
            ) + refs.mapValues { (_, ref) ->
                ColumnDefinition(NeutralType.Integer, references = ref)
            },
        )

    fun schemaOf(vararg tables: Pair<String, TableDefinition>): SchemaDefinition =
        SchemaDefinition(name = "S", version = "1", tables = tables.toMap())

    context("matchingSchemaTableNames") {
        test("exact match returns the table") {
            ImportDirectoryResolver.matchingSchemaTableNames(setOf("users", "orders"), "users") shouldBe listOf("users")
        }
        test("qualified request matches unqualified schema table") {
            ImportDirectoryResolver.matchingSchemaTableNames(setOf("users"), "public.users") shouldBe listOf("users")
        }
        test("unqualified request matches qualified schema table") {
            ImportDirectoryResolver.matchingSchemaTableNames(setOf("public.users"), "users") shouldBe listOf("public.users")
        }
        test("returns multiple if multiple schema tables share the suffix") {
            val matches = ImportDirectoryResolver.matchingSchemaTableNames(
                setOf("public.users", "audit.users"), "users",
            )
            matches shouldContainExactly listOf("public.users", "audit.users")
        }
        test("returns empty when no match") {
            ImportDirectoryResolver.matchingSchemaTableNames(setOf("users"), "orders") shouldBe emptyList()
        }
    }

    context("resolveTableOrder happy paths") {
        test("empty directory yields empty list") {
            val dir = tempDir()
            val result = ImportDirectoryResolver.resolveTableOrder(
                schemaPath = Path.of("/schema.yaml"),
                schema = schemaOf("users" to simpleTable()),
                input = ImportInput.Directory(dir),
                format = DataExportFormat.JSON,
            )
            result.shouldBeEmpty()
            Files.deleteIfExists(dir)
        }

        test("topologically sorts based on FK references (column-level)") {
            val dir = tempDir()
            makeFile(dir, "orders.json")
            makeFile(dir, "users.json")
            val schema = schemaOf(
                "users" to simpleTable(),
                "orders" to simpleTable(
                    refs = mapOf("user_id" to ReferenceDefinition("users", "id")),
                ),
            )
            val result = ImportDirectoryResolver.resolveTableOrder(
                schemaPath = Path.of("/s.yaml"),
                schema = schema,
                input = ImportInput.Directory(dir),
                format = DataExportFormat.JSON,
            )
            result shouldBe listOf("users", "orders")
        }

        test("topologically sorts via FK constraint references (composite)") {
            val dir = tempDir()
            makeFile(dir, "items.json")
            makeFile(dir, "orders.json")
            val itemsTable = TableDefinition(
                columns = mapOf(
                    "order_id" to ColumnDefinition(NeutralType.Integer),
                    "sku" to ColumnDefinition(NeutralType.Integer),
                ),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "fk_items_orders",
                        type = ConstraintType.FOREIGN_KEY,
                        columns = listOf("order_id"),
                        references = ConstraintReferenceDefinition(
                            table = "orders",
                            columns = listOf("id"),
                        ),
                    ),
                ),
            )
            val schema = schemaOf(
                "orders" to simpleTable(),
                "items" to itemsTable,
            )
            val result = ImportDirectoryResolver.resolveTableOrder(
                schemaPath = Path.of("/s.yaml"),
                schema = schema,
                input = ImportInput.Directory(dir),
                format = DataExportFormat.JSON,
            )
            result shouldBe listOf("orders", "items")
        }

        test("yaml format accepts both .yaml and .yml extensions") {
            val dir = tempDir()
            makeFile(dir, "users.yaml")
            makeFile(dir, "orders.yml")
            val schema = schemaOf(
                "users" to simpleTable(),
                "orders" to simpleTable(),
            )
            val result = ImportDirectoryResolver.resolveTableOrder(
                schemaPath = Path.of("/s.yaml"),
                schema = schema,
                input = ImportInput.Directory(dir),
                format = DataExportFormat.YAML,
            )
            result.toSet() shouldBe setOf("users", "orders")
        }

        test("ignores files without matching extension") {
            val dir = tempDir()
            makeFile(dir, "users.json")
            makeFile(dir, "README.md")
            val schema = schemaOf("users" to simpleTable())
            val result = ImportDirectoryResolver.resolveTableOrder(
                schemaPath = Path.of("/s.yaml"),
                schema = schema,
                input = ImportInput.Directory(dir),
                format = DataExportFormat.JSON,
            )
            result shouldBe listOf("users")
        }

        test("tableFilter restricts the set") {
            val dir = tempDir()
            makeFile(dir, "users.json")
            makeFile(dir, "orders.json")
            val schema = schemaOf(
                "users" to simpleTable(),
                "orders" to simpleTable(),
            )
            val result = ImportDirectoryResolver.resolveTableOrder(
                schemaPath = Path.of("/s.yaml"),
                schema = schema,
                input = ImportInput.Directory(dir, tableFilter = listOf("orders")),
                format = DataExportFormat.JSON,
            )
            result shouldBe listOf("orders")
        }
    }

    context("resolveTableOrder error paths") {
        test("missing schema entry") {
            val dir = tempDir()
            makeFile(dir, "ghost.json")
            val schema = schemaOf("users" to simpleTable())
            val ex = shouldThrow<ImportPreflightException> {
                ImportDirectoryResolver.resolveTableOrder(
                    schemaPath = Path.of("/s.yaml"),
                    schema = schema,
                    input = ImportInput.Directory(dir),
                    format = DataExportFormat.JSON,
                )
            }
            ex.message!! shouldContain "does not define tables required for directory import: ghost"
        }

        test("ambiguous match") {
            val dir = tempDir()
            makeFile(dir, "users.json")
            val schema = schemaOf(
                "public.users" to simpleTable(),
                "audit.users" to simpleTable(),
            )
            val ex = shouldThrow<ImportPreflightException> {
                ImportDirectoryResolver.resolveTableOrder(
                    schemaPath = Path.of("/s.yaml"),
                    schema = schema,
                    input = ImportInput.Directory(dir),
                    format = DataExportFormat.JSON,
                )
            }
            ex.message!! shouldContain "matches directory import tables ambiguously"
        }

        test("multiple candidate files for the same table without filter") {
            val dir = tempDir()
            makeFile(dir, "users.yaml")
            makeFile(dir, "users.yml")
            val schema = schemaOf("users" to simpleTable())
            val ex = shouldThrow<ImportPreflightException> {
                ImportDirectoryResolver.resolveTableOrder(
                    schemaPath = Path.of("/s.yaml"),
                    schema = schema,
                    input = ImportInput.Directory(dir),
                    format = DataExportFormat.YAML,
                )
            }
            ex.message!! shouldContain "multiple files for the same table"
        }

        test("multiple candidate files when explicitly requested via filter") {
            val dir = tempDir()
            makeFile(dir, "users.yaml")
            makeFile(dir, "users.yml")
            val schema = schemaOf("users" to simpleTable())
            val ex = shouldThrow<ImportPreflightException> {
                ImportDirectoryResolver.resolveTableOrder(
                    schemaPath = Path.of("/s.yaml"),
                    schema = schema,
                    input = ImportInput.Directory(dir, tableFilter = listOf("users")),
                    format = DataExportFormat.YAML,
                )
            }
            ex.message!! shouldContain "multiple files for the same table"
        }

        test("filter referencing non-existent table") {
            val dir = tempDir()
            makeFile(dir, "users.json")
            val schema = schemaOf("users" to simpleTable())
            val ex = shouldThrow<ImportPreflightException> {
                ImportDirectoryResolver.resolveTableOrder(
                    schemaPath = Path.of("/s.yaml"),
                    schema = schema,
                    input = ImportInput.Directory(dir, tableFilter = listOf("ghost")),
                    format = DataExportFormat.JSON,
                )
            }
            ex.message!! shouldContain "filter references tables without matching files: ghost"
        }

        test("circular FK dependency") {
            val dir = tempDir()
            makeFile(dir, "a.json")
            makeFile(dir, "b.json")
            val schema = schemaOf(
                "a" to simpleTable(refs = mapOf("b_id" to ReferenceDefinition("b", "id"))),
                "b" to simpleTable(refs = mapOf("a_id" to ReferenceDefinition("a", "id"))),
            )
            val ex = shouldThrow<ImportPreflightException> {
                ImportDirectoryResolver.resolveTableOrder(
                    schemaPath = Path.of("/s.yaml"),
                    schema = schema,
                    input = ImportInput.Directory(dir),
                    format = DataExportFormat.JSON,
                )
            }
            ex.message!! shouldContain "table dependency cycle"
        }

        test("non-existent directory wraps IOException") {
            val ghost = Path.of("/tmp/dmigrate-resolver-doesnotexist-${System.nanoTime()}")
            val schema = schemaOf("users" to simpleTable())
            val ex = shouldThrow<ImportPreflightException> {
                ImportDirectoryResolver.resolveTableOrder(
                    schemaPath = Path.of("/s.yaml"),
                    schema = schema,
                    input = ImportInput.Directory(ghost),
                    format = DataExportFormat.JSON,
                )
            }
            ex.message!! shouldContain "Failed to list directory import source"
        }

        test("two directory tables map to the same schema table (duplicate target)") {
            val dir = tempDir()
            // Both qualified and unqualified file names match the single schema table 'users'
            makeFile(dir, "users.json")
            makeFile(dir, "public.users.json")
            val schema = schemaOf("users" to simpleTable())
            val ex = shouldThrow<ImportPreflightException> {
                ImportDirectoryResolver.resolveTableOrder(
                    schemaPath = Path.of("/s.yaml"),
                    schema = schema,
                    input = ImportInput.Directory(dir),
                    format = DataExportFormat.JSON,
                )
            }
            ex.message!! shouldContain "multiple directory tables to the same schema table"
        }
    }
})
