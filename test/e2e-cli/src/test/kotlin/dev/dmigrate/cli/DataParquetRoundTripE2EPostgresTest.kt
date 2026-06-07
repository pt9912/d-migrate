package dev.dmigrate.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.sql.DriverManager

/**
 * S7d / S7e: End-to-End-Roundtrip fuer den Parquet-Pfad gegen zwei
 * Postgres-Container (Testcontainers), analog
 * [E2ERoundTripPostgresTest]. Beweist:
 *
 * 1. **Single-File-Roundtrip (S7d)**: `data export --format parquet
 *    --output users.parquet --tables users` schreibt eine Single-
 *    File mit `d-migrate.manifest`-Footer-KV (S7-0 + S4-Provider).
 *    Der KV traegt den unqualifizierten Tabellennamen `users`
 *    (Schema-Prefix-Stripping gibt es im Producer nicht).
 *    `data import --format parquet --source users.parquet` **ohne**
 *    `--table` laeuft durch die Footer-KV-Tabellennamen-Inferenz
 *    (Review-Finding A4) und schreibt die Zeilen in die vom Test
 *    angelegte Ziel-Tabelle.
 *
 * 2. **Bundle-Roundtrip (S7e)**: `data export --format parquet
 *    --output bundle/ --split-files --tables users,orders` schreibt
 *    `manifest.yaml` + zwei Parquet-Dateien via
 *    `onBundleClosure = ParquetBundleClosure(...)` (S7-0). Die
 *    Bundle-Parquet-Dateien tragen KEINEN Footer-KV (S4 §2.2-
 *    Invariante: Bundle-Pfad hat sein Manifest in `manifest.yaml`).
 *    `data import --format parquet --source bundle/` benutzt den
 *    Bundle-Preflight (S5a) + Seekable-Dispatch (S7a/b).
 *
 * Tagged `integration` — laeuft nur mit `-PintegrationTests`.
 */
class DataParquetRoundTripE2EPostgresTest : FunSpec({

    val source = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_parquet_src")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    val target = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_parquet_tgt")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    fun dmigUrl(c: PostgreSQLContainer): String =
        "postgresql://${c.username}:${c.password}@${c.host}:${c.firstMappedPort}/${c.databaseName}"

    fun rawJdbc(c: PostgreSQLContainer): String =
        "jdbc:postgresql://${c.host}:${c.firstMappedPort}/${c.databaseName}"

    fun cli() = DMigrate().subcommands(SchemaCommand(), DataCommand())

    fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try { block() } finally { System.setOut(original) }
        return captured.toString(Charsets.UTF_8)
    }

    fun queryAll(c: PostgreSQLContainer, table: String): List<Map<String, Any?>> =
        JdbcTestHelper.queryAll(rawJdbc(c), table, c.username, c.password)

    fun truncate(c: PostgreSQLContainer, table: String) {
        DriverManager.getConnection(rawJdbc(c), c.username, c.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE $table RESTART IDENTITY")
            }
        }
    }

    beforeSpec {
        source.start()
        target.start()
        registerDrivers()

        // Quell-DB: users-Tabelle + 3 Zeilen.
        DriverManager.getConnection(rawJdbc(source), source.username, source.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE users (
                        id   SERIAL PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE orders (
                        id      SERIAL PRIMARY KEY,
                        user_id INTEGER NOT NULL,
                        amount  NUMERIC(10, 2) NOT NULL
                    )
                    """.trimIndent()
                )
            }
            conn.prepareStatement("INSERT INTO users (name) VALUES (?)").use { ps ->
                listOf("alice", "bob", "charlie").forEach { name ->
                    ps.setString(1, name)
                    ps.execute()
                }
            }
            conn.prepareStatement("INSERT INTO orders (user_id, amount) VALUES (?, ?)").use { ps ->
                ps.setInt(1, 1); ps.setBigDecimal(2, java.math.BigDecimal("99.95")); ps.execute()
                ps.setInt(1, 2); ps.setBigDecimal(2, java.math.BigDecimal("42.00")); ps.execute()
            }
        }

        // Ziel-DB: leere Tabellen — `data import` legt kein DDL an.
        DriverManager.getConnection(rawJdbc(target), target.username, target.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE users (
                        id   INTEGER PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE orders (
                        id      INTEGER PRIMARY KEY,
                        user_id INTEGER NOT NULL,
                        amount  NUMERIC(10, 2) NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    afterSpec {
        // Defense-in-depth (Plan-Review-v2 Finding 14): halb-initialisierte
        // Container nicht erneut stoppen, sonst maskiert ein Cleanup-NPE den
        // Start-Stack-Trace im Gradle-Report.
        if (source.isRunning) source.stop()
        if (target.isRunning) target.stop()
        DatabaseDriverRegistry.clear()
    }

    // Plan-Review-v2 Finding 13: Truncate-Hygiene als beforeTest, damit
    // hinzukommende Cases nicht in id-Kollisionen rennen.
    beforeTest {
        truncate(target, "users")
        truncate(target, "orders")
    }

    test("S7d Single-File-Roundtrip: parquet export → import ohne --table (Footer-KV-Inferenz)") {
        val tmpFile = Files.createTempFile("dmigrate-parquet-singlefile-", ".parquet")
        // createTempFile reserviert die Datei; export laesst der Writer
        // sie wieder neu schreiben.
        Files.deleteIfExists(tmpFile)

        try {
            // Export Single-File → tempFile. CLI-Wiring (S7-0):
            //   ParquetChunkWriterFactory(extraMetaDataProvider = SingleFileWriter(...).provider)
            captureStdout {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", dmigUrl(source),
                        "--format", "parquet",
                        "--tables", "users",
                        "--output", tmpFile.toString(),
                    )
                )
            }
            Files.isRegularFile(tmpFile) shouldBe true

            // Plan-Review-v2 Finding 10: Footer-KV direkt am exportierten
            // File verifizieren, statt sich auf die spaete Inferenz-Exception
            // zu verlassen. ParquetSingleFilePreflight.phase1 oeffnet die
            // Datei, parst den Footer und exponiert manifestPresent — der
            // sauberste Single-Call-Pfad ohne neuen Hadoop-Import.
            val phase1Result = dev.dmigrate.format.parquet.ParquetSingleFilePreflight()
                .phase1(path = tmpFile, explicitTable = null, computeContentSha256 = false)
            phase1Result.manifestPresent shouldBe true
            phase1Result.table shouldBe "users"

            // Import ohne --table. Footer-KV traegt den Tabellennamen
            // `users` (unqualifiziert, weil `--tables users` ohne Schema-
            // Prefix exportiert wurde), den ParquetSingleFilePreflight.phase1
            // (AP11 §5.5) ueber den UNRESOLVED_PARQUET_TABLE_SENTINEL-Pfad
            // (Review-Finding A4) aufloest.
            captureStdout {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "import",
                        "--target", dmigUrl(target),
                        "--format", "parquet",
                        "--source", tmpFile.toString(),
                    )
                )
            }

            val rows = queryAll(target, "users")
            rows shouldContainExactlyInAnyOrder listOf(
                mapOf("id" to 1, "name" to "alice"),
                mapOf("id" to 2, "name" to "bob"),
                mapOf("id" to 3, "name" to "charlie"),
            )
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    test("S7e Bundle-Roundtrip: parquet --split-files export → bundle import schreibt manifest.yaml + N Parquet-Files") {
        val tmpDir = Files.createTempDirectory("dmigrate-parquet-bundle-")
        try {
            // Export als Bundle (Multi-File). CLI-Wiring (S7-0):
            //   onBundleClosure = ParquetBundleClosure(producerVersion = ...)
            //   writerFactoryBuilder gibt ParquetChunkWriterFactory ohne
            //   extraMetaDataProvider zurueck (S4 §2.2-Invariant: Bundle-Pfad
            //   ohne Footer-KV; manifest.yaml uebernimmt diese Rolle).
            captureStdout {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", dmigUrl(source),
                        "--format", "parquet",
                        "--tables", "users,orders",
                        "--output", tmpDir.toString(),
                        "--split-files",
                    )
                )
            }

            // Bundle-Marker: manifest.yaml + per-Tabellen-Parquet-Dateien.
            Files.isRegularFile(tmpDir.resolve("manifest.yaml")) shouldBe true
            Files.isRegularFile(tmpDir.resolve("users.parquet")) shouldBe true
            Files.isRegularFile(tmpDir.resolve("orders.parquet")) shouldBe true

            // Import des Bundles. ImportPreflight erkennt `manifest.yaml`
            // (Review-Finding A3 Sniff), `ParquetBundleResolver` baut die
            // ResolvedBundle, Phase-1-Hook produziert
            // `ImportInput.ResolvedBundle`. TableImporter dispatched
            // pro Tabelle Seekable (S7a).
            captureStdout {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "import",
                        "--target", dmigUrl(target),
                        "--format", "parquet",
                        "--source", tmpDir.toString(),
                    )
                )
            }

            queryAll(target, "users") shouldContainExactlyInAnyOrder listOf(
                mapOf("id" to 1, "name" to "alice"),
                mapOf("id" to 2, "name" to "bob"),
                mapOf("id" to 3, "name" to "charlie"),
            )
            // orders.user_id ist nur INTEGER (kein REFERENCES — Bundle-FK-Ordering
            // ist nicht Test-Gegenstand). Wir vergleichen amounts via Double-
            // Aequivalenz, weil PG NUMERIC als BigDecimal zurueckkommt.
            val orderRows = queryAll(target, "orders").map { row ->
                mapOf(
                    "id" to row["id"],
                    "user_id" to row["user_id"],
                    "amount" to (row["amount"] as java.math.BigDecimal).toPlainString(),
                )
            }
            orderRows shouldContainExactlyInAnyOrder listOf(
                mapOf("id" to 1, "user_id" to 1, "amount" to "99.95"),
                mapOf("id" to 2, "user_id" to 2, "amount" to "42.00"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
})
