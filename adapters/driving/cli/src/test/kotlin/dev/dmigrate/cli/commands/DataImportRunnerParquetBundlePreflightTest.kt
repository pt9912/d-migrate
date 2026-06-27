package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.ParquetChunkWriter
import dev.dmigrate.format.parquet.manifest.ParquetBundleClosure
import dev.dmigrate.streaming.BundleClosureContext
import dev.dmigrate.streaming.BundleClosureTable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S9a Bundle-Test-Familien **1 (CLI-Preflight-Codes)** und
 * **2 (manifest.yaml-Sniff)**: fährt [DataImportRunner.execute]
 * end-to-end mit dem **echten** [ParquetImportInputResolutionHook] gegen
 * den in S9a-0 hergestellten AP12-§9-Exit-Code-Vertrag. Belegt, dass
 * Bundle-Preflight-Fehler am CLI-Rand den korrekten Prozess-Exit-Code +
 * den stabilen stderr-Code tragen (Familie 1) und dass der Directory-
 * Format-Sniff ohne `--format` auf CLI-Ebene greift (Familie 2; vorher
 * nur Adapter-/Hook-/Helper-Ebene gedeckt).
 *
 * **Erreichbarkeits-Hinweise am CLI (verifiziert 2026-06-09):**
 * - `BUNDLE_ORDER_*` (Exit 5) sind seit dem `--table-order`-Flag
 *   CLI-erreichbar (`request.tableOrder` → `ImportInput.Directory.tableOrder`
 *   → Hook → `applyFilterAndOrder`). Die drei Tests unten beweisen das
 *   end-to-end (löst die frühere „adapter-only"-Notiz auf).
 * - `MANIFEST_SHA256_MISMATCH` (Exit 4) ist nur mit aktivem `--resume`
 *   erreichbar (ohne Resume gilt `verifyContentSha256 = false`, S8e).
 *   Die Per-File-Hash-Verifikation ist Teil der Resume-Familie (S9a.3).
 *   Hier werden die **immer** geprüften MANIFEST_*-Pfade verifiziert.
 */
class DataImportRunnerParquetBundlePreflightTest : FunSpec({

    class FakeConnectionPool(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
    ) : ConnectionPool {
        override fun borrow(): DatabaseConnection = error("borrow() must not be called — preflight fails first")
        override fun activeConnections(): Int = 0
        override fun close() {}
    }

    class FakeDataWriter(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
    ) : DataWriter {
        override fun schemaSync(): SchemaSync = error("not used")
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions): TableImportSession =
            error("not used")
    }

    fun newRunner(stderr: (String) -> Unit): DataImportRunner = DataImportRunner(
        targetResolver = { t, _ -> t ?: error("no target") },
        urlParser = {
            ConnectionConfig(
                dialect = DatabaseDialect.SQLITE,
                host = null, port = null, database = "/tmp/x.db", user = null, password = null,
            )
        },
        poolFactory = { FakeConnectionPool() },
        writerLookup = { FakeDataWriter() },
        importExecutor = { _, _, _, _ -> error("executor must not run — preflight fails first") },
        stderr = stderr,
        inputResolutionHook = ParquetImportInputResolutionHook(),
    )

    fun request(
        source: String,
        tables: List<String>? = null,
        tableOrder: List<String>? = null,
        format: String? = "parquet",
    ) = DataImportRequest(
        target = "sqlite:///tmp/x.db",
        source = source,
        format = format,
        schema = null,
        table = null,
        tables = tables,
        tableOrder = tableOrder,
        onError = "abort",
        onConflict = null,
        triggerMode = "fire",
        truncate = false,
        disableFkChecks = false,
        reseedSequences = true,
        encoding = null,
        csvNoHeader = false,
        csvNullString = "",
        chunkSize = 10_000,
        cliConfigPath = null,
        quiet = false,
        noProgress = false,
    )

    fun writeBundle(dir: Path) {
        val usersSchema = ChunkSchema(
            table = "users",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("id", false, NeutralType.BigInteger)),
        )
        val ordersSchema = ChunkSchema(
            table = "orders",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("order_id", false, NeutralType.BigInteger)),
        )
        for ((name, schema) in listOf("users" to usersSchema, "orders" to ordersSchema)) {
            Files.newOutputStream(dir.resolve("$name.parquet")).use { out ->
                ParquetChunkWriter(out).use { writer ->
                    writer.begin(name, schema)
                    writer.write(
                        DataChunk(table = name, columns = emptyList(), rows = listOf(arrayOf<Any?>(1L)), chunkIndex = 0L),
                    )
                    writer.end()
                }
            }
        }
        val fixedClock = Clock.fixed(Instant.parse("2026-06-06T11:00:00Z"), ZoneOffset.UTC)
        ParquetBundleClosure(producerVersion = "0.9.8", manifestSha256 = false, clock = fixedClock)(
            BundleClosureContext(
                directory = dir,
                format = DataExportFormat.PARQUET,
                tables = listOf(
                    BundleClosureTable("users", dir.resolve("users.parquet"), usersSchema, rowCount = 1),
                    BundleClosureTable("orders", dir.resolve("orders.parquet"), ordersSchema, rowCount = 1),
                ),
            ),
        )
    }

    test("CLI bundle import: fehlendes manifest.yaml → Exit 4 (MANIFEST_NOT_FOUND)") {
        val lines = mutableListOf<String>()
        val dir = Files.createTempDirectory("s9a1-no-manifest-")
        try {
            val code = newRunner(lines::add).execute(request(dir.toString()))
            code shouldBe 4
            lines.joinToString("\n") shouldContain "MANIFEST_NOT_FOUND"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("CLI bundle import: Orphan-Parquet → Exit 4 (MANIFEST_FILE_UNREFERENCED)") {
        val lines = mutableListOf<String>()
        val dir = Files.createTempDirectory("s9a1-orphan-")
        try {
            writeBundle(dir)
            Files.writeString(dir.resolve("orphan.parquet"), "not in manifest")
            val code = newRunner(lines::add).execute(request(dir.toString()))
            code shouldBe 4
            lines.joinToString("\n").let { out ->
                out shouldContain "MANIFEST_FILE_UNREFERENCED"
                out shouldContain "orphan.parquet"
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("CLI bundle import: --tables mit unbekannter Tabelle → Exit 5 (BUNDLE_FILTER_UNKNOWN_TABLE)") {
        val lines = mutableListOf<String>()
        val dir = Files.createTempDirectory("s9a1-filter-")
        try {
            writeBundle(dir)
            val code = newRunner(lines::add).execute(request(dir.toString(), tables = listOf("ghost")))
            code shouldBe 5
            lines.joinToString("\n").let { out ->
                out shouldContain "BUNDLE_FILTER_UNKNOWN_TABLE"
                out shouldContain "ghost"
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── --table-order: BUNDLE_ORDER_* jetzt CLI-erreichbar (Exit 5) ──

    test("CLI bundle import: --table-order mit Duplikat → Exit 5 (BUNDLE_ORDER_DUPLICATE)") {
        val lines = mutableListOf<String>()
        val dir = Files.createTempDirectory("s9a1-order-dup-")
        try {
            writeBundle(dir)
            val code = newRunner(lines::add).execute(request(dir.toString(), tableOrder = listOf("users", "users")))
            code shouldBe 5
            lines.joinToString("\n") shouldContain "BUNDLE_ORDER_DUPLICATE"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("CLI bundle import: --table-order mit unbekannter Tabelle → Exit 5 (BUNDLE_ORDER_UNKNOWN_TABLE)") {
        val lines = mutableListOf<String>()
        val dir = Files.createTempDirectory("s9a1-order-unknown-")
        try {
            writeBundle(dir)
            val code = newRunner(lines::add)
                .execute(request(dir.toString(), tableOrder = listOf("users", "orders", "ghost")))
            code shouldBe 5
            lines.joinToString("\n").let { out ->
                out shouldContain "BUNDLE_ORDER_UNKNOWN_TABLE"
                out shouldContain "ghost"
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("CLI bundle import: partieller --table-order → Exit 5 (BUNDLE_ORDER_INCOMPLETE)") {
        val lines = mutableListOf<String>()
        val dir = Files.createTempDirectory("s9a1-order-incomplete-")
        try {
            writeBundle(dir)
            val code = newRunner(lines::add).execute(request(dir.toString(), tableOrder = listOf("users")))
            code shouldBe 5
            lines.joinToString("\n").let { out ->
                out shouldContain "BUNDLE_ORDER_INCOMPLETE"
                out shouldContain "orders"
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── S9a.2: manifest.yaml-Sniff (Format-Inferenz ohne --format) ──
    // Belegt auf CLI-Ebene, dass DataImportHelpers.resolveFormat den
    // Directory-Sniff anwendet (Helper-Ebene: DataImportHelpersTest).

    test("CLI sniff: Bundle-Dir ohne --format wird als Parquet inferiert (Orphan → Exit 4)") {
        val lines = mutableListOf<String>()
        val dir = Files.createTempDirectory("s9a2-infer-")
        try {
            writeBundle(dir)
            Files.writeString(dir.resolve("orphan.parquet"), "not in manifest")
            // Kein --format. Wäre der Sniff fehlgeschlagen, käme Exit 2
            // (Format unbestimmbar) statt Exit 4 (Parquet-Bundle-Preflight lief).
            val code = newRunner(lines::add).execute(request(dir.toString(), format = null))
            code shouldBe 4
            lines.joinToString("\n") shouldContain "MANIFEST_FILE_UNREFERENCED"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("CLI sniff: Verzeichnis ohne manifest.yaml und ohne --format → Exit 2") {
        val lines = mutableListOf<String>()
        val dir = Files.createTempDirectory("s9a2-no-manifest-")
        try {
            Files.writeString(dir.resolve("data.parquet"), "x")
            val code = newRunner(lines::add).execute(request(dir.toString(), format = null))
            code shouldBe 2
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("CLI sniff: Helm-Style-manifest.yaml ohne --format → Exit 2 (False-Positive-Resistenz)") {
        val lines = mutableListOf<String>()
        val dir = Files.createTempDirectory("s9a2-helm-")
        try {
            // Kein `formatVersion:`/`tables:` → Sniff lehnt ab (DataImportHelpersTest-Parität).
            Files.writeString(dir.resolve("manifest.yaml"), "apiVersion: v2\nname: my-chart\nversion: 1.0.0\n")
            val code = newRunner(lines::add).execute(request(dir.toString(), format = null))
            code shouldBe 2
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
