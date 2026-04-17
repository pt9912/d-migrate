package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataExportFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * 0.9.0 Phase D.4 (`docs/ImpPlan-0.9.0-D.md` §4.5 / §5.4): Unit-Tests
 * fuer den Directory-Scanner. Dieselbe Auswahl-Semantik wie im
 * privaten `StreamingImporter.resolveDirectoryInputs`, aber ohne
 * Streaming-Abhaengigkeit, damit der Runner-Preflight sie vor dem
 * Import-Lauf aufrufen kann.
 */
class DirectoryImportScannerTest : FunSpec({

    test("returns files sorted by table name when no tableOrder is set") {
        val dir = Files.createTempDirectory("d-migrate-scan-sorted-")
        Files.writeString(dir.resolve("users.json"), "[]")
        Files.writeString(dir.resolve("orders.json"), "[]")
        Files.writeString(dir.resolve("addresses.json"), "[]")
        val result = DirectoryImportScanner.scan(dir, DataExportFormat.JSON)
        result.map { it.table } shouldContainExactly listOf("addresses", "orders", "users")
        result.map { it.fileName } shouldContainExactly
            listOf("addresses.json", "orders.json", "users.json")
    }

    test("ignores non-matching file extensions") {
        val dir = Files.createTempDirectory("d-migrate-scan-filter-")
        Files.writeString(dir.resolve("users.json"), "[]")
        Files.writeString(dir.resolve("README.md"), "docs")
        Files.writeString(dir.resolve("other.csv"), "a,b")
        val result = DirectoryImportScanner.scan(dir, DataExportFormat.JSON)
        result.map { it.table } shouldContainExactly listOf("users")
    }

    test("tableFilter narrows the candidate set") {
        val dir = Files.createTempDirectory("d-migrate-scan-narrow-")
        Files.writeString(dir.resolve("users.json"), "[]")
        Files.writeString(dir.resolve("orders.json"), "[]")
        val result = DirectoryImportScanner.scan(
            dir, DataExportFormat.JSON, tableFilter = listOf("users"),
        )
        result.map { it.table } shouldContainExactly listOf("users")
    }

    test("tableOrder overrides default sort order") {
        val dir = Files.createTempDirectory("d-migrate-scan-order-")
        Files.writeString(dir.resolve("users.json"), "[]")
        Files.writeString(dir.resolve("orders.json"), "[]")
        val result = DirectoryImportScanner.scan(
            dir, DataExportFormat.JSON,
            tableOrder = listOf("orders", "users"),
        )
        result.map { it.table } shouldContainExactly listOf("orders", "users")
    }

    test("tableFilter missing table throws") {
        val dir = Files.createTempDirectory("d-migrate-scan-missing-")
        Files.writeString(dir.resolve("users.json"), "[]")
        val ex = shouldThrow<IllegalArgumentException> {
            DirectoryImportScanner.scan(
                dir, DataExportFormat.JSON,
                tableFilter = listOf("users", "orders"),
            )
        }
        ex.message!! shouldContain "tableFilter references tables"
    }

    test("tableOrder with duplicate throws") {
        val dir = Files.createTempDirectory("d-migrate-scan-dup-")
        Files.writeString(dir.resolve("users.json"), "[]")
        Files.writeString(dir.resolve("orders.json"), "[]")
        val ex = shouldThrow<IllegalArgumentException> {
            DirectoryImportScanner.scan(
                dir, DataExportFormat.JSON,
                tableOrder = listOf("users", "users"),
            )
        }
        ex.message!! shouldContain "duplicate tables"
    }

    test("tableOrder missing coverage throws") {
        val dir = Files.createTempDirectory("d-migrate-scan-extras-")
        Files.writeString(dir.resolve("users.json"), "[]")
        Files.writeString(dir.resolve("orders.json"), "[]")
        val ex = shouldThrow<IllegalArgumentException> {
            DirectoryImportScanner.scan(
                dir, DataExportFormat.JSON,
                tableOrder = listOf("users"),
            )
        }
        ex.message!! shouldContain "missing order for"
    }

    test("non-directory path throws") {
        val file = Files.createTempFile("d-migrate-scan-nondir-", ".json")
        val ex = shouldThrow<IllegalArgumentException> {
            DirectoryImportScanner.scan(file, DataExportFormat.JSON)
        }
        ex.message!! shouldContain "is not a directory"
    }

    test("ambiguous duplicate files for same table throws") {
        val dir = Files.createTempDirectory("d-migrate-scan-ambig-")
        // YAML format accepts both .yaml and .yml extensions
        Files.writeString(dir.resolve("users.yaml"), "[]")
        Files.writeString(dir.resolve("users.yml"), "[]")
        val ex = shouldThrow<IllegalArgumentException> {
            DirectoryImportScanner.scan(dir, DataExportFormat.YAML)
        }
        ex.message!! shouldContain "multiple files for the same table"
    }

    test("CSV format uses .csv extension") {
        val dir = Files.createTempDirectory("d-migrate-scan-csv-")
        Files.writeString(dir.resolve("users.csv"), "id,name\n")
        val result = DirectoryImportScanner.scan(dir, DataExportFormat.CSV)
        result.single().table shouldBe "users"
        result.single().fileName shouldBe "users.csv"
    }
})
