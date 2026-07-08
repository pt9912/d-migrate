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
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.ParquetChunkWriter
import dev.dmigrate.format.parquet.manifest.ParquetBundleClosure
import dev.dmigrate.format.data.BundleClosureContext
import dev.dmigrate.format.data.BundleClosureTable
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.TableImportSummary
import dev.dmigrate.streaming.checkpoint.FileCheckpointStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S9a Bundle-Test-Familie **3 (Bundle-Resume)** auf CLI-Ebene: end-to-end
 * durch [DataImportRunner.execute] mit dem echten
 * [ParquetImportInputResolutionHook] + einem realen [FileCheckpointStore].
 *
 * **Methode (Zwei-Phasen):** Da `ImportOptionsFingerprint` `internal` zu
 * `:hexagon:application` ist (vom CLI-Modul nicht sichtbar), wird der
 * Checkpoint **nicht** von Hand vorab gebaut, sondern durch einen ersten
 * Lauf erzeugt, der mitten im Import scheitert (Exit 5 → `complete()`
 * läuft nicht → Manifest persistiert). Der Runner berechnet den
 * `optionsFingerprint` in beiden Phasen identisch, sodass der Resume den
 * generischen Fingerprint-Check passiert und die Bundle-Resume-Validierung
 * (S8c/S9a-0.f) erreicht.
 *
 * **Hier abgedeckt:** Happy-Path-Resume, `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`
 * (Exit 3), `MANIFEST_SHA256_MISMATCH` (Exit 4, nur unter `--resume`
 * erreichbar — `verifyContentSha256 = true`).
 *
 * **Bewusst auf Manager-Ebene** (`ImportCheckpointManagerOperationSpecificsTest`,
 * S8c + S9a-0.f) statt hier: `BUNDLE_FORMAT_VERSION_INCOMPATIBLE`
 * (`ParquetBundleClosure` schreibt immer `formatVersion = "1.0"`),
 * `BUNDLE_TABLE_ORDER_CHANGED` / `BUNDLE_RESUME_REQUIRES_FILE_HASHES`
 * (verlangen exakte manifestSha256-Übereinstimmung) und
 * `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` (Pre-AP8 = `operationSpecific`
 * null, vom Runner nie erzeugt). Diese sind dort sauber per Injektion gedeckt.
 */
class DataImportRunnerParquetBundleResumeTest : FunSpec({

    class FakeConnectionPool(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
    ) : ConnectionPool {
        override fun borrow(): DatabaseConnection = error("borrow() must not be called")
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

    val successExecutor: ImportExecutor = ImportExecutor { ctx, _, _, _ ->
        val tables = when (val input = ctx.input) {
            is dev.dmigrate.streaming.ImportInput.ResolvedBundle -> input.tables.map { it.table }
            else -> error("expected ResolvedBundle, got ${ctx.input}")
        }
        ImportResult(
            tables = tables.map {
                TableImportSummary(
                    table = it, rowsInserted = 10, rowsUpdated = 0, rowsSkipped = 0,
                    rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
                    sequenceAdjustments = emptyList(), targetColumns = emptyList(),
                    triggerMode = TriggerMode.FIRE, durationMs = 1,
                )
            },
            totalRowsInserted = 10L * tables.size, totalRowsUpdated = 0,
            totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 0, durationMs = 1,
        )
    }

    val failingExecutor: ImportExecutor = ImportExecutor { _, _, _, _ ->
        throw RuntimeException("simulated mid-import failure")
    }

    fun newRunner(stderr: (String) -> Unit, executor: ImportExecutor, store: FileCheckpointStore): DataImportRunner =
        DataImportRunner(
            targetResolver = { t, _ -> t ?: error("no target") },
            urlParser = {
                ConnectionConfig(DatabaseDialect.SQLITE, null, null, "/tmp/x.db", null, null)
            },
            poolFactory = { FakeConnectionPool() },
            writerLookup = { FakeDataWriter() },
            importExecutor = executor,
            stderr = stderr,
            checkpointStoreFactory = { store },
            inputResolutionHook = ParquetImportInputResolutionHook(),
        )

    fun request(source: String, resume: String?, checkpointDir: Path) = DataImportRequest(
        target = "sqlite:///tmp/x.db",
        source = source,
        format = "parquet",
        schema = null,
        table = null,
        tables = null,
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
        quiet = true,
        noProgress = true,
        resume = resume,
        checkpointDir = checkpointDir,
    )

    fun writeBundle(dir: Path, producerVersion: String = "0.9.8") {
        val usersSchema = ChunkSchema(
            table = "users", origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("id", false, NeutralType.BigInteger)),
        )
        val ordersSchema = ChunkSchema(
            table = "orders", origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("order_id", false, NeutralType.BigInteger)),
        )
        for ((name, schema) in listOf("users" to usersSchema, "orders" to ordersSchema)) {
            // Nur schreiben, wenn die Datei noch nicht existiert — Phase 2
            // (Manifest-Rewrite) lässt die .parquet-Dateien unverändert,
            // sonst änderte sich der per-Tabelle-Hash.
            if (Files.exists(dir.resolve("$name.parquet"))) continue
            Files.newOutputStream(dir.resolve("$name.parquet")).use { out ->
                ParquetChunkWriter(out).use { writer ->
                    writer.begin(name, schema)
                    writer.write(DataChunk(table = name, columns = emptyList(), rows = listOf(arrayOf<Any?>(1L)), chunkIndex = 0L))
                    writer.end()
                }
            }
        }
        val fixedClock = Clock.fixed(Instant.parse("2026-06-06T11:00:00Z"), ZoneOffset.UTC)
        ParquetBundleClosure(producerVersion = producerVersion, manifestSha256 = true, clock = fixedClock)(
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

    /** Liest die in Phase 1 erzeugte (zufällige) operationId aus dem Store-Verzeichnis. */
    fun persistedOpId(storeDir: Path): String =
        Files.list(storeDir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith(".checkpoint.yaml") }
                .findFirst()
                .orElseThrow { AssertionError("no persisted checkpoint in $storeDir") }
        }.removeSuffix(".checkpoint.yaml")

    test("--resume + manipulierte Datei → Exit 4 (MANIFEST_SHA256_MISMATCH)") {
        val storeDir = Files.createTempDirectory("s9a3-sha-store-")
        val bundleDir = Files.createTempDirectory("s9a3-sha-bundle-")
        try {
            writeBundle(bundleDir)
            // Datei nach dem Manifest manipulieren; unter --resume gilt
            // verifyContentSha256 = true → Preflight-SHA-Check schlägt an.
            Files.write(bundleDir.resolve("users.parquet"), ByteArray(16))
            val lines = mutableListOf<String>()
            val store = FileCheckpointStore(storeDir)
            val code = newRunner(lines::add, successExecutor, store)
                .execute(request(bundleDir.toString(), resume = "any-op", checkpointDir = storeDir))
            code shouldBe 4
            lines.joinToString("\n") shouldContain "MANIFEST_SHA256_MISMATCH"
        } finally {
            storeDir.toFile().deleteRecursively()
            bundleDir.toFile().deleteRecursively()
        }
    }

    test("Bundle-Resume Happy-Path: Lauf scheitert → Resume mit gleichem Bundle → Exit 0") {
        val storeDir = Files.createTempDirectory("s9a3-happy-store-")
        val bundleDir = Files.createTempDirectory("s9a3-happy-bundle-")
        try {
            writeBundle(bundleDir)
            val store = FileCheckpointStore(storeDir)

            // Phase 1: frischer Lauf, Executor scheitert → Exit 5, Manifest persistiert.
            val p1 = mutableListOf<String>()
            newRunner(p1::add, failingExecutor, store)
                .execute(request(bundleDir.toString(), resume = null, checkpointDir = storeDir)) shouldBe 5
            val opId = persistedOpId(storeDir)

            // Phase 2: Resume mit identischem Bundle → Validierung passiert → Exit 0.
            val p2 = mutableListOf<String>()
            newRunner(p2::add, successExecutor, store)
                .execute(request(bundleDir.toString(), resume = opId, checkpointDir = storeDir)) shouldBe 0
        } finally {
            storeDir.toFile().deleteRecursively()
            bundleDir.toFile().deleteRecursively()
        }
    }

    test("Bundle-Resume mit geändertem manifest.yaml → Exit 3 (BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT)") {
        val storeDir = Files.createTempDirectory("s9a3-changed-store-")
        val bundleDir = Files.createTempDirectory("s9a3-changed-bundle-")
        try {
            writeBundle(bundleDir, producerVersion = "0.9.8")
            val store = FileCheckpointStore(storeDir)

            // Phase 1: frischer Lauf scheitert → Checkpoint mit v1-Fingerprint persistiert.
            val p1 = mutableListOf<String>()
            newRunner(p1::add, failingExecutor, store)
                .execute(request(bundleDir.toString(), resume = null, checkpointDir = storeDir)) shouldBe 5
            val opId = persistedOpId(storeDir)

            // manifest.yaml mit anderem producerVersion neu schreiben (gleiche
            // .parquet-Dateien/Tabellen → optionsFingerprint unverändert, aber
            // manifestSha256 differiert) → BUNDLE_MANIFEST_CHANGED.
            writeBundle(bundleDir, producerVersion = "9.9.9")

            val p2 = mutableListOf<String>()
            val code = newRunner(p2::add, successExecutor, store)
                .execute(request(bundleDir.toString(), resume = opId, checkpointDir = storeDir))
            code shouldBe 3
            p2.joinToString("\n") shouldContain "BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT"
        } finally {
            storeDir.toFile().deleteRecursively()
            bundleDir.toFile().deleteRecursively()
        }
    }
})
