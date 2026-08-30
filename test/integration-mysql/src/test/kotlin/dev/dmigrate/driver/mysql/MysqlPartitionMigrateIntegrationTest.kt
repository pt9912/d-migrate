package dev.dmigrate.driver.mysql

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
import org.testcontainers.mysql.MySQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Partitionen kommen dazu — gegen echtes MySQL.
 *
 * Der Beleg ist die **Stelle**: MySQL verlangt aufsteigende
 * `VALUES LESS THAN`-Grenzen, deshalb gilt hinter der letzten Grenze
 * `ADD PARTITION` und dazwischen `REORGANIZE PARTITION`. Ein `ADD` in der
 * Mitte lehnt der Server ab (ER_RANGE_NOT_INCREASING_ERROR), ein
 * `REORGANIZE` nimmt die Zeilen mit.
 */
class MysqlPartitionMigrateIntegrationTest : FunSpec({

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MYSQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
                params = mapOf("allowPublicKeyRetrieval" to "true"),
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun child(name: String, upper: String) =
        PartitionDefinition(name = name, to = listOf(PartitionBound.Value(upper)))

    fun desiredWith(vararg children: PartitionDefinition) = SchemaDefinition(
        name = "partition-desired", version = "1",
        tables = mapOf(
            "events" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                    "bucket" to ColumnDefinition(NeutralType.Integer, required = true),
                ),
                partitioning = PartitionConfig(PartitionType.RANGE, listOf("bucket"), children.toList()),
            ),
        ),
    )

    fun migrate(desired: SchemaDefinition, executed: MutableList<String>, errors: MutableList<String>): Int {
        val tmp = createTempDirectory("mysql-partition-migrate")
        return try {
            SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveMysqlOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MYSQL) MysqlDiffDdlGenerator() else noMysqlRenderer() },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MYSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    beforeTest {
        execMysqlDdl(
            pool,
            "DROP TABLE IF EXISTS events",
            "CREATE TABLE events (id BIGINT NOT NULL, bucket INT NOT NULL) " +
                "PARTITION BY RANGE COLUMNS(bucket) (" +
                "PARTITION p100 VALUES LESS THAN (100), PARTITION p300 VALUES LESS THAN (300))",
            "INSERT INTO events (id, bucket) VALUES (1, 50), (2, 150), (3, 250)",
        )
    }

    test("hinter der letzten Grenze haengt der Server die Partition an") {
        val executed = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val exit = migrate(
            desiredWith(child("p100", "100"), child("p300", "300"), child("p400", "400")),
            executed,
            errors,
        )

        withClue(
            "migrate meldete $exit\nausgefuehrt:\n" + executed.joinToString("\n") +
                "\nmeldungen:\n" + errors.joinToString("\n"),
        ) {
            (exit == 0 || exit == 5) shouldBe true
            executed.any { it.contains("ADD PARTITION") } shouldBe true
        }
        mysqlPartitionNames(pool, "events") shouldBe listOf("p100", "p300", "p400")
        mysqlRowCount(pool, "events") shouldBe 3
    }

    test("dazwischen teilt der Server die folgende Partition auf und behaelt die Zeilen") {
        val executed = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val exit = migrate(
            desiredWith(child("p100", "100"), child("p200", "200"), child("p300", "300")),
            executed,
            errors,
        )

        withClue(
            "migrate meldete $exit\nausgefuehrt:\n" + executed.joinToString("\n") +
                "\nmeldungen:\n" + errors.joinToString("\n"),
        ) {
            (exit == 0 || exit == 5) shouldBe true
            executed.any { it.contains("REORGANIZE PARTITION") } shouldBe true
        }
        mysqlPartitionNames(pool, "events") shouldBe listOf("p100", "p200", "p300")
        // Die Zeile mit bucket=150 lag in p300 und liegt jetzt in p200 — ein
        // Drop-und-Neuanlegen haette sie verloren.
        mysqlRowCount(pool, "events") shouldBe 3
    }
})

private fun execMysqlDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
    }
}

private fun mysqlPartitionNames(pool: ConnectionPool, table: String): List<String> =
    pool.borrow().asJdbc().use { conn ->
        conn.prepareStatement(
            "SELECT partition_name FROM information_schema.partitions " +
                "WHERE table_schema = DATABASE() AND table_name = ? AND partition_name IS NOT NULL " +
                "ORDER BY partition_ordinal_position",
        ).use { stmt ->
            stmt.setString(1, table)
            stmt.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

private fun mysqlRowCount(pool: ConnectionPool, table: String): Int =
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }
    }

private fun liveMysqlOperand(pool: ConnectionPool): ResolvedSchemaOperand = ResolvedSchemaOperand(
    reference = "live-mysql",
    schema = MysqlSchemaReader().read(pool, SchemaReadOptions()).schema,
    validation = ValidationResult(),
    dialect = DatabaseDialect.MYSQL,
)

private fun noMysqlRenderer(): DiffDdlGenerator = error("test wires only the MySQL renderer")
