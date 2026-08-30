package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.DiffDdlGenerator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Eine Partition kommt dazu oder faellt weg — gegen echtes PostgreSQL.
 *
 * In PostgreSQL ist eine Partition eine eigene Tabelle. Der Beleg ist deshalb
 * nicht nur das gerenderte `CREATE TABLE … PARTITION OF …`, sondern dass der
 * Server sie als Kind fuehrt und die Zeilen der uebrigen Kinder stehen
 * bleiben.
 *
 * Der **DEFAULT-Fall** steht mit im Test: solange eine DEFAULT-Partition
 * Zeilen des neuen Bereichs traegt, lehnt PostgreSQL das Anlegen ab. Das ist
 * eine Server-Eigenschaft, keine Rendering-Frage — und sie faellt nur live auf.
 */
class PostgresPartitionMigrateIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun child(name: String, from: String, to: String) = PartitionDefinition(
        name = name,
        from = listOf(PartitionBound.Value("'$from'")),
        to = listOf(PartitionBound.Value("'$to'")),
    )

    fun desiredWith(vararg children: PartitionDefinition) = SchemaDefinition(
        name = "partition-desired", version = "1",
        tables = mapOf(
            "events" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                    "placed_on" to ColumnDefinition(NeutralType.Date, required = true),
                ),
                partitioning = PartitionConfig(PartitionType.RANGE, listOf("placed_on"), children.toList()),
            ),
        ),
    )

    fun migrate(desired: SchemaDefinition, executed: MutableList<String>): Int {
        val tmp = createTempDirectory("pg-partition-migrate")
        return try {
            SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> livePgOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d ->
                    if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else noPgRenderer()
                },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { _, _ -> Unit },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.POSTGRESQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    val q1 = child("events_2026q1", "2026-01-01", "2026-04-01")
    val q2 = child("events_2026q2", "2026-04-01", "2026-07-01")

    beforeTest {
        execPgDdl(
            pool,
            "DROP TABLE IF EXISTS events CASCADE",
            "CREATE TABLE events (id BIGINT NOT NULL, placed_on DATE NOT NULL) PARTITION BY RANGE (placed_on)",
            "CREATE TABLE events_2026q1 PARTITION OF events FOR VALUES FROM ('2026-01-01') TO ('2026-04-01')",
            "INSERT INTO events (id, placed_on) VALUES (1, '2026-02-01')",
        )
    }

    test("eine hinzugekommene Partition entsteht als Kindtabelle") {
        val executed = mutableListOf<String>()

        val exit = migrate(desiredWith(q1, q2), executed)

        withClue("migrate meldete $exit\nausgefuehrt:\n" + executed.joinToString("\n")) {
            (exit == 0 || exit == 5) shouldBe true
            executed.any { it.contains("PARTITION OF") && it.contains("events_2026q2") } shouldBe true
        }
        pgChildPartitions(pool, "events") shouldBe listOf("events_2026q1", "events_2026q2")
        pgRowCount(pool, "events") shouldBe 1
    }

    test("eine entfallene Partition wird als Kindtabelle verworfen") {
        execPgDdl(
            pool,
            "CREATE TABLE events_2026q2 PARTITION OF events FOR VALUES FROM ('2026-04-01') TO ('2026-07-01')",
            "INSERT INTO events (id, placed_on) VALUES (2, '2026-05-01')",
        )
        val executed = mutableListOf<String>()

        val exit = migrate(desiredWith(q1), executed)

        withClue("migrate meldete $exit\nausgefuehrt:\n" + executed.joinToString("\n")) {
            // Ein Kind zu verwerfen ist zerstoerend; ohne `--allow-destructive`
            // blockt der Lauf (Exit 8) und fuehrt nichts aus. Genau das ist die
            // gewuenschte Vorsicht — der Nachweis ist, dass es an der
            // Bestaetigung haengt und nicht an fehlendem Rendering.
            exit shouldBe 8
        }
        pgChildPartitions(pool, "events") shouldBe listOf("events_2026q1", "events_2026q2")
    }

    test("PostgreSQL lehnt eine Partition ab, deren Bereich die DEFAULT-Partition traegt") {
        execPgDdl(
            pool,
            "CREATE TABLE events_default PARTITION OF events DEFAULT",
            "INSERT INTO events (id, placed_on) VALUES (3, '2026-05-01')",
        )
        val executed = mutableListOf<String>()

        val exit = migrate(
            desiredWith(q1, q2, PartitionDefinition(name = "events_default", isDefault = true)),
            executed,
        )

        withClue("migrate meldete $exit\nausgefuehrt:\n" + executed.joinToString("\n")) {
            // Der Server, nicht das Werkzeug, sagt hier Nein: die Zeile vom
            // 2026-05-01 liegt in der DEFAULT-Partition und gehoerte in die
            // neue. PostgreSQL prueft das beim Anlegen.
            (exit != 0) shouldBe true
        }
        pgChildPartitions(pool, "events").contains("events_2026q2") shouldBe false
    }
})

private fun execPgDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
    }
}

/** Die Kindtabellen, die der Server heute an der Elterntabelle fuehrt. */
private fun pgChildPartitions(pool: ConnectionPool, parent: String): List<String> =
    pool.borrow().asJdbc().use { conn ->
        conn.prepareStatement(
            "SELECT c.relname FROM pg_inherits i " +
                "JOIN pg_class c ON c.oid = i.inhrelid " +
                "WHERE i.inhparent = ?::regclass ORDER BY c.relname",
        ).use { stmt ->
            stmt.setString(1, parent)
            stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

private fun pgRowCount(pool: ConnectionPool, table: String): Int =
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }
    }

private fun livePgOperand(pool: ConnectionPool): ResolvedSchemaOperand = ResolvedSchemaOperand(
    reference = "live-pg",
    schema = PostgresSchemaReader().read(pool, SchemaReadOptions()).schema,
    validation = ValidationResult(),
    dialect = DatabaseDialect.POSTGRESQL,
)

private fun noPgRenderer(): DiffDdlGenerator = error("test wires only the PostgreSQL renderer")
