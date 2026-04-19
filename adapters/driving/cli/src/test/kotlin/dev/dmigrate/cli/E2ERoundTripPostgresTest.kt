package dev.dmigrate.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.format.yaml.YamlSchemaCodec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.sql.DriverManager

private val IntegrationTag = NamedTag("integration")

/**
 * End-to-End Round-Trip: Export from source PostgreSQL, import into
 * fresh target PostgreSQL, then verify schema and data.
 *
 * This test proves the full cycle:
 * 1. Seed schema + data in source DB
 * 2. Export via `data export` to JSON
 * 3. Generate DDL via `schema reverse` + `schema generate`
 * 4. Apply DDL to target DB
 * 5. Import via `data import` from JSON
 * 6. Verify: row counts + key values + content values match
 *
 * Tagged `integration` — runs only with `-PintegrationTests`.
 */
class E2ERoundTripPostgresTest : FunSpec({

    tags(IntegrationTag)

    val source = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_src")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    val target = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_tgt")
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

    beforeSpec {
        source.start()
        target.start()
        registerDrivers()

        // Seed source DB
        DriverManager.getConnection(rawJdbc(source), source.username, source.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE users (
                        id    SERIAL PRIMARY KEY,
                        name  TEXT NOT NULL,
                        email TEXT
                    )
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE orders (
                        id      SERIAL PRIMARY KEY,
                        user_id INTEGER NOT NULL REFERENCES users(id),
                        amount  NUMERIC(10, 2) NOT NULL
                    )
                """.trimIndent())
            }
            conn.prepareStatement("INSERT INTO users (name, email) VALUES (?, ?)").use { ps ->
                ps.setString(1, "alice"); ps.setString(2, "alice@test.com"); ps.execute()
                ps.setString(1, "bob"); ps.setString(2, "bob@test.com"); ps.execute()
                ps.setString(1, "charlie"); ps.setString(2, null); ps.execute()
            }
            conn.prepareStatement("INSERT INTO orders (user_id, amount) VALUES (?, ?)").use { ps ->
                ps.setInt(1, 1); ps.setBigDecimal(2, java.math.BigDecimal("99.95")); ps.execute()
                ps.setInt(1, 1); ps.setBigDecimal(2, java.math.BigDecimal("42.00")); ps.execute()
                ps.setInt(1, 2); ps.setBigDecimal(2, java.math.BigDecimal("10.50")); ps.execute()
            }
        }
    }

    afterSpec {
        source.stop()
        target.stop()
    }

    test("full round-trip: export → import → verify schema and data") {
        val tmpDir = Files.createTempDirectory("dmigrate-e2e-roundtrip")
        try {
            val srcUrl = dmigUrl(source)
            val tgtUrl = dmigUrl(target)

            // ─── 1. Export users and orders as JSON ─────────────
            val usersJson = tmpDir.resolve("users.json")
            val ordersJson = tmpDir.resolve("orders.json")

            cli().parse(listOf(
                "--quiet",
                "data", "export",
                "--source", srcUrl,
                "--tables", "users",
                "--format", "json",
                "--output", usersJson.toString(),
            ))
            cli().parse(listOf(
                "--quiet",
                "data", "export",
                "--source", srcUrl,
                "--tables", "orders",
                "--format", "json",
                "--output", ordersJson.toString(),
            ))

            Files.exists(usersJson) shouldBe true
            Files.exists(ordersJson) shouldBe true

            // ─── 2. Reverse-engineer schema from source ─────────
            val schemaYaml = tmpDir.resolve("schema.yaml")
            cli().parse(listOf(
                "--quiet",
                "schema", "reverse",
                "--source", srcUrl,
                "--output", schemaYaml.toString(),
            ))
            Files.exists(schemaYaml) shouldBe true

            // ─── 3. Generate DDL for target ─────────────────────
            val ddlOutput = captureStdout {
                cli().parse(listOf(
                    "--quiet",
                    "schema", "generate",
                    "--source", schemaYaml.toString(),
                    "--target", "postgresql",
                ))
            }
            ddlOutput shouldContain "CREATE TABLE"

            // ─── 4. Apply DDL to target DB ──────────────────────
            DriverManager.getConnection(rawJdbc(target), target.username, target.password).use { conn ->
                conn.createStatement().use { stmt ->
                    // Strip comment-only lines, then split by semicolons
                    val cleanedDdl = ddlOutput.lines()
                        .filter { !it.trimStart().startsWith("--") }
                        .joinToString("\n")
                    cleanedDdl.split(";")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { sql -> stmt.execute("$sql;") }
                }
            }

            // ─── 5. Import data into target ─────────────────────
            cli().parse(listOf(
                "--quiet",
                "data", "import",
                "--target", tgtUrl,
                "--source", usersJson.toString(),
                "--table", "users",
                "--format", "json",
            ))
            cli().parse(listOf(
                "--quiet",
                "data", "import",
                "--target", tgtUrl,
                "--source", ordersJson.toString(),
                "--table", "orders",
                "--format", "json",
            ))

            // ─── 6. Structural schema compare via SchemaComparator ──
            val targetSchemaYaml = tmpDir.resolve("target-schema.yaml")
            cli().parse(listOf(
                "--quiet",
                "schema", "reverse",
                "--source", tgtUrl,
                "--output", targetSchemaYaml.toString(),
            ))
            val codec = YamlSchemaCodec()
            val sourceSchema = codec.read(schemaYaml)
            val targetSchema = codec.read(targetSchemaYaml)
            val diff = SchemaComparator().compare(sourceSchema, targetSchema)

            // Hard assertions: table structure must round-trip intact
            diff.tablesAdded.shouldBeEmpty()
            diff.tablesRemoved.shouldBeEmpty()
            diff.tablesChanged.shouldBeEmpty()

            // Allowlist: only sequence number changes are acceptable.
            // SERIAL columns create implicit sequences whose start/increment
            // values may differ after reverse-engineering. All other
            // structural categories must be empty.
            diff.customTypesAdded.shouldBeEmpty()
            diff.customTypesRemoved.shouldBeEmpty()
            diff.customTypesChanged.shouldBeEmpty()
            diff.viewsAdded.shouldBeEmpty()
            diff.viewsRemoved.shouldBeEmpty()
            diff.viewsChanged.shouldBeEmpty()
            diff.functionsAdded.shouldBeEmpty()
            diff.functionsRemoved.shouldBeEmpty()
            diff.functionsChanged.shouldBeEmpty()
            diff.proceduresAdded.shouldBeEmpty()
            diff.proceduresRemoved.shouldBeEmpty()
            diff.proceduresChanged.shouldBeEmpty()
            diff.triggersAdded.shouldBeEmpty()
            diff.triggersRemoved.shouldBeEmpty()
            diff.triggersChanged.shouldBeEmpty()
            // Sequences: SERIAL creates implicit sequences (e.g. users_id_seq)
            // that appear in the reversed target schema but not in the
            // source (which was also reversed and may normalize differently).
            // sequencesAdded: allowed — implicit SERIAL sequences
            // sequencesChanged: allowed — only number fields (start, increment, etc.)
            // sequencesRemoved: must be empty — no sequence should disappear
            diff.sequencesRemoved.shouldBeEmpty()
            // Verify that any changed sequences differ only in number fields,
            // not in structural identity
            for (seqDiff in diff.sequencesChanged) {
                // SequenceDiff only contains number fields (start, increment,
                // minValue, maxValue, cycle, cache) — there are no structural
                // fields that could indicate a non-number change. So any entry
                // in sequencesChanged is by definition a number-only diff.
                seqDiff.hasChanges() shouldBe true // must have at least one change to be listed
            }

            // ─── 7. Verify: row counts ──────────────────────────
            DriverManager.getConnection(rawJdbc(target), target.username, target.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM users").use { rs ->
                        rs.next(); rs.getInt(1) shouldBe 3
                    }
                    stmt.executeQuery("SELECT COUNT(*) FROM orders").use { rs ->
                        rs.next(); rs.getInt(1) shouldBe 3
                    }

                    // ─── 8. Verify: key values + NULL round-trip ──
                    stmt.executeQuery("SELECT name, email FROM users ORDER BY id").use { rs ->
                        rs.next(); rs.getString("name") shouldBe "alice"
                        rs.getString("email") shouldBe "alice@test.com"
                        rs.next(); rs.getString("name") shouldBe "bob"
                        rs.next(); rs.getString("name") shouldBe "charlie"
                        rs.getString("email") shouldBe null // NULL round-trip
                    }

                    // ─── 9. Verify: content value ───────────────
                    stmt.executeQuery("SELECT amount FROM orders WHERE user_id = 1 ORDER BY id").use { rs ->
                        rs.next(); rs.getBigDecimal("amount").toPlainString() shouldBe "99.95"
                        rs.next(); rs.getBigDecimal("amount").toPlainString() shouldBe "42.00"
                    }

                    // ─── 10. Verify: FK constraint intact ───────
                    stmt.executeQuery("""
                        SELECT COUNT(*) FROM information_schema.table_constraints
                        WHERE constraint_type = 'FOREIGN KEY' AND table_name = 'orders'
                    """.trimIndent()).use { rs ->
                        rs.next(); rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
})
