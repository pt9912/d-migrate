package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

private val IntegrationTag = NamedTag("integration")

/**
 * End-to-End-Tests für `d-migrate data import` gegen einen realen PostgreSQL-
 * Container (Testcontainers). Plan §4 Phase F Schritt 29 + 32.
 *
 * Tagged `integration` — läuft nur mit `./gradlew test -PintegrationTests`
 * und im `.github/workflows/integration.yml`-Workflow.
 */
class DataImportE2EPostgresTest : FunSpec({

    tags(IntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_e2e")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    fun cli() = DMigrate().subcommands(SchemaCommand(), DataCommand())

    fun jdbcUrl(): String =
        "postgresql://${container.username}:${container.password}@" +
            "${container.host}:${container.firstMappedPort}/${container.databaseName}"

    fun rawJdbc(): String =
        "jdbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"

    fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try { block() } finally { System.setOut(original) }
        return captured.toString(Charsets.UTF_8)
    }

    fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try { block() } finally { System.setErr(original) }
        return captured.toString(Charsets.UTF_8)
    }

    fun queryAll(table: String): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.prepareStatement("SELECT * FROM $table ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val meta = rs.metaData
                    while (rs.next()) {
                        val row = linkedMapOf<String, Any?>()
                        for (i in 1..meta.columnCount) {
                            row[meta.getColumnName(i)] = rs.getObject(i)
                        }
                        rows += row
                    }
                }
            }
        }
        return rows
    }

    fun truncateAll() {
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE orders, users, audit_log RESTART IDENTITY CASCADE")
            }
        }
    }

    fun seedUsers(vararg rows: Triple<String, String?, Boolean>) {
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (name, email, active, score) VALUES (?, ?, ?, ?)"
            ).use { ps ->
                rows.forEach { (name, email, active) ->
                    ps.setString(1, name)
                    if (email == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, email)
                    ps.setBoolean(3, active)
                    ps.setBigDecimal(4, java.math.BigDecimal("50.00"))
                    ps.executeUpdate()
                }
            }
        }
    }

    fun exportToFile(format: String, outFile: java.nio.file.Path, tables: String = "users") {
        captureStdout {
            cli().parse(
                listOf(
                    "--quiet",
                    "data", "export",
                    "--source", jdbcUrl(),
                    "--format", format,
                    "--tables", tables,
                    "--output", outFile.toString(),
                )
            )
        }
    }

    beforeSpec {
        container.start()
        registerDrivers()

        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE users (
                        id         SERIAL PRIMARY KEY,
                        name       TEXT NOT NULL,
                        email      TEXT,
                        active     BOOLEAN NOT NULL DEFAULT TRUE,
                        score      NUMERIC(10, 2),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE orders (
                        id        SERIAL PRIMARY KEY,
                        user_id   INTEGER NOT NULL REFERENCES users(id),
                        amount    NUMERIC(12, 2) NOT NULL,
                        placed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE TABLE audit_log (id SERIAL PRIMARY KEY, message TEXT NOT NULL)")
                stmt.execute(
                    """
                    CREATE OR REPLACE FUNCTION log_user_insert() RETURNS TRIGGER AS $$
                    BEGIN
                        INSERT INTO audit_log (message) VALUES ('inserted ' || NEW.name);
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """.trimIndent()
                )
                stmt.execute(
                    "CREATE TRIGGER trg_user_insert AFTER INSERT ON users " +
                        "FOR EACH ROW EXECUTE FUNCTION log_user_insert()"
                )
            }
        }
    }

    afterSpec {
        if (container.isRunning) container.stop()
        DatabaseDriverRegistry.clear()
    }

    beforeTest {
        truncateAll()
    }

    // ─── Round-Trip: JSON ────────────────────────────────────────

    test("JSON round-trip: export → import → row equivalence") {
        seedUsers(
            Triple("alice", "alice@example.com", true),
            Triple("bob", null, false),
        )
        // Disable trigger to avoid audit_log noise during seed — we re-seed after truncate
        truncateAll()
        // Seed without trigger firing (import path will handle this)
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE users DISABLE TRIGGER trg_user_insert")
                stmt.execute("INSERT INTO users (name, email, active, score) VALUES ('alice', 'alice@example.com', true, 98.50)")
                stmt.execute("INSERT INTO users (name, email, active, score) VALUES ('bob', null, false, 75.25)")
                stmt.execute("ALTER TABLE users ENABLE TRIGGER trg_user_insert")
            }
        }

        val dataFile = Files.createTempFile("pg-import-rt-", ".json")
        dataFile.deleteIfExists()
        try {
            exportToFile("json", dataFile)

            truncateAll()

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                        "--trigger-mode", "disable",
                    )
                )
            }

            val rows = queryAll("users")
            rows.size shouldBe 2
            rows.map { it["name"] } shouldContainExactlyInAnyOrder listOf("alice", "bob")
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── Sequence-Reseeding ──────────────────────────────────────

    test("sequence reseeding: nextval returns MAX(id)+1 after import") {
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE users DISABLE TRIGGER trg_user_insert")
                stmt.execute("INSERT INTO users (id, name, score) VALUES (10, 'manual', 0)")
                stmt.execute("ALTER TABLE users ENABLE TRIGGER trg_user_insert")
            }
        }

        val dataFile = Files.createTempFile("pg-reseed-", ".json")
        dataFile.deleteIfExists()
        try {
            exportToFile("json", dataFile)
            truncateAll()

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                        "--trigger-mode", "disable",
                    )
                )
            }

            DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
                conn.prepareStatement("SELECT nextval('users_id_seq')").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 11L
                    }
                }
            }
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --truncate ──────────────────────────────────────────────

    test("--truncate replaces pre-existing data") {
        // Seed source data (will be exported)
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE users DISABLE TRIGGER trg_user_insert")
                stmt.execute("INSERT INTO users (id, name, active, score) VALUES (1, 'alice', true, 50)")
                stmt.execute("ALTER TABLE users ENABLE TRIGGER trg_user_insert")
            }
        }

        val dataFile = Files.createTempFile("pg-truncate-", ".json")
        dataFile.deleteIfExists()
        try {
            exportToFile("json", dataFile)
            // Now add pre-existing data that should be removed by truncate
            DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("ALTER TABLE users DISABLE TRIGGER trg_user_insert")
                    stmt.execute("INSERT INTO users (id, name, active, score) VALUES (99, 'pre-existing', true, 0)")
                    stmt.execute("ALTER TABLE users ENABLE TRIGGER trg_user_insert")
                }
            }

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                        "--truncate",
                        "--trigger-mode", "disable",
                    )
                )
            }

            val rows = queryAll("users")
            rows.size shouldBe 1
            rows[0]["name"] shouldBe "alice"
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --on-conflict update ────────────────────────────────────

    test("--on-conflict update upserts rows") {
        // Seed source with alice + bob
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE users DISABLE TRIGGER trg_user_insert")
                stmt.execute("INSERT INTO users (id, name, active, score) VALUES (1, 'alice', true, 98.50)")
                stmt.execute("INSERT INTO users (id, name, active, score) VALUES (2, 'bob', false, 75.25)")
                stmt.execute("ALTER TABLE users ENABLE TRIGGER trg_user_insert")
            }
        }

        val dataFile = Files.createTempFile("pg-upsert-", ".json")
        dataFile.deleteIfExists()
        try {
            exportToFile("json", dataFile)
            // Replace data with stale version of id=1
            truncateAll()
            DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("ALTER TABLE users DISABLE TRIGGER trg_user_insert")
                    stmt.execute("INSERT INTO users (id, name, active, score) VALUES (1, 'OLD', true, 0)")
                    stmt.execute("ALTER TABLE users ENABLE TRIGGER trg_user_insert")
                }
            }

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                        "--on-conflict", "update",
                        "--trigger-mode", "disable",
                    )
                )
            }

            val rows = queryAll("users")
            rows.size shouldBe 2
            rows.first { (it["id"] as Number).toInt() == 1 }["name"] shouldBe "alice"
            rows.first { (it["id"] as Number).toInt() == 2 }["name"] shouldBe "bob"
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --trigger-mode disable ──────────────────────────────────

    test("--trigger-mode disable prevents trigger from firing") {
        val dataFile = Files.createTempFile("pg-trigger-", ".json")
        Files.writeString(dataFile, """[{"id":1,"name":"triggertest","email":null,"active":true,"score":"0","updated_at":"2026-01-01T00:00:00"}]""")
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                        "--trigger-mode", "disable",
                    )
                )
            }

            // audit_log must be empty — trigger was disabled
            queryAll("audit_log").size shouldBe 0
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    test("trigger fires normally when --trigger-mode is fire (default)") {
        val dataFile = Files.createTempFile("pg-trigger-fire-", ".json")
        Files.writeString(dataFile, """[{"id":1,"name":"firedtest","email":null,"active":true,"score":"0","updated_at":"2026-01-01T00:00:00"}]""")
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                    )
                )
            }

            val auditRows = queryAll("audit_log")
            auditRows.size shouldBe 1
            (auditRows[0]["message"] as String) shouldContain "firedtest"
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── Incremental round-trip (Step 32) ────────────────────────

    test("incremental round-trip: initial → delta → UPSERT → correct merge") {
        // 1. Seed initial data
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE users DISABLE TRIGGER trg_user_insert")
                stmt.execute("INSERT INTO users (id, name, active, score, updated_at) VALUES (1, 'alice', true, 10, '2026-01-01 00:00:00')")
                stmt.execute("INSERT INTO users (id, name, active, score, updated_at) VALUES (2, 'bob', true, 20, '2026-01-01 00:00:00')")
                stmt.execute("ALTER TABLE users ENABLE TRIGGER trg_user_insert")
                // Create target table
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS users_target (
                        id         SERIAL PRIMARY KEY,
                        name       TEXT NOT NULL,
                        email      TEXT,
                        active     BOOLEAN NOT NULL DEFAULT TRUE,
                        score      NUMERIC(10, 2),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent()
                )
                stmt.execute("TRUNCATE TABLE users_target RESTART IDENTITY")
            }
        }

        val initialFile = Files.createTempFile("pg-incr-init-", ".json")
        val deltaFile = Files.createTempFile("pg-incr-delta-", ".json")
        initialFile.deleteIfExists()
        deltaFile.deleteIfExists()
        try {
            // 2. Initial export
            exportToFile("json", initialFile)

            // 3. Initial import into target
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", initialFile.toString(),
                        "--format", "json",
                        "--table", "users_target",
                    )
                )
            }

            // 4. Modify source: update alice, add charlie
            val checkpoint = "2026-06-01T00:00:00"
            DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("ALTER TABLE users DISABLE TRIGGER trg_user_insert")
                    stmt.execute("UPDATE users SET name = 'ALICE_UPDATED', updated_at = '2026-06-15 12:00:00' WHERE id = 1")
                    stmt.execute("INSERT INTO users (id, name, active, score, updated_at) VALUES (3, 'charlie', true, 30, '2026-06-15 12:00:00')")
                    stmt.execute("ALTER TABLE users ENABLE TRIGGER trg_user_insert")
                }
            }

            // 5. Delta export with --since
            captureStdout {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "json",
                        "--tables", "users",
                        "--output", deltaFile.toString(),
                        "--since-column", "updated_at",
                        "--since", checkpoint,
                    )
                )
            }

            // 6. Delta import with --on-conflict update
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", deltaFile.toString(),
                        "--format", "json",
                        "--table", "users_target",
                        "--on-conflict", "update",
                    )
                )
            }

            // 7. Verify target
            val rows = queryAll("users_target")
            rows.size shouldBe 3
            rows.first { (it["id"] as Number).toInt() == 1 }["name"] shouldBe "ALICE_UPDATED"
            rows.first { (it["id"] as Number).toInt() == 2 }["name"] shouldBe "bob"
            rows.first { (it["id"] as Number).toInt() == 3 }["name"] shouldBe "charlie"
        } finally {
            Files.deleteIfExists(initialFile)
            Files.deleteIfExists(deltaFile)
            DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
                conn.createStatement().use { it.execute("DROP TABLE IF EXISTS users_target") }
            }
        }
    }
})
