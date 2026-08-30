package dev.dmigrate.driver.mssql

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
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mssqlserver.MSSQLServerContainer
import kotlin.io.path.createTempDirectory

/**
 * Partitionierung im Migrationspfad, gegen echtes SQL Server.
 *
 * Zwei Dinge stehen hier fest, die kein Renderer-Test zeigen kann:
 *
 * - Der Server nimmt Partition Function, Scheme und Tabelle in der Reihenfolge
 *   an, in der der Pfad sie schickt.
 * - Eine **eingefuegte Grenze** wird als `SPLIT RANGE` ausgefuehrt und behaelt
 *   die Zeilen. Im neutralen Modell sieht dieselbe Aenderung wie ein
 *   entferntes und zwei hinzugekommene Kinder aus — wer dem woertlich folgte,
 *   verloere die Zeilen der aufgeteilten Partition.
 */
class MssqlPartitionMigrateIntegrationTest : FunSpec({

    val container = startMssqlContainer()

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = poolFor(container, "dmigrate_partitions")
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("migrate creates a partitioned table on the real server") {
        val tmp = createTempDirectory("mssql-partition-migrate")
        try {
            val desired = SchemaDefinition(
                name = "part-desired",
                version = "1",
                tables = mapOf(
                    "events" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "bucket" to ColumnDefinition(NeutralType.Integer, required = true),
                        ),
                        partitioning = PartitionConfig(
                            type = PartitionType.RANGE,
                            key = listOf("bucket"),
                            partitions = listOf(
                                PartitionDefinition(
                                    name = "p_low",
                                    from = listOf(PartitionBound.MinValue),
                                    to = listOf(PartitionBound.Value("100")),
                                ),
                                PartitionDefinition(
                                    name = "p_high",
                                    from = listOf(PartitionBound.Value("100")),
                                    to = listOf(PartitionBound.MaxValue),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val errors = mutableListOf<String>()
            val executed = mutableListOf<String>()
            val migrateExit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )

            withClue(
                "migrate meldete $migrateExit\nausgefuehrt:\n" + executed.joinToString("\n") +
                    "\nmeldungen:\n" + errors.joinToString("\n"),
            ) {
                // Exit 5 waere hier NICHT der Ausfuehrungsfehler, sondern der
                // Post-Compare: die Kindnamen ueberleben SQL Server nicht (R346),
                // der Server nummeriert sie. Genau der Fall, fuer den das
                // partition-mapping-Overlay geplant ist.
                (migrateExit == 0 || migrateExit == 5) shouldBe true
            }
            executed.any { it.contains("CREATE PARTITION FUNCTION") } shouldBe true

            // Der eigentliche Nachweis: der Server hat die Tabelle partitioniert
            // angelegt — zwei Grenzen, ein Schluessel.
            val readBack = readSchema(pool).tables.getValue("events")
            val partitioning = readBack.partitioning
            withClue("die zurueckgelesene Tabelle traegt keine Partitionierung") {
                (partitioning != null) shouldBe true
            }
            partitioning!!.key shouldBe listOf("bucket")
            partitioning.partitions.size shouldBe 2
        } finally {
            execDdl(pool, "IF OBJECT_ID('events') IS NOT NULL DROP TABLE events")
            runCatching { execDdl(pool, "DROP PARTITION SCHEME ps_events") }
            runCatching { execDdl(pool, "DROP PARTITION FUNCTION pf_events") }
            tmp.toFile().deleteRecursively()
        }
    }

    test("eine eingefuegte Grenze wird als SPLIT ausgefuehrt und behaelt die Zeilen") {
        val tmp = createTempDirectory("mssql-partition-split")
        try {
            execDdl(
                pool,
                "CREATE PARTITION FUNCTION pf_events (INT) AS RANGE RIGHT FOR VALUES (100)",
                "CREATE PARTITION SCHEME ps_events AS PARTITION pf_events ALL TO ([PRIMARY])",
                "CREATE TABLE events (id BIGINT NOT NULL, bucket INT NOT NULL) ON ps_events (bucket)",
                "INSERT INTO events (id, bucket) VALUES (1, 50), (2, 150), (3, 250)",
            )

            // Der Zielzustand hat eine Grenze mehr — und andere Kindnamen als
            // die, die der Reverse vergibt (`p1`, `p2`). Dass die Namen nicht
            // stoeren, ist Teil des Nachweises: die Zuordnung laeuft ueber die
            // Grenzen, nicht ueber den Namen.
            val desired = SchemaDefinition(
                name = "split-desired",
                version = "1",
                tables = mapOf(
                    "events" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "bucket" to ColumnDefinition(NeutralType.Integer, required = true),
                        ),
                        partitioning = PartitionConfig(
                            type = PartitionType.RANGE,
                            key = listOf("bucket"),
                            partitions = listOf(
                                PartitionDefinition(
                                    name = "p_low",
                                    from = listOf(PartitionBound.MinValue),
                                    to = listOf(PartitionBound.Value("100")),
                                ),
                                PartitionDefinition(
                                    name = "p_mid",
                                    from = listOf(PartitionBound.Value("100")),
                                    to = listOf(PartitionBound.Value("200")),
                                ),
                                PartitionDefinition(
                                    name = "p_high",
                                    from = listOf(PartitionBound.Value("200")),
                                    to = listOf(PartitionBound.MaxValue),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val errors = mutableListOf<String>()
            val executed = mutableListOf<String>()
            val migrateExit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )

            withClue(
                "migrate meldete $migrateExit\nausgefuehrt:\n" + executed.joinToString("\n") +
                    "\nmeldungen:\n" + errors.joinToString("\n"),
            ) {
                // Exit 5 = Post-Compare-Rest: die Kindnamen ueberleben SQL Server
                // nicht (R346), der Server nummeriert sie.
                (migrateExit == 0 || migrateExit == 5) shouldBe true
                executed.any { it.contains("SPLIT RANGE (200)") } shouldBe true
                executed.none { it.startsWith("CREATE TABLE") || it.startsWith("DROP TABLE") } shouldBe true
            }

            // Der eigentliche Nachweis: eine Grenze mehr, und alle drei Zeilen
            // stehen noch — ein Drop-und-Neuanlegen haette sie verloren.
            readSchema(pool).tables.getValue("events").partitioning!!.partitions.size shouldBe 3
            rowCount(pool, "events") shouldBe 3
            distinctPartitions(pool, "events") shouldBe 3
        } finally {
            execDdl(pool, "IF OBJECT_ID('events') IS NOT NULL DROP TABLE events")
            runCatching { execDdl(pool, "DROP PARTITION SCHEME ps_events") }
            runCatching { execDdl(pool, "DROP PARTITION FUNCTION pf_events") }
            tmp.toFile().deleteRecursively()
        }
    }
})

/** Wie viele Abschnitte die Partition Function der Tabelle heute hat. */
private fun distinctPartitions(pool: ConnectionPool, table: String): Int =
    pool.borrow().asJdbc().use { conn ->
        conn.prepareStatement(
            "SELECT COUNT(*) FROM sys.partitions WHERE object_id = OBJECT_ID(?) AND index_id IN (0, 1)",
        ).use { stmt ->
            stmt.setString(1, table)
            stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

private fun rowCount(pool: ConnectionPool, table: String): Int =
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }
    }
